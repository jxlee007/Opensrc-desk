package com.example.protocol

import com.example.model.DisplayProfile
import com.example.model.VirtualDisplayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
sealed class ControlMessage {
    /**
     * Step 1 (Host -> Display): Announces protocol version, nonces, and ephemeral ECDH pubkey.
     * Never transmits any raw pairingToken. Defaults to protocolVersion = 1 if omitted by legacy peers.
     */
    @Serializable
    data class HandshakeRequest(
        val protocolVersion: Int = 1,
        val minSupportedProtocolVersion: Int = 1,
        val hostId: String,
        val hostName: String,
        val hostNonce: String = "",
        val hostEcdhPubKey: String? = null,
        val expectSavedTrust: Boolean = false,
        val transportMode: String = "LOCAL_WIFI"
    ) : ControlMessage()

    /**
     * Step 2 (Display -> Host): Negotiates protocol version, supplies Display nonce + ECDH pubkey,
     * and returns HMAC-SHA-256 proof when the Host is already trusted on this Display.
     */
    @Serializable
    data class HandshakeChallenge(
        val negotiatedProtocolVersion: Int = PairingCrypto.PROTOCOL_VERSION,
        val peerId: String,
        val displayNonce: String,
        val displayProofHmac: String? = null,
        val requiresUserApproval: Boolean = true,
        val displayEcdhPubKey: String? = null,
        val rejectionReason: String? = null,
        val sasTimeoutMs: Long = PairingCrypto.DEFAULT_SAS_TIMEOUT_MS,
        val sessionOnly: Boolean = false,
        val previousPersistFailed: Boolean = false
    ) : ControlMessage()

    /**
     * Step 3 (Host -> Display): Returns mutual HMAC-SHA-256 proof for trusted reconnects, or
     * indicates user confirmation of the 6-digit SAS verification code during initial pairing.
     */
    @Serializable
    data class HandshakeAuthResponse(
        val hostProofHmac: String? = null,
        val sasConfirmed: Boolean = false
    ) : ControlMessage()

    /**
     * Step 4 (Display -> Host): Confirms trust & sends ECDH-sealed initial pairing token when approved.
     */
    @Serializable
    data class TrustApprovalResult(
        val approved: Boolean,
        val sealedPairingToken: String? = null,
        val sealedPairingTag: String? = null,
        val profile: DisplayProfile? = null,
        val reason: String? = null
    ) : ControlMessage()

    @Serializable
    data class HandshakeAck(
        val config: VirtualDisplayConfig,
        val videoPort: Int,
        val desktopMode: String
    ) : ControlMessage()

    @Serializable
    data class HandshakeCommitAck(
        val committed: Boolean,
        val sessionOnly: Boolean = false
    ) : ControlMessage()

    @Serializable
    data class HandshakeCommitFinal(
        val accepted: Boolean,
        val reason: String? = null
    ) : ControlMessage()

    @Serializable
    data class HandshakeCommitPersistError(
        val reason: String,
        val sessionOnlyFallback: Boolean = true
    ) : ControlMessage()

    @Serializable
    data class ModeChange(
        val reason: String,
        val newMode: String
    ) : ControlMessage()

    @Serializable
    data class LatencyProbe(
        val timestampMs: Long
    ) : ControlMessage()

    @Serializable
    data class KeyEventMsg(
        val keyCode: Int,
        val unicodeChar: Int = 0,
        val textInput: String? = null
    ) : ControlMessage()

    @Serializable
    data class LaunchApp(
        val packageName: String
    ) : ControlMessage()

    @Serializable
    data class DisplayOverride(
        val config: VirtualDisplayConfig
    ) : ControlMessage()

    @Serializable
    data class HeartbeatPing(
        val timestampMs: Long
    ) : ControlMessage()

    @Serializable
    data class HeartbeatAck(
        val timestampMs: Long
    ) : ControlMessage()

    @Serializable
    data class DisconnectMsg(
        val reason: String
    ) : ControlMessage()
}

object ControlCodec {
    const val FRAME_JSON: Byte = 0x01
    const val FRAME_BINARY_TOUCH: Byte = 0x02

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val boundedWriteScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ExtDeskBoundedWriteWatchdog").apply { isDaemon = true }
    }

    fun writeJsonFrame(out: DataOutputStream, message: ControlMessage) {
        val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeByte(FRAME_JSON.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    /**
     * Writes a JSON control frame within [timeoutMs]; closes [socket] immediately if the write
     * stalls past [timeoutMs] so neither Host nor Display can block commit cleanup.
     */
    fun writeJsonFrameBounded(
        socket: Socket,
        out: DataOutputStream,
        message: ControlMessage,
        timeoutMs: Long = PairingCrypto.COMMIT_WINDOW_MS
    ): Boolean {
        if (socket.isClosed) return false
        val completed = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val watchdog = boundedWriteScheduler.schedule(
            {
                if (completed.compareAndSet(false, true)) {
                    timedOut.set(true)
                    try {
                        socket.close()
                    } catch (_: Throwable) {
                    }
                }
            },
            timeoutMs.coerceAtLeast(1L),
            TimeUnit.MILLISECONDS
        )
        return try {
            writeJsonFrame(out, message)
            val finishedInTime = completed.compareAndSet(false, true)
            finishedInTime && !timedOut.get()
        } catch (_: Throwable) {
            completed.set(true)
            false
        } finally {
            watchdog.cancel(false)
        }
    }

    fun writeBinaryTouchFrame(out: DataOutputStream, buffer: ByteBuffer, sample: TouchSample) {
        sample.writeToBuffer(buffer)
        val arr = ByteArray(TouchSample.PACKET_BYTES)
        buffer.get(arr)
        synchronized(out) {
            out.writeByte(FRAME_BINARY_TOUCH.toInt())
            out.writeInt(TouchSample.PACKET_BYTES)
            out.write(arr)
            out.flush()
        }
    }

    sealed interface DecodedFrame {
        data class JsonControl(val message: ControlMessage) : DecodedFrame
        data class BinaryTouch(val bytes: ByteArray) : DecodedFrame
    }

    fun readNextFrame(input: DataInputStream): DecodedFrame {
        val frameType = input.readByte()
        val length = input.readInt()
        require(length in 1..(1024 * 1024)) { "Invalid frame length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return when (frameType) {
            FRAME_JSON -> {
                val msg = json.decodeFromString<ControlMessage>(payload.toString(Charsets.UTF_8))
                DecodedFrame.JsonControl(msg)
            }
            FRAME_BINARY_TOUCH -> DecodedFrame.BinaryTouch(payload)
            else -> throw IllegalStateException("Unknown frame type: $frameType")
        }
    }
}
