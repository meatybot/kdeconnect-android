/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Team <kdeconnect@kde.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.audiostream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame of the audiostream payload, see docs/specs/audiostream.md "Framing".
 * [sequence] is an unsigned 32 bit value stored in a Long.
 */
class AudioFrame(
    val sequence: Long,
    val timestampUs: Long,
    val last: Boolean,
    val data: ByteArray,
)

object FrameCodec {
    const val HEADER_SIZE = 20
    const val MAX_DATA_SIZE = 65536
    const val VERSION: Byte = 1
    const val FLAG_LAST = 0x01
    private const val MAGIC0 = 'K'.code.toByte()
    private const val MAGIC1 = 'A'.code.toByte()

    fun encode(frame: AudioFrame): ByteArray {
        require(frame.data.size <= MAX_DATA_SIZE)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.data.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC0)
        buffer.put(MAGIC1)
        buffer.put(VERSION)
        buffer.put(if (frame.last) FLAG_LAST.toByte() else 0)
        buffer.putInt((frame.sequence and 0xFFFFFFFFL).toInt())
        buffer.putLong(frame.timestampUs)
        buffer.putInt(frame.data.size)
        buffer.put(frame.data)
        return buffer.array()
    }

    internal fun isValidHeader(b: ByteArray, off: Int): Boolean {
        if (b[off] != MAGIC0 || b[off + 1] != MAGIC1 || b[off + 2] != VERSION) {
            return false
        }
        val length = readU32(b, off + 16)
        return length <= MAX_DATA_SIZE
    }

    internal fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    internal fun readU64(b: ByteArray, off: Int): Long = (readU32(b, off) shl 32) or readU32(b, off + 4)
}

/**
 * Incremental decoder: feed whatever arrives from the payload and pull complete frames.
 * Garbage (bad magic, unknown version, oversized length) is skipped byte by byte until a valid
 * header is found again. Not thread safe.
 */
class FrameDecoder {
    private var buffer = ByteArray(8192)
    private var start = 0
    private var end = 0

    var discardedBytes: Long = 0
        private set

    val pendingBytes: Int
        get() = end - start

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (end + length > buffer.size) {
            val pending = end - start
            if (pending + length > buffer.size) {
                val grown = ByteArray(maxOf(buffer.size * 2, pending + length))
                System.arraycopy(buffer, start, grown, 0, pending)
                buffer = grown
            } else {
                System.arraycopy(buffer, start, buffer, 0, pending)
            }
            start = 0
            end = pending
        }
        System.arraycopy(bytes, offset, buffer, end, length)
        end += length
    }

    fun next(): AudioFrame? {
        while (end - start >= FrameCodec.HEADER_SIZE) {
            if (!FrameCodec.isValidHeader(buffer, start)) {
                start++
                discardedBytes++
                continue
            }
            val length = FrameCodec.readU32(buffer, start + 16).toInt()
            if (end - start < FrameCodec.HEADER_SIZE + length) {
                return null
            }
            val last = (buffer[start + 3].toInt() and FrameCodec.FLAG_LAST) != 0
            val sequence = FrameCodec.readU32(buffer, start + 4)
            val timestamp = FrameCodec.readU64(buffer, start + 8)
            val data = buffer.copyOfRange(start + FrameCodec.HEADER_SIZE, start + FrameCodec.HEADER_SIZE + length)
            start += FrameCodec.HEADER_SIZE + length
            if (start == end) {
                start = 0
                end = 0
            }
            return AudioFrame(sequence, timestamp, last, data)
        }
        return null
    }
}
