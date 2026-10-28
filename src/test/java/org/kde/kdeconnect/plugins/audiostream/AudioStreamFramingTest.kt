/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Team <kdeconnect@kde.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.audiostream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AudioStreamFramingTest {
    private fun frame(sequence: Long, size: Int = 16, last: Boolean = false) =
        AudioFrame(sequence, 1000L * sequence, last, ByteArray(size) { ((sequence * 31 + it) and 0xFF).toByte() })

    private fun drain(decoder: FrameDecoder): List<AudioFrame> = generateSequence { decoder.next() }.toList()

    /** sequence of the popped frame, -1 for concealment, -2 for silence */
    private fun JitterBuffer.popSequence(): Long = when (val out = pop()) {
        is JitterBuffer.Output.Frame -> out.frame.sequence
        JitterBuffer.Output.Concealed -> -1
        JitterBuffer.Output.Silence -> -2
    }

    @Test
    fun wireFormatMatchesSpec() {
        val wire = FrameCodec.encode(AudioFrame(0x01020304, 0x1122334455667788, true, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())))
        assertEquals(FrameCodec.HEADER_SIZE + 3, wire.size)
        val expectedHeader = byteArrayOf(
            'K'.code.toByte(), 'A'.code.toByte(), 1, 1,
            1, 2, 3, 4,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(),
            0, 0, 0, 3,
        )
        assertArrayEquals(expectedHeader, wire.copyOf(FrameCodec.HEADER_SIZE))
    }

    @Test
    fun roundTripIncludingSequenceWrap() {
        val sent = (0 until 50).map { i -> frame((0xFFFFFFF0L + i) and 0xFFFFFFFFL, i * 37 % 4000, last = i == 49) }
        val decoder = FrameDecoder()
        sent.forEach { decoder.feed(FrameCodec.encode(it)) }
        val received = drain(decoder)
        assertEquals(sent.size, received.size)
        sent.zip(received).forEach { (a, b) ->
            assertEquals(a.sequence, b.sequence)
            assertEquals(a.timestampUs, b.timestampUs)
            assertEquals(a.last, b.last)
            assertArrayEquals(a.data, b.data)
        }
        assertEquals(0, decoder.pendingBytes)
        assertEquals(0, decoder.discardedBytes)
    }

    @Test
    fun splitAtEveryByte() {
        val wire = (0L until 3L).map { FrameCodec.encode(frame(it, 7)) }.reduce { a, b -> a + b }
        for (split in 0..wire.size) {
            val decoder = FrameDecoder()
            decoder.feed(wire, 0, split)
            val frames = drain(decoder).toMutableList()
            decoder.feed(wire, split, wire.size - split)
            frames += drain(decoder)
            assertEquals(listOf(0L, 1L, 2L), frames.map { it.sequence })
        }
    }

    @Test
    fun resyncAfterGarbageAndRejectsBadHeaders() {
        val garbage = "garbage KA".toByteArray()
        val oversized = FrameCodec.encode(frame(1))
        ByteBuffer.wrap(oversized).putInt(16, FrameCodec.MAX_DATA_SIZE + 1)
        val badVersion = FrameCodec.encode(frame(2)).also { it[2] = 2 }
        val decoder = FrameDecoder()
        decoder.feed(garbage + oversized + badVersion + FrameCodec.encode(frame(5)) + FrameCodec.encode(frame(6)))
        assertEquals(listOf(5L, 6L), drain(decoder).map { it.sequence })
        assertTrue(decoder.discardedBytes >= garbage.size)
    }

    @Test
    fun largeFramesGrowTheBuffer() {
        val big = frame(9, FrameCodec.MAX_DATA_SIZE)
        val decoder = FrameDecoder()
        val wire = FrameCodec.encode(big)
        // In small pieces to exercise compaction and growth
        var off = 0
        while (off < wire.size) {
            val n = minOf(1000, wire.size - off)
            decoder.feed(wire, off, n)
            off += n
        }
        val frames = drain(decoder)
        assertEquals(1, frames.size)
        assertArrayEquals(big.data, frames[0].data)
    }

    @Test
    fun jitterPrefillThenInOrder() {
        val jb = JitterBuffer(3, 10)
        assertEquals(-2, jb.popSequence())
        jb.push(frame(0))
        jb.push(frame(1))
        assertEquals(-2, jb.popSequence())
        jb.push(frame(2))
        assertEquals(0, jb.popSequence())
        assertEquals(JitterBuffer.State.PLAYING, jb.state)
        assertEquals(1, jb.popSequence())
        assertEquals(2, jb.popSequence())
    }

    @Test
    fun jitterReordersAndConceals() {
        val jb = JitterBuffer(4, 10)
        listOf(2L, 0L, 4L, 1L).forEach { jb.push(frame(it)) }
        assertEquals(listOf(0L, 1L, 2L, -1L, 4L), List(5) { jb.popSequence() })
        assertEquals(1, jb.stats.lost)
    }

    @Test
    fun jitterLateAndDuplicates() {
        val jb = JitterBuffer(1, 10)
        jb.push(frame(0))
        jb.push(frame(1))
        jb.push(frame(1))
        assertEquals(1, jb.stats.duplicates)
        assertEquals(0, jb.popSequence())
        assertEquals(1, jb.popSequence())
        jb.push(frame(0))
        assertEquals(1, jb.stats.late)
        assertEquals(0, jb.depth)
    }

    @Test
    fun jitterUnderrunRebuffers() {
        val jb = JitterBuffer(2, 10)
        jb.push(frame(0))
        jb.push(frame(1))
        assertEquals(0, jb.popSequence())
        assertEquals(1, jb.popSequence())
        assertEquals(-2, jb.popSequence())
        assertEquals(1, jb.stats.underruns)
        assertEquals(JitterBuffer.State.BUFFERING, jb.state)
        jb.push(frame(3))
        assertEquals(-2, jb.popSequence())
        jb.push(frame(4))
        assertEquals(3, jb.popSequence())
        assertEquals(1, jb.stats.lost) // frame 2 never came
    }

    @Test
    fun jitterOverflowTrimsToTarget() {
        val jb = JitterBuffer(3, 6)
        (0L until 7L).forEach { jb.push(frame(it)) }
        assertEquals(3, jb.depth)
        assertEquals(4, jb.stats.overflowDropped)
        assertEquals(4, jb.popSequence())
        (7L until 12L).forEach { jb.push(frame(it)) }
        assertEquals(3, jb.depth)
        assertEquals(listOf(9L, 10L, 11L), List(3) { jb.popSequence() })
        assertEquals(0, jb.stats.lost)
    }

    @Test
    fun jitterSequenceWrap() {
        val jb = JitterBuffer(2, 10)
        listOf(0xFFFFFFFFL, 0xFFFFFFFEL, 0L, 1L).forEach { jb.push(frame(it)) }
        assertEquals(listOf(0xFFFFFFFEL, 0xFFFFFFFFL, 0L, 1L), List(4) { jb.popSequence() })
        assertEquals(0, jb.stats.lost)
    }

    @Test
    fun jitterTargets() {
        assertEquals(5, JitterBuffer.targetFramesFor(100, 20))
        assertEquals(1, JitterBuffer.targetFramesFor(1, 20))
        assertEquals(15, JitterBuffer.maxFramesFor(100, 20))
    }

    @Test
    fun playbackBufferFillsGapsWithSilence() {
        val format = AudioStreamProtocol.StreamFormat(sampleRate = 1000, channels = 1, frameDurationMs = 10)
        val buffer = PlaybackBuffer(format, 20)
        assertArrayEquals(ByteArray(20), buffer.nextFrame())
        val a = frame(0, 20)
        val c = frame(2, 21) // odd size gets cut to whole samples
        buffer.push(a)
        buffer.push(c)
        assertArrayEquals(a.data, buffer.nextFrame())
        assertArrayEquals(ByteArray(20), buffer.nextFrame())
        assertArrayEquals(c.data.copyOf(20), buffer.nextFrame())
        assertEquals(1, buffer.stats().lost)
    }

    @Test
    fun frameQueueDropsOldestWholeFrames() {
        val frameWire = FrameCodec.HEADER_SIZE + 10
        val queue = FrameQueue(frameWire * 3)
        repeat(5) { i -> queue.enqueue(i.toLong(), ByteArray(10) { ('a' + i).code.toByte() }) }
        assertEquals(5, queue.framesQueued)
        assertEquals(2, queue.framesDropped)
        assertEquals(frameWire * 3, queue.available())

        val decoder = FrameDecoder()
        val half = ByteArray(frameWire / 2)
        assertEquals(half.size, queue.read(half, 0, half.size))
        decoder.feed(half)
        for (i in 5 until 9) queue.enqueue(i.toLong(), ByteArray(10) { ('a' + i).code.toByte() })
        val rest = ByteArray(queue.available())
        assertEquals(rest.size, queue.read(rest, 0, rest.size))
        decoder.feed(rest)
        val frames = drain(decoder)
        assertEquals(2L, frames.first().sequence)
        assertArrayEquals(ByteArray(10) { 'c'.code.toByte() }, frames.first().data)
        assertEquals(8L, frames.last().sequence)
        assertEquals(0, decoder.discardedBytes)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
    }

    @Test
    fun frameQueueFinishEndsTheStream() {
        var closed = false
        val queue = FrameQueue(1 shl 20) { closed = true }
        queue.enqueue(1, ByteArray(4))
        queue.finish()
        queue.enqueue(2, ByteArray(4)) // ignored
        val decoder = FrameDecoder()
        val buf = ByteArray(1024)
        while (true) {
            val n = queue.read(buf, 0, buf.size)
            if (n < 0) break
            decoder.feed(buf, 0, n)
        }
        val frames = drain(decoder)
        assertEquals(2, frames.size)
        assertFalse(frames[0].last)
        assertTrue(frames[1].last)
        assertEquals(1L, frames[1].sequence)
        assertFalse(closed)
        queue.close()
        assertTrue(closed)
        assertNull(decoder.next())
    }

    @Test
    fun frameQueueReadBlocksUntilDataOrClose() {
        val queue = FrameQueue(1 shl 20)
        var result = 0
        val reader = Thread { result = queue.read(ByteArray(100), 0, 100) }
        reader.start()
        Thread.sleep(100)
        assertTrue(reader.isAlive)
        queue.enqueue(1, ByteArray(4))
        reader.join(2000)
        assertEquals(FrameCodec.HEADER_SIZE + 4, result)

        val blocked = Thread { result = queue.read(ByteArray(100), 0, 100) }
        blocked.start()
        Thread.sleep(100)
        queue.close()
        blocked.join(2000)
        assertFalse(blocked.isAlive)
        assertEquals(-1, result)
    }
}
