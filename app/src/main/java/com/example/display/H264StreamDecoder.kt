package com.example.display

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.example.model.LatencyProbeStats
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class H264StreamDecoder(
    private val onFirstOrResumedFrame: () -> Unit,
    private val onLatencyStats: (LatencyProbeStats) -> Unit,
    private val onStreamMetrics: (fps: Int) -> Unit,
    private val onDecoderStatus: (DecoderStatus) -> Unit = {}
) {
    sealed interface DecoderStatus {
        data class HardwareActive(val codecName: String) : DecoderStatus
        data class SoftwareFallbackActive(val codecName: String, val hwFailureReason: String) : DecoderStatus
        data class FatalError(val message: String) : DecoderStatus
    }

    data class NalPacketInfo(
        val nalTypes: List<Int>,
        val isCodecConfig: Boolean,
        val isKeyFrame: Boolean
    )

    private val running = AtomicBoolean(false)
    @Volatile
    private var currentSurface: Surface? = null
    @Volatile
    private var videoSocket: Socket? = null
    @Volatile
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    val lastFrameDecodedMs = AtomicLong(0L)
    private val latencyWindow = ArrayDeque<Long>(32)

    fun isActiveForSurface(surface: Surface?): Boolean =
        surface != null && running.get() && currentSurface === surface

    fun start(
        hostIp: String,
        videoPort: Int,
        width: Int,
        height: Int,
        outputSurface: Surface
    ) {
        if (isActiveForSurface(outputSurface)) {
            return
        }
        stop()
        currentSurface = outputSurface
        running.set(true)
        lastFrameDecodedMs.set(System.currentTimeMillis())
        workerThread = Thread({
            runDecodeLoop(hostIp, videoPort, width, height, outputSurface)
        }, "ExtDesk-H264Decoder").apply {
            start()
        }
    }

    fun stop() {
        running.set(false)
        currentSurface = null
        try {
            videoSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            workerThread?.join(400)
        } catch (_: Throwable) {
        }
        workerThread = null
        videoSocket = null
        releaseCodecQuietly(codec)
        codec = null
    }

    private fun releaseCodecQuietly(target: MediaCodec?) {
        if (target == null) return
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    private fun runDecodeLoop(
        hostIp: String,
        videoPort: Int,
        width: Int,
        height: Int,
        outputSurface: Surface
    ) {
        var usingSoftware = false
        var hwFailureReason: String? = null
        var cachedSps: ByteArray? = null
        var cachedPps: ByteArray? = null
        var cachedCsd0: ByteArray? = null
        var waitingForKeyFrameAfterReset = false

        try {
            val sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(hostIp, videoPort), 5000)
            }
            videoSocket = sock
            val inp = DataInputStream(sock.getInputStream())

            var mediaCodec: MediaCodec? = null
            try {
                val (created, name) = createAndStartCodec(
                    useSoftware = false,
                    width = width,
                    height = height,
                    surface = outputSurface,
                    csd0 = null
                )
                mediaCodec = created
                codec = created
                onDecoderStatus(DecoderStatus.HardwareActive(name))
            } catch (hwErr: Throwable) {
                if (!running.get()) return
                releaseCodecQuietly(mediaCodec)
                codec = null
                val hwReason = hwErr.message?.takeIf { it.isNotBlank() } ?: hwErr::class.java.simpleName
                hwFailureReason = hwReason
                usingSoftware = true
                try {
                    val (swCodec, swName) = createAndStartCodec(
                        useSoftware = true,
                        width = width,
                        height = height,
                        surface = outputSurface,
                        csd0 = null
                    )
                    mediaCodec = swCodec
                    codec = swCodec
                    onDecoderStatus(DecoderStatus.SoftwareFallbackActive(swName, hwReason))
                } catch (swErr: Throwable) {
                    if (running.get()) {
                        val swReason = swErr.message?.takeIf { it.isNotBlank() } ?: swErr::class.java.simpleName
                        onDecoderStatus(
                            DecoderStatus.FatalError("H.264 decode failed (HW: $hwReason · SW: $swReason)")
                        )
                    }
                    return
                }
            }

            val info = MediaCodec.BufferInfo()
            var frameCount = 0
            var fpsWindowStart = System.currentTimeMillis()

            while (running.get()) {
                val nalLen = inp.readInt()
                val probeTimestampMs = inp.readLong()
                if (nalLen <= 0 || nalLen > 4 * 1024 * 1024) break
                val nalBytes = ByteArray(nalLen)
                inp.readFully(nalBytes)

                val nalInfo = classifyAnnexBNalPacket(nalBytes)
                if (nalInfo.isCodecConfig) {
                    if (nalInfo.nalTypes.contains(NAL_SPS) && nalInfo.nalTypes.contains(NAL_PPS)) {
                        cachedCsd0 = nalBytes
                    } else if (nalInfo.nalTypes.contains(NAL_SPS)) {
                        cachedSps = nalBytes
                        cachedCsd0 = combineSpsPps(cachedSps, cachedPps) ?: nalBytes
                    } else if (nalInfo.nalTypes.contains(NAL_PPS)) {
                        cachedPps = nalBytes
                        cachedCsd0 = combineSpsPps(cachedSps, cachedPps) ?: nalBytes
                    }
                }

                if (waitingForKeyFrameAfterReset && !nalInfo.isCodecConfig && !nalInfo.isKeyFrame) {
                    continue
                }

                val flags = when {
                    nalInfo.isCodecConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    nalInfo.isKeyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }

                var activeCodec = mediaCodec
                var queued = false
                try {
                    if (activeCodec != null) {
                        queued = queueAndDrainFrame(
                            mediaCodec = activeCodec,
                            nalBytes = nalBytes,
                            flags = flags,
                            probeTimestampMs = probeTimestampMs,
                            info = info,
                            onFrameRendered = { frameCount++ }
                        )
                    }
                } catch (codecErr: Throwable) {
                    if (!running.get()) break
                    val errReason = codecErr.message?.takeIf { it.isNotBlank() } ?: codecErr::class.java.simpleName
                    releaseCodecQuietly(activeCodec)
                    codec = null
                    mediaCodec = null

                    if (!usingSoftware) {
                        usingSoftware = true
                        hwFailureReason = errReason
                        try {
                            val (swCodec, swName) = createAndStartCodec(
                                useSoftware = true,
                                width = width,
                                height = height,
                                surface = outputSurface,
                                csd0 = cachedCsd0
                            )
                            mediaCodec = swCodec
                            codec = swCodec
                            onDecoderStatus(DecoderStatus.SoftwareFallbackActive(swName, errReason))
                            if (nalInfo.isCodecConfig || nalInfo.isKeyFrame) {
                                queued = queueAndDrainFrame(
                                    mediaCodec = swCodec,
                                    nalBytes = nalBytes,
                                    flags = flags,
                                    probeTimestampMs = probeTimestampMs,
                                    info = info,
                                    onFrameRendered = { frameCount++ }
                                )
                                waitingForKeyFrameAfterReset = false
                            } else {
                                waitingForKeyFrameAfterReset = true
                            }
                        } catch (swErr: Throwable) {
                            val swReason = swErr.message?.takeIf { it.isNotBlank() } ?: swErr::class.java.simpleName
                            onDecoderStatus(
                                DecoderStatus.FatalError("H.264 HW failed ($errReason) · SW fallback failed ($swReason)")
                            )
                            break
                        }
                    } else {
                        onDecoderStatus(
                            DecoderStatus.FatalError(
                                "H.264 decode failed (${hwFailureReason?.let { "HW: $it · " } ?: ""}SW: $errReason)"
                            )
                        )
                        break
                    }
                }

                if (queued && waitingForKeyFrameAfterReset && nalInfo.isKeyFrame) {
                    waitingForKeyFrameAfterReset = false
                }

                val now = System.currentTimeMillis()
                if (now - fpsWindowStart >= 1000L) {
                    onStreamMetrics(frameCount)
                    frameCount = 0
                    fpsWindowStart = now
                }
            }
        } catch (_: EOFException) {
            // Host closed video stream cleanly
        } catch (_: IOException) {
            // Socket closed on disconnect or teardown
        } catch (t: Throwable) {
            if (running.get()) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                onDecoderStatus(DecoderStatus.FatalError("Video stream error: $msg"))
            }
        }
    }

    private fun queueAndDrainFrame(
        mediaCodec: MediaCodec,
        nalBytes: ByteArray,
        flags: Int,
        probeTimestampMs: Long,
        info: MediaCodec.BufferInfo,
        onFrameRendered: () -> Unit
    ): Boolean {
        var inIdx = mediaCodec.dequeueInputBuffer(10_000L)
        if (inIdx < 0) {
            // Drain output buffers first so congested legacy OMX pipelines free input slots
            drainAvailableOutputs(mediaCodec, info, probeTimestampMs, onFrameRendered)
            inIdx = mediaCodec.dequeueInputBuffer(20_000L)
        }
        if (inIdx >= 0) {
            val inBuf = mediaCodec.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                inBuf.put(nalBytes)
                mediaCodec.queueInputBuffer(
                    inIdx,
                    0,
                    nalBytes.size,
                    System.nanoTime() / 1000L,
                    flags
                )
            }
        }
        drainAvailableOutputs(mediaCodec, info, probeTimestampMs, onFrameRendered)
        return inIdx >= 0
    }

    private fun drainAvailableOutputs(
        mediaCodec: MediaCodec,
        info: MediaCodec.BufferInfo,
        probeTimestampMs: Long,
        onFrameRendered: () -> Unit
    ) {
        var outIdx = mediaCodec.dequeueOutputBuffer(info, 5_000L)
        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outIdx = mediaCodec.dequeueOutputBuffer(info, 5_000L)
                continue
            }
            val shouldRender = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0
            mediaCodec.releaseOutputBuffer(outIdx, shouldRender)
            if (shouldRender) {
                lastFrameDecodedMs.set(System.currentTimeMillis())
                onFirstOrResumedFrame()
                onFrameRendered()
                if (probeTimestampMs > 0L) {
                    val elapsed = (System.currentTimeMillis() - probeTimestampMs).coerceAtLeast(1L)
                    recordLatencySample(elapsed)
                }
            }
            outIdx = mediaCodec.dequeueOutputBuffer(info, 0L)
        }
    }

    private fun createAndStartCodec(
        useSoftware: Boolean,
        width: Int,
        height: Int,
        surface: Surface,
        csd0: ByteArray?
    ): Pair<MediaCodec, String> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (csd0 != null) {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }
        }
        val mediaCodec = if (useSoftware) {
            val swName = selectSoftwareAvcDecoderName() ?: FALLBACK_SW_DECODER
            MediaCodec.createByCodecName(swName)
        } else {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        val actualName = runCatching { mediaCodec.name }.getOrDefault(if (useSoftware) FALLBACK_SW_DECODER else "hardware")
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.start()
        return mediaCodec to actualName
    }

    private fun recordLatencySample(elapsedMs: Long) {
        synchronized(latencyWindow) {
            if (latencyWindow.size >= 25) {
                latencyWindow.removeFirst()
            }
            latencyWindow.addLast(elapsedMs)
            val sorted = latencyWindow.sorted()
            val median = sorted[sorted.size / 2]
            onLatencyStats(
                LatencyProbeStats(
                    lastTouchToFrameMs = elapsedMs,
                    medianTouchToFrameMs = median,
                    sampleCount = sorted.size
                )
            )
        }
    }

    companion object {
        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val FALLBACK_SW_DECODER = "OMX.google.h264.decoder"

        fun classifyAnnexBNalPacket(nalBytes: ByteArray): NalPacketInfo {
            if (nalBytes.size < 4) {
                return NalPacketInfo(emptyList(), isCodecConfig = false, isKeyFrame = false)
            }
            val nalTypes = mutableListOf<Int>()
            var idx = 0
            while (idx <= nalBytes.size - 4) {
                val startCodeLen = when {
                    nalBytes[idx].toInt() == 0 &&
                        nalBytes[idx + 1].toInt() == 0 &&
                        nalBytes[idx + 2].toInt() == 0 &&
                        nalBytes[idx + 3].toInt() == 1 -> 4
                    nalBytes[idx].toInt() == 0 &&
                        nalBytes[idx + 1].toInt() == 0 &&
                        nalBytes[idx + 2].toInt() == 1 -> 3
                    else -> 0
                }
                if (startCodeLen > 0) {
                    val headerIdx = idx + startCodeLen
                    if (headerIdx < nalBytes.size) {
                        nalTypes.add(nalBytes[headerIdx].toInt() and 0x1F)
                    }
                    idx += startCodeLen + 1
                } else {
                    idx++
                }
            }
            val isConfig = nalTypes.isNotEmpty() && nalTypes.all { it == NAL_SPS || it == NAL_PPS }
            val isIdr = nalTypes.any { it == NAL_IDR }
            return NalPacketInfo(
                nalTypes = nalTypes,
                isCodecConfig = isConfig,
                isKeyFrame = isIdr
            )
        }

        private fun combineSpsPps(sps: ByteArray?, pps: ByteArray?): ByteArray? {
            if (sps == null || pps == null) return null
            val combined = ByteArray(sps.size + pps.size)
            System.arraycopy(sps, 0, combined, 0, sps.size)
            System.arraycopy(pps, 0, combined, sps.size, pps.size)
            return combined
        }

        fun selectSoftwareAvcDecoderName(
            availableCodecs: List<Pair<String, Boolean>> = queryAvailableAvcDecoders()
        ): String? {
            return availableCodecs.firstOrNull { (name, isSoft) ->
                isSoft || name.startsWith("OMX.google.h264", ignoreCase = true) ||
                    name.startsWith("c2.android.avc", ignoreCase = true)
            }?.first
        }

        private fun queryAvailableAvcDecoders(): List<Pair<String, Boolean>> {
            return runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .filter { info ->
                        !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    }
                    .map { info ->
                        val isSoft = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            info.isSoftwareOnly
                        } else {
                            info.name.startsWith("OMX.google.", ignoreCase = true) ||
                                info.name.startsWith("c2.android.", ignoreCase = true)
                        }
                        info.name to isSoft
                    }
            }.getOrDefault(emptyList())
        }
    }
}
