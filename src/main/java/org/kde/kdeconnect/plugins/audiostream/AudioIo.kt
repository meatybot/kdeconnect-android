/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Team <kdeconnect@kde.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.audiostream

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.StreamFormat

private const val LOG_TAG = "AudioStream"

object AudioRecordFactory {
    /**
     * media/system: playback capture through [projection] (API 29+). microphone: MIC.
     * call: VOICE_COMMUNICATION, the local microphone only, see the spec's limitations.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun create(source: String, format: StreamFormat, projection: MediaProjection?): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRate,
            if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, format.frameBytes * 4)
        val builder = AudioRecord.Builder().setAudioFormat(audioFormat).setBufferSizeInBytes(bufferSize)
        when (source) {
            AudioStreamProtocol.SOURCE_MEDIA, AudioStreamProtocol.SOURCE_SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) {
                    throw IllegalStateException("Playback capture needs Android 10 and a MediaProjection")
                }
                setPlaybackCapture(builder, source, projection)
            }
            AudioStreamProtocol.SOURCE_CALL -> builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            else -> builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord could not be initialized for $source")
        }
        return record
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPlaybackCapture(builder: AudioRecord.Builder, source: String, projection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        if (source == AudioStreamProtocol.SOURCE_SYSTEM) {
            // The only other usages Android lets apps capture
            config.addMatchingUsage(AudioAttributes.USAGE_GAME)
            config.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        }
        builder.setAudioPlaybackCaptureConfig(config.build())
    }
}

/** Reads frames from [record] on its own thread into [queue] */
class AudioCaptureThread(
    private val record: AudioRecord,
    private val format: StreamFormat,
    private val queue: FrameQueue,
    private val projection: MediaProjection?,
    private val onError: () -> Unit,
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "AudioStreamCapture").also { it.start() }
    }

    /** Does not wait: the thread releases the recorder after its current read (at most one frame) */
    fun stop() {
        running = false
    }

    private fun run() {
        val frameBytes = format.frameBytes
        val frameUs = format.frameDurationMs * 1000L
        var failed = false
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(LOG_TAG, "Recording did not start")
                failed = true
            }
            while (running && !failed) {
                val pcm = ByteArray(frameBytes)
                var read = 0
                while (read < frameBytes && running) {
                    val n = record.read(pcm, read, frameBytes - read, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        Log.w(LOG_TAG, "AudioRecord.read failed: $n")
                        failed = true
                        break
                    }
                    read += n
                }
                if (read == frameBytes) {
                    // read() returns once the last sample of the frame was recorded
                    queue.enqueue(SystemClock.elapsedRealtimeNanos() / 1000 - frameUs, pcm)
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(LOG_TAG, "Recording failed", e)
            failed = true
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            projection?.stop()
        }
        if (failed && running) {
            onError()
        }
    }
}

/** Plays [buffer] through an AudioTrack; the blocking write paces the jitter buffer */
class AudioPlayer(private val format: StreamFormat, private val buffer: PlaybackBuffer, source: String) {
    private val track: AudioTrack
    @Volatile
    private var running = false
    private var thread: Thread? = null

    init {
        val speech = source == AudioStreamProtocol.SOURCE_CALL || source == AudioStreamProtocol.SOURCE_MICROPHONE
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // Small device buffer: the jitter buffer is where latency is managed
            .setBufferSizeInBytes(maxOf(minBuffer, format.frameBytes * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack could not be initialized")
        }
    }

    fun start() {
        running = true
        thread = Thread({ run() }, "AudioStreamPlayback").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
    }

    private fun run() {
        try {
            track.play()
            while (running) {
                val pcm = buffer.nextFrame()
                if (track.write(pcm, 0, pcm.size) < 0) {
                    Log.w(LOG_TAG, "AudioTrack.write failed")
                    break
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(LOG_TAG, "Playback failed", e)
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
    }
}
