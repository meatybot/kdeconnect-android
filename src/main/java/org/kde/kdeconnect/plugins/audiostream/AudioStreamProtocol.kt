/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.kde.kdeconnect.NetworkPacket
import java.util.UUID

/** Packet building, parsing and negotiation, see docs/specs/audiostream.md */
object AudioStreamProtocol {
    const val PACKET_TYPE_AUDIOSTREAM = "kdeconnect.audiostream"
    const val PACKET_TYPE_AUDIOSTREAM_REQUEST = "kdeconnect.audiostream.request"

    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val ACTION_CAPABILITIES = "capabilities"

    const val SOURCE_MEDIA = "media"
    const val SOURCE_SYSTEM = "system"
    const val SOURCE_CALL = "call"
    const val SOURCE_MICROPHONE = "microphone"
    val ALL_SOURCES = listOf(SOURCE_MEDIA, SOURCE_SYSTEM, SOURCE_CALL, SOURCE_MICROPHONE)

    const val CODEC_PCM_S16LE = "pcm_s16le"

    const val ERROR_UNSUPPORTED_SOURCE = "unsupported_source"
    const val ERROR_UNSUPPORTED_FORMAT = "unsupported_format"
    const val ERROR_PERMISSION_DENIED = "permission_denied"
    const val ERROR_BUSY = "busy"
    const val ERROR_CAPTURE_FAILED = "capture_failed"
    const val ERROR_PLAYBACK_FAILED = "playback_failed"
    const val ERROR_TIMEOUT = "timeout"
    const val ERROR_INVALID_REQUEST = "invalid_request"
    /** The system mutes the capture, for example during a phone call */
    const val ERROR_SILENCED = "silenced"

    const val CALL_AUDIO_LOCAL_MICROPHONE_ONLY = "local_microphone_only"

    const val PROTOCOL_VERSION = 1
    const val DEFAULT_SAMPLE_RATE = 48000
    const val DEFAULT_FRAME_DURATION_MS = 20
    const val MAX_STREAMS = 4

    private val STREAM_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,64}$")

    data class StreamFormat(
        val codec: String = CODEC_PCM_S16LE,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val channels: Int = 2,
        val frameDurationMs: Int = DEFAULT_FRAME_DURATION_MS,
    ) {
        val bytesPerSample: Int get() = 2
        val frameBytes: Int get() = sampleRate * frameDurationMs / 1000 * channels * bytesPerSample
    }

    data class Message(
        val action: String,
        val streamId: String = "",
        val source: String = "",
        val format: StreamFormat = StreamFormat(),
        /** stop only, empty for a normal stop */
        val error: String = "",
        val callAudio: String = "",
    )

    data class Capabilities(
        val sources: List<String>,
        val codecs: List<String> = listOf(CODEC_PCM_S16LE),
        val sampleRates: List<Int> = listOf(DEFAULT_SAMPLE_RATE),
        val canPlay: Boolean = true,
        val maxStreams: Int = MAX_STREAMS,
    )

    sealed class ParseResult {
        data class Ok(val message: Message) : ParseResult()
        /** [message] holds what could be read, enough to answer with a stop when it has a stream id */
        data class Invalid(val error: String, val message: Message) : ParseResult()
    }

    fun isValidStreamId(streamId: String): Boolean = STREAM_ID_REGEX.matches(streamId)

    fun newStreamId(): String = UUID.randomUUID().toString()

    /** The packet body as JSON, to tell numbers from strings (NetworkPacket hides that) */
    private fun bodyOf(np: NetworkPacket): JsonObject =
        Json.parseToJsonElement(np.serialize()).jsonObject["body"]?.jsonObject ?: JsonObject(emptyMap())

    /** Integral JSON number in [min, max]; [fallback] when absent; null when present but invalid */
    private fun readRange(body: JsonObject, key: String, min: Int, max: Int, fallback: Int): Int? {
        val element = body[key] ?: return fallback
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.longOrNull ?: primitive.doubleOrNull?.let { d ->
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong() else null
        } ?: return null
        return if (value in min..max) value.toInt() else null
    }

    fun parse(np: NetworkPacket): ParseResult {
        val action = np.getString("action")
        val partial = Message(
            action = action,
            streamId = np.getString("streamId"),
            source = np.getString("source"),
            error = np.getString("error"),
            callAudio = np.getString("callAudio"),
        )
        if (action != ACTION_START && action != ACTION_STOP && action != ACTION_CAPABILITIES) {
            return ParseResult.Invalid(ERROR_INVALID_REQUEST, partial)
        }
        if (action == ACTION_CAPABILITIES) {
            return ParseResult.Ok(partial)
        }
        if (!isValidStreamId(partial.streamId)) {
            return ParseResult.Invalid(ERROR_INVALID_REQUEST, partial)
        }
        if (action == ACTION_STOP) {
            return ParseResult.Ok(partial)
        }
        if (partial.source !in ALL_SOURCES || !np.has("codec") || !np.has("sampleRate") || !np.has("channels")) {
            return ParseResult.Invalid(ERROR_INVALID_REQUEST, partial)
        }
        val body = bodyOf(np)
        val sampleRate = readRange(body, "sampleRate", 8000, 96000, DEFAULT_SAMPLE_RATE)
        val channels = readRange(body, "channels", 1, 2, 2)
        val frameDuration = readRange(body, "frameDurationMs", 5, 100, DEFAULT_FRAME_DURATION_MS)
        if (sampleRate == null || channels == null || frameDuration == null) {
            return ParseResult.Invalid(ERROR_INVALID_REQUEST, partial)
        }
        val message = partial.copy(format = StreamFormat(np.getString("codec"), sampleRate, channels, frameDuration))
        if (message.format.codec != CODEC_PCM_S16LE) {
            return ParseResult.Invalid(ERROR_UNSUPPORTED_FORMAT, message)
        }
        return ParseResult.Ok(message)
    }

    fun parseCapabilities(np: NetworkPacket): Capabilities {
        val sources = np.getStringList("sources", emptyList()).filter { it in ALL_SOURCES }.distinct()
        val codecs = np.getStringList("codecs") ?: listOf(CODEC_PCM_S16LE)
        val rates = np.getJsonArray("sampleRates")?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            ?: listOf(DEFAULT_SAMPLE_RATE)
        val maxStreams = readRange(bodyOf(np), "maxStreams", 0, 64, MAX_STREAMS) ?: MAX_STREAMS
        return Capabilities(sources, codecs, rates, np.getBoolean("canPlay", true), maxStreams)
    }

    fun makeStartPacket(streamId: String, source: String, format: StreamFormat, callAudio: String? = null): NetworkPacket =
        NetworkPacket(PACKET_TYPE_AUDIOSTREAM).apply {
            this["action"] = ACTION_START
            this["streamId"] = streamId
            this["source"] = source
            this["codec"] = format.codec
            this["sampleRate"] = format.sampleRate
            this["channels"] = format.channels
            this["frameDurationMs"] = format.frameDurationMs
            if (!callAudio.isNullOrEmpty()) {
                this["callAudio"] = callAudio
            }
        }

    fun makeStopPacket(type: String, streamId: String, source: String? = null, error: String? = null): NetworkPacket =
        NetworkPacket(type).apply {
            this["action"] = ACTION_STOP
            this["streamId"] = streamId
            if (!source.isNullOrEmpty()) this["source"] = source
            if (!error.isNullOrEmpty()) this["error"] = error
        }

    fun makeStartRequest(streamId: String, source: String, preferred: StreamFormat): NetworkPacket =
        NetworkPacket(PACKET_TYPE_AUDIOSTREAM_REQUEST).apply {
            this["action"] = ACTION_START
            this["streamId"] = streamId
            this["source"] = source
            this["codec"] = preferred.codec
            this["sampleRate"] = preferred.sampleRate
            this["channels"] = preferred.channels
        }

    fun makeCapabilitiesRequest(): NetworkPacket =
        NetworkPacket(PACKET_TYPE_AUDIOSTREAM_REQUEST).apply { this["action"] = ACTION_CAPABILITIES }

    fun makeCapabilitiesPacket(caps: Capabilities): NetworkPacket =
        NetworkPacket(PACKET_TYPE_AUDIOSTREAM).apply {
            this["action"] = ACTION_CAPABILITIES
            this["protocolVersion"] = PROTOCOL_VERSION
            this["sources"] = caps.sources
            this["codecs"] = caps.codecs
            this["sampleRates"] = JsonArray(caps.sampleRates.map { JsonPrimitive(it) })
            this["canPlay"] = caps.canPlay
            this["maxStreams"] = caps.maxStreams
        }

    /** Channels a source naturally has: stereo for playback capture, mono for microphones */
    fun sourceChannels(source: String): Int = if (source == SOURCE_MEDIA || source == SOURCE_SYSTEM) 2 else 1

    /** Format the sender will use for a request: the requested one if we support it, else our default */
    fun negotiateFormat(requested: StreamFormat, sourceChannels: Int): StreamFormat {
        var channels = sourceChannels.coerceIn(1, 2)
        if (requested.codec == CODEC_PCM_S16LE && requested.sampleRate == DEFAULT_SAMPLE_RATE) {
            // Downmixing is cheap, upmixing a mono source to stereo is not worth the bandwidth
            channels = minOf(channels, requested.channels)
        }
        return StreamFormat(channels = channels)
    }

    data class Decision(val error: String?, val format: StreamFormat)

    /** What a sender answers to a start request */
    fun decideStartRequest(request: Message, local: Capabilities, activeStreams: Int, streamIdInUse: Boolean): Decision {
        val error = when {
            request.source !in local.sources -> ERROR_UNSUPPORTED_SOURCE
            request.format.codec != CODEC_PCM_S16LE -> ERROR_UNSUPPORTED_FORMAT
            streamIdInUse || activeStreams >= local.maxStreams -> ERROR_BUSY
            else -> null
        }
        return Decision(error, negotiateFormat(request.format, sourceChannels(request.source)))
    }

    /** Whether a receiver can play an incoming start packet (null when it can) */
    fun checkIncomingStart(start: Message, local: Capabilities, activeStreams: Int, streamIdInUse: Boolean): String? = when {
        !local.canPlay -> ERROR_PLAYBACK_FAILED
        start.format.codec !in local.codecs -> ERROR_UNSUPPORTED_FORMAT
        streamIdInUse || activeStreams >= local.maxStreams -> ERROR_BUSY
        else -> null
    }
}
