package com.example.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class H264StreamEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrateMbps: Int,
    private val fps: Int = 60,
    private val onEncodedFrame: (ByteArray, Long) -> Unit,
    private val onMetrics: (fps: Int, kbps: Int) -> Unit
) {
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private val lastSyncRequestMs = AtomicLong(0L)

    fun start(): Surface {
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val codecCaps = runCatching {
            mediaCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull()
        val encCaps = codecCaps?.encoderCapabilities
        val chosenBitrateMode = selectBitrateMode(
            supportsCbr = encCaps?.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            ) == true,
            supportsVbr = encCaps?.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            ) == true
        )
        val supportedProfiles = codecCaps?.profileLevels
            ?.map { it.profile to it.level }
            ?: emptyList()
        val (chosenProfile, chosenLevel) = selectBaselineProfileLevel(supportedProfiles)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Short 1s GOP
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, IDLE_REPEAT_FRAME_US)
            if (chosenProfile != null) {
                setInteger(MediaFormat.KEY_PROFILE, chosenProfile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && chosenLevel != null) {
                setInteger(MediaFormat.KEY_LEVEL, chosenLevel)
            }
            if (chosenBitrateMode != null) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, chosenBitrateMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // Zero B-frames for low latency
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mediaCodec.createInputSurface()
        mediaCodec.start()

        codec = mediaCodec
        inputSurface = surface
        running.set(true)

        drainThread = Thread({ drainLoop(mediaCodec) }, "ExtDesk-H264Encoder").apply {
            start()
        }
        return surface
    }

    fun requestSyncFrameBurst(forceImmediate: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceImmediate && now - lastSyncRequestMs.get() < 250L) return
        lastSyncRequestMs.set(now)
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        running.set(false)
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        try {
            drainThread?.join(500)
        } catch (_: Throwable) {
        }
        drainThread = null
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    private fun drainLoop(mediaCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var frameCount = 0
        var byteAccum = 0L
        var windowStartMs = System.currentTimeMillis()
        var configBytes: ByteArray? = null

        while (running.get()) {
            val outIndex = try {
                mediaCodec.dequeueOutputBuffer(info, 10_000L)
            } catch (_: Throwable) {
                break
            }
            if (outIndex >= 0) {
                val outBuf = mediaCodec.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    outBuf.get(data)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        configBytes = data
                        onEncodedFrame(data, System.currentTimeMillis())
                    } else {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && configBytes != null) {
                            onEncodedFrame(configBytes!!, System.currentTimeMillis())
                        }
                        onEncodedFrame(data, System.currentTimeMillis())
                        frameCount++
                        byteAccum += data.size
                    }
                }
                mediaCodec.releaseOutputBuffer(outIndex, false)
            }

            val now = System.currentTimeMillis()
            val elapsed = now - windowStartMs
            if (elapsed >= 1000L) {
                val measuredFps = ((frameCount * 1000f) / elapsed).toInt()
                val kbps = ((byteAccum * 8L) / elapsed).toInt()
                onMetrics(measuredFps, kbps)
                frameCount = 0
                byteAccum = 0L
                windowStartMs = now
            }
        }
    }

    companion object {
        const val IDLE_REPEAT_FRAME_US = 100_000L

        fun selectBitrateMode(supportsCbr: Boolean, supportsVbr: Boolean): Int? = when {
            supportsCbr -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            supportsVbr -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            else -> null
        }

        /**
         * Forces H.264 Baseline Profile (Level 3.1 when supported) so legacy hardware decoders
         * such as TI OMAP4 / PowerVR SGX544 (OMX.IMG.MSVDX) do not reject High Profile streams.
         */
        fun selectBaselineProfileLevel(
            supportedProfileLevels: List<Pair<Int, Int>>
        ): Pair<Int?, Int?> {
            val baseline = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            val level31 = MediaCodecInfo.CodecProfileLevel.AVCLevel31
            if (supportedProfileLevels.isEmpty()) {
                return baseline to level31
            }
            val baselineLevels = supportedProfileLevels
                .filter { it.first == baseline }
                .map { it.second }
            if (baselineLevels.isEmpty()) {
                return null to null
            }
            val chosenLevel = if (baselineLevels.any { it >= level31 }) {
                level31
            } else {
                baselineLevels.maxOrNull()
            }
            return baseline to chosenLevel
        }

        fun writeLengthPrefixedFrame(out: DataOutputStream, nalBytes: ByteArray, probeTimestampMs: Long) {
            synchronized(out) {
                out.writeInt(nalBytes.size)
                out.writeLong(probeTimestampMs)
                out.write(nalBytes)
                out.flush()
            }
        }
    }
}
