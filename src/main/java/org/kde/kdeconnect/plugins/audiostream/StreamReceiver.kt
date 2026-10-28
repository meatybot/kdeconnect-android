/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import android.os.SystemClock
import java.io.IOException
import java.io.InputStream

/** Jitter buffer plus silence filling, shared by the network reader and the audio output thread */
class PlaybackBuffer(private val format: AudioStreamProtocol.StreamFormat, latencyMs: Int) {
    private val jitter = JitterBuffer(
        JitterBuffer.targetFramesFor(latencyMs, format.frameDurationMs),
        JitterBuffer.maxFramesFor(latencyMs, format.frameDurationMs),
    )
    private val sampleBytes = format.channels * format.bytesPerSample

    @Synchronized
    fun push(frame: AudioFrame) = jitter.push(frame)

    /** Exactly one frame of PCM: the next frame, or silence when buffering or concealing a loss */
    @Synchronized
    fun nextFrame(): ByteArray {
        return when (val out = jitter.pop()) {
            is JitterBuffer.Output.Frame -> {
                val data = out.frame.data
                if (data.size % sampleBytes == 0) data else data.copyOf(data.size - data.size % sampleBytes)
            }
            else -> ByteArray(format.frameBytes)
        }
    }

    @Synchronized
    fun stats(): JitterBuffer.Stats = jitter.stats.copy()

    @get:Synchronized
    val depth: Int
        get() = jitter.depth

    @get:Synchronized
    val state: JitterBuffer.State
        get() = jitter.state
}

/**
 * Reads the endless payload of an incoming stream on its own thread, decodes frames and feeds
 * them to [buffer]. [onEnd] is called once the stream ended: last frame, or the connection
 * closing (which also covers stop()).
 */
class StreamReceiver(
    private val input: InputStream,
    private val format: AudioStreamProtocol.StreamFormat,
    private val buffer: PlaybackBuffer,
    private val onEnd: () -> Unit,
    private val clockUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1000 },
    /** Closes the connection; for a socket payload closing the stream alone is not enough */
    private val close: () -> Unit = { input.close() },
) {
    private val decoder = FrameDecoder()
    private val sampleBytes = format.channels * format.bytesPerSample
    private var thread: Thread? = null

    @Volatile
    private var stopped = false
    private var minDelayUs = Long.MAX_VALUE

    @Volatile
    var framesDecoded: Long = 0
        private set
    @Volatile
    var framesInvalid: Long = 0
        private set
    @Volatile
    var jitterMs: Double = 0.0
        private set

    fun start() {
        thread = Thread({ run() }, "AudioStreamReceiver").also { it.start() }
    }

    /** Close the input; the reader thread ends and reports a normal end */
    fun stop() {
        stopped = true
        try {
            close()
        } catch (_: IOException) {
        }
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    private fun run() {
        val chunk = ByteArray(16 * 1024)
        try {
            loop@ while (!stopped) {
                val n = input.read(chunk)
                if (n < 0) break
                decoder.feed(chunk, 0, n)
                while (true) {
                    val frame = decoder.next() ?: break
                    if (frame.last) break@loop
                    if (frame.data.size % sampleBytes != 0) {
                        framesInvalid++
                        continue
                    }
                    framesDecoded++
                    // The clock offset between the devices is unknown: track delay relative to the best seen
                    val delay = clockUs() - frame.timestampUs
                    minDelayUs = minOf(minDelayUs, delay)
                    jitterMs = (delay - minDelayUs) / 1000.0
                    buffer.push(frame)
                }
            }
        } catch (_: IOException) {
            // The connection going away (or stop() closing it) ends the stream like EOF does
        } finally {
            try {
                close()
            } catch (_: IOException) {
            }
        }
        onEnd()
    }
}
