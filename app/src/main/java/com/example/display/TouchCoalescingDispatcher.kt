package com.example.display

import android.os.Process
import com.example.protocol.ControlCodec
import com.example.protocol.ControlMessage
import com.example.protocol.TouchSample
import com.example.protocol.TouchSamplePool
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class TouchCoalescingDispatcher {

    private val pool = TouchSamplePool(48)
    private val sendBuffer = ByteBuffer.allocate(TouchSample.PACKET_BYTES)
    private val immediateQueue = ArrayDeque<TouchSample>(32)
    private var pendingMoveSample: TouchSample? = null

    private val running = AtomicBoolean(false)
    private val transitioning = AtomicBoolean(false)
    private var writerThread: Thread? = null
    private var outStream: DataOutputStream? = null
    private var sessionStartMs: Long = 0L
    private var lastProbeSentMs: Long = 0L

    fun start(controlOut: DataOutputStream) {
        stop()
        outStream = controlOut
        sessionStartMs = System.currentTimeMillis()
        running.set(true)
        writerThread = Thread({ writerLoop() }, "ExtDesk-TouchCoalescer").apply {
            start()
        }
    }

    fun setTransitionPaused(paused: Boolean) {
        transitioning.set(paused)
    }

    fun enqueueTouch(action: Byte, pointerId: Short, normX: Float, normY: Float, scrollDelta: Float = 0f) {
        if (!running.get()) return
        val sample = pool.acquire()
        sample.packetType = TouchSample.PACKET_TYPE_TOUCH
        sample.action = action
        sample.pointerId = pointerId
        sample.normX = normX.coerceIn(0f, 1f)
        sample.normY = normY.coerceIn(0f, 1f)
        sample.scrollDelta = scrollDelta
        sample.displayTimestampOffsetMs = (System.currentTimeMillis() - sessionStartMs).toInt()

        synchronized(this) {
            if (action == TouchSample.ACTION_MOVE) {
                pendingMoveSample?.let { pool.release(it) }
                pendingMoveSample = sample
            } else {
                if (immediateQueue.size >= MAX_BOUNDED_QUEUE) {
                    val dropped = immediateQueue.removeFirst()
                    pool.release(dropped)
                }
                immediateQueue.addLast(sample)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            writerThread?.join(300)
        } catch (_: Throwable) {
        }
        writerThread = null
        outStream = null
        synchronized(this) {
            pendingMoveSample?.let { pool.release(it) }
            pendingMoveSample = null
            while (immediateQueue.isNotEmpty()) {
                pool.release(immediateQueue.removeFirst())
            }
        }
    }

    private fun writerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
        }

        while (running.get()) {
            val out = outStream ?: break
            var dispatched = false

            while (running.get()) {
                val nextImmediate = synchronized(this) {
                    if (immediateQueue.isEmpty()) null else immediateQueue.removeFirst()
                } ?: break

                try {
                    val now = System.currentTimeMillis()
                    if (nextImmediate.action == TouchSample.ACTION_DOWN || now - lastProbeSentMs >= 200L) {
                        lastProbeSentMs = now
                        ControlCodec.writeJsonFrame(out, ControlMessage.LatencyProbe(now))
                    }
                    ControlCodec.writeBinaryTouchFrame(out, sendBuffer, nextImmediate)
                    dispatched = true
                } catch (_: Throwable) {
                } finally {
                    pool.release(nextImmediate)
                }
            }

            if (!transitioning.get()) {
                val move = synchronized(this) {
                    val m = pendingMoveSample
                    pendingMoveSample = null
                    m
                }
                if (move != null) {
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastProbeSentMs >= 250L) {
                            lastProbeSentMs = now
                            ControlCodec.writeJsonFrame(out, ControlMessage.LatencyProbe(now))
                        }
                        ControlCodec.writeBinaryTouchFrame(out, sendBuffer, move)
                        dispatched = true
                    } catch (_: Throwable) {
                    } finally {
                        pool.release(move)
                    }
                }
            }

            try {
                Thread.sleep(if (dispatched) COALESCE_INTERVAL_MS else IDLE_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    companion object {
        private const val COALESCE_INTERVAL_MS = 16L // ~60 Hz
        private const val IDLE_POLL_MS = 8L
        private const val MAX_BOUNDED_QUEUE = 24
    }
}
