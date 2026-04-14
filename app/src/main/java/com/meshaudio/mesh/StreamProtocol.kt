package com.meshaudio.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

object StreamProtocol {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 1
    const val DEFAULT_PORT = 7878
    val MAGIC = byteArrayOf(0x4D, 0x41, 0x55, 0x31) // MAU1

    const val HEADER_SIZE = 12

    fun header(seq: Int, payloadBytes: Int): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(seq)
        buf.putInt(payloadBytes)
        return buf.array()
    }

    fun parseHeader(data: ByteArray, offset: Int = 0): Pair<Int, Int>? {
        if (offset + HEADER_SIZE > data.size) return null
        if (data[offset] != MAGIC[0] || data[offset + 1] != MAGIC[1] ||
            data[offset + 2] != MAGIC[2] || data[offset + 3] != MAGIC[3]
        ) {
            return null
        }
        val bb = ByteBuffer.wrap(data, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        val seq = bb.int
        val len = bb.int
        return seq to len
    }
}
