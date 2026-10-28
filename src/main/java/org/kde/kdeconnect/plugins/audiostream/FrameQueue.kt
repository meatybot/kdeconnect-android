/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import java.io.InputStream
import java.util.ArrayDeque

/**
 * Blocking InputStream used as the endless payload of an outgoing stream. Captured frames are
 * queued encoded; if the network does not drain the queue, the oldest frames that have not
 * started being read are dropped so latency stays bounded. Thread safe: the capture thread
 * enqueues while the link's payload thread reads.
 */
class FrameQueue(private val maxQueuedBytes: Int, private val onClosed: () -> Unit = {}) : InputStream() {
    private val lock = Object()
    private val frames = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queuedBytes = 0
    private var nextSequence = 0L
    private var finishing = false
    private var closed = false

    var framesQueued: Long = 0
        private set
    var framesDropped: Long = 0
        private set

    /** Assigns the next sequence number; dropped frames leave a gap the receiver sees as loss */
    fun enqueue(timestampUs: Long, data: ByteArray) {
        synchronized(lock) {
            if (finishing || closed) return
            push(AudioFrame(nextSequence, timestampUs, false, data))
            nextSequence = (nextSequence + 1) and 0xFFFFFFFFL
        }
    }

    /** Queue a last frame; read() returns -1 once everything was read */
    fun finish() {
        synchronized(lock) {
            if (finishing || closed) return
            push(AudioFrame(nextSequence, 0, true, ByteArray(0)))
            finishing = true
        }
    }

    private fun push(frame: AudioFrame) {
        val encoded = FrameCodec.encode(frame)
        frames.addLast(encoded)
        queuedBytes += encoded.size
        framesQueued++
        // Drop whole frames from the front, but never the one that is partially read
        while (queuedBytes > maxQueuedBytes && frames.size > 1) {
            val iterator = frames.iterator()
            var victim = iterator.next()
            if (headOffset > 0) {
                if (frames.size <= 2) break
                victim = iterator.next()
            }
            iterator.remove()
            queuedBytes -= victim.size
            framesDropped++
        }
        lock.notifyAll()
    }

    override fun available(): Int = synchronized(lock) { queuedBytes - headOffset }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (frames.isEmpty()) {
                if (finishing || closed) return -1
                lock.wait()
            }
            var copied = 0
            while (copied < len && frames.isNotEmpty()) {
                val head = frames.first
                val chunk = minOf(len - copied, head.size - headOffset)
                System.arraycopy(head, headOffset, b, off + copied, chunk)
                copied += chunk
                headOffset += chunk
                if (headOffset == head.size) {
                    frames.removeFirst()
                    queuedBytes -= head.size
                    headOffset = 0
                }
            }
            return copied
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            frames.clear()
            queuedBytes = 0
            lock.notifyAll()
        }
        onClosed()
    }

    val isClosed: Boolean
        get() = synchronized(lock) { closed }
}
