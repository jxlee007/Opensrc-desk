package com.example.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed 20-byte binary touch frame:
 * [0]     packetType (1B)
 * [1]     action (1B)
 * [2..3]  pointerId (2B Short)
 * [4..7]  normX (4B Float)
 * [8..11] normY (4B Float)
 * [12..15] scrollDelta (4B Float)
 * [16..19] displayTimestampOffsetMs (4B Int)
 * Total = 1 + 1 + 2 + 4 + 4 + 4 + 4 = 20 bytes
 */
data class TouchSample(
    var packetType: Byte = PACKET_TYPE_TOUCH,
    var action: Byte = ACTION_MOVE,
    var pointerId: Short = 0,
    var normX: Float = 0f,
    var normY: Float = 0f,
    var scrollDelta: Float = 0f,
    var displayTimestampOffsetMs: Int = 0
) {
    fun copyFrom(other: TouchSample) {
        packetType = other.packetType
        action = other.action
        pointerId = other.pointerId
        normX = other.normX
        normY = other.normY
        scrollDelta = other.scrollDelta
        displayTimestampOffsetMs = other.displayTimestampOffsetMs
    }

    fun writeToBuffer(buffer: ByteBuffer) {
        buffer.clear()
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(packetType)
        buffer.put(action)
        buffer.putShort(pointerId)
        buffer.putFloat(normX)
        buffer.putFloat(normY)
        buffer.putFloat(scrollDelta)
        buffer.putInt(displayTimestampOffsetMs)
        buffer.flip()
    }

    companion object {
        const val PACKET_BYTES = 20
        const val PACKET_TYPE_TOUCH: Byte = 0x10
        const val PACKET_TYPE_TRACKPAD_DELTA: Byte = 0x11

        const val ACTION_DOWN: Byte = 0
        const val ACTION_MOVE: Byte = 1
        const val ACTION_UP: Byte = 2
        const val ACTION_SCROLL: Byte = 3
        const val ACTION_RIGHT_CLICK: Byte = 4

        fun readFromBytes(bytes: ByteArray, out: TouchSample): Boolean {
            if (bytes.size != PACKET_BYTES) return false
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            out.packetType = buf.get()
            out.action = buf.get()
            out.pointerId = buf.short
            out.normX = buf.float
            out.normY = buf.float
            out.scrollDelta = buf.float
            out.displayTimestampOffsetMs = buf.int
            return true
        }
    }
}

/**
 * Pre-allocated zero-GC object pool for touch samples on constrained 1 GB RAM tablets.
 */
class TouchSamplePool(capacity: Int = 32) {
    private val pool = ArrayDeque<TouchSample>(capacity)

    init {
        repeat(capacity) { pool.addLast(TouchSample()) }
    }

    @Synchronized
    fun acquire(): TouchSample {
        return if (pool.isEmpty()) TouchSample() else pool.removeLast()
    }

    @Synchronized
    fun release(sample: TouchSample) {
        if (pool.size < 64) {
            pool.addLast(sample)
        }
    }
}
