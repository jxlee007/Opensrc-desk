package com.example.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.example.model.LatencyProbeStats
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class H264StreamDecoder(
    private val onFirstOrResumedFrame: () -> Unit,
    private val onLatencyStats: (LatencyProbeStats) -> Unit,
    private val onStreamMetrics: (fps: Int) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var videoSocket: Socket? = null
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    val lastFrameDecodedMs = AtomicLong(0L)
    private val latencyWindow = ArrayDeque<Long>(32)

    fun start(
        hostIp: String,
        videoPort: Int,
        width: Int,
        height: Int,
        outputSurface: Surface
    ) {
        stop()
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
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    private fun runDecodeLoop(
        hostIp: String,
        videoPort: Int,
        width: Int,
        height: Int,
        outputSurface: Surface
    ) {
        try {
            val sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(hostIp, videoPort), 5000)
            }
            videoSocket = sock
            val inp = DataInputStream(sock.getInputStream())

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec.configure(format, outputSurface, null, 0)
            mediaCodec.start()
            codec = mediaCodec

            val info = MediaCodec.BufferInfo()
            var frameCount = 0
            var fpsWindowStart = System.currentTimeMillis()

            while (running.get()) {
                val nalLen = inp.readInt()
                val probeTimestampMs = inp.readLong()
                if (nalLen <= 0 || nalLen > 4 * 1024 * 1024) break
                val nalBytes = ByteArray(nalLen)
                inp.readFully(nalBytes)

                val inIdx = mediaCodec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = mediaCodec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(nalBytes)
                        mediaCodec.queueInputBuffer(inIdx, 0, nalLen, System.nanoTime() / 1000L, 0)
                    }
                }

                var outIdx = mediaCodec.dequeueOutputBuffer(info, 5_000L)
                while (outIdx >= 0) {
                    mediaCodec.releaseOutputBuffer(outIdx, true)
                    lastFrameDecodedMs.set(System.currentTimeMillis())
                    onFirstOrResumedFrame()
                    frameCount++

                    if (probeTimestampMs > 0L) {
                        val elapsed = (System.currentTimeMillis() - probeTimestampMs).coerceAtLeast(1L)
                        recordLatencySample(elapsed)
                    }
                    outIdx = mediaCodec.dequeueOutputBuffer(info, 0L)
                }

                val now = System.currentTimeMillis()
                if (now - fpsWindowStart >= 1000L) {
                    onStreamMetrics(frameCount)
                    frameCount = 0
                    fpsWindowStart = now
                }
            }
        } catch (_: Throwable) {
            // Surface retains last decoded frame cleanly when stream stops or switches
        }
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
}
