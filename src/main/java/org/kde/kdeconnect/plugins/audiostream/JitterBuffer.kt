/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import java.util.TreeMap

/**
 * Reorders frames by sequence number and paces them out at the rate the audio output asks for.
 * See docs/specs/audiostream.md "Jitter buffer and latency target". Not thread safe.
 */
class JitterBuffer(targetFrames: Int, maxFrames: Int) {
    enum class State { BUFFERING, PLAYING }

    sealed class Output {
        class Frame(val frame: AudioFrame) : Output()
        /** The next frame is missing: play silence in its place */
        data object Concealed : Output()
        /** Nothing to play yet (buffering) */
        data object Silence : Output()
    }

    data class Stats(
        var received: Long = 0,
        var played: Long = 0,
        var lost: Long = 0,
        var late: Long = 0,
        var duplicates: Long = 0,
        var overflowDropped: Long = 0,
        var underruns: Long = 0,
    )

    val target: Int = maxOf(1, targetFrames)
    private val max: Int = maxOf(target + 1, maxFrames)
    private val frames = TreeMap<Long, AudioFrame>()
    private var next = -1L
    private var highest = -1L

    var state = State.BUFFERING
        private set
    val stats = Stats()
    val depth: Int
        get() = frames.size

    private fun unwrap(sequence: Long): Long {
        if (highest < 0) {
            highest = sequence
            return sequence
        }
        // Signed 32 bit distance to the newest frame we know of, handles the wrap-around
        val delta = (sequence - (highest and 0xFFFFFFFFL)).toInt()
        val unwrapped = highest + delta
        if (unwrapped > highest) {
            highest = unwrapped
        }
        return unwrapped
    }

    fun push(frame: AudioFrame) {
        val key = unwrap(frame.sequence)
        if (next >= 0 && key < next) {
            stats.late++
            return
        }
        if (frames.containsKey(key)) {
            stats.duplicates++
            return
        }
        frames[key] = frame
        stats.received++

        if (frames.size > max) {
            while (frames.size > target) {
                frames.pollFirstEntry()
                stats.overflowDropped++
            }
            if (state == State.PLAYING) {
                next = frames.firstKey()
            }
        }
    }

    fun pop(): Output {
        if (state == State.BUFFERING) {
            if (frames.size < target) {
                return Output.Silence
            }
            val first = frames.firstKey()
            if (next in 0 until first) {
                // Frames that never arrived while we were rebuffering
                stats.lost += first - next
            }
            next = first
            state = State.PLAYING
        }

        if (frames.isEmpty()) {
            stats.underruns++
            state = State.BUFFERING
            return Output.Silence
        }

        val firstKey = frames.firstKey()
        val out = if (firstKey == next) {
            stats.played++
            Output.Frame(frames.pollFirstEntry()!!.value)
        } else {
            stats.lost++
            Output.Concealed
        }
        next++
        return out
    }

    companion object {
        private const val OVERFLOW_HEADROOM_MS = 200

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

        fun targetFramesFor(latencyMs: Int, frameDurationMs: Int): Int = maxOf(1, ceilDiv(latencyMs, frameDurationMs))

        fun maxFramesFor(latencyMs: Int, frameDurationMs: Int): Int =
            targetFramesFor(latencyMs, frameDurationMs) + ceilDiv(OVERFLOW_HEADROOM_MS, frameDurationMs)
    }
}
