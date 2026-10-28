/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.Capabilities
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.Message
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.ParseResult
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.StreamFormat
import java.io.InputStream

/**
 * Stream sessions with one device and the protocol state machine, see docs/specs/audiostream.md.
 * Everything platform specific (permissions, capture, playback, notifications, timers) goes
 * through [Environment] so this can be unit tested. Thread safe.
 */
class AudioStreamController(private val env: Environment, val localCapabilities: Capabilities) {

    fun interface StreamHandle {
        /** Release the resources of the stream. Must not block on threads that call back into the controller. */
        fun stop()
    }

    fun interface Cancellable {
        fun cancel()
    }

    interface Environment {
        fun send(np: NetworkPacket)
        /** A remote request needs the local user: answer with [consentGranted]/[consentDenied] */
        fun askConsent(streamId: String, source: String, format: StreamFormat)
        fun dismissConsent(streamId: String)
        /** Start playing an incoming stream; call [streamEnded] when it ends. Null if no output is available. */
        fun startPlayback(streamId: String, start: Message, payload: NetworkPacket.Payload): StreamHandle?
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
        fun sessionsChanged()
    }

    enum class State {
        /** We asked the peer to send, waiting for its start packet */
        REQUESTED,
        /** Waiting for the local user (consent notification, permission, MediaProjection dialog) */
        AWAITING_CONSENT,
        ACTIVE,
    }

    data class SessionInfo(val streamId: String, val source: String, val sending: Boolean, val state: State, val format: StreamFormat)

    private class Session(
        val source: String,
        val sending: Boolean,
        var state: State,
        var format: StreamFormat,
        /** The peer asked for this stream, so it has to hear about how it ends */
        val remotelyRequested: Boolean,
    ) {
        var handle: StreamHandle? = null
        var timeout: Cancellable? = null
    }

    private val sessions = LinkedHashMap<String, Session>()

    @Volatile
    var remoteCapabilities: Capabilities? = null
        private set

    @Synchronized
    fun sessions(): List<SessionInfo> = sessions.map { (id, s) -> SessionInfo(id, s.source, s.sending, s.state, s.format) }

    @Synchronized
    fun hasSession(streamId: String): Boolean = sessions.containsKey(streamId)

    fun onConnected() {
        env.send(AudioStreamProtocol.makeCapabilitiesRequest())
        env.send(AudioStreamProtocol.makeCapabilitiesPacket(localCapabilities))
    }

    /** Ask the peer to send its [source] here. Returns the stream id, null if we can't. */
    @Synchronized
    fun requestRemote(source: String): String? {
        if (source !in AudioStreamProtocol.ALL_SOURCES || sessions.size >= localCapabilities.maxStreams || !localCapabilities.canPlay) {
            return null
        }
        val streamId = AudioStreamProtocol.newStreamId()
        val preferred = StreamFormat(channels = AudioStreamProtocol.sourceChannels(source))
        val session = Session(source, sending = false, state = State.REQUESTED, format = preferred, remotelyRequested = false)
        sessions[streamId] = session
        session.timeout = env.schedule(REQUEST_TIMEOUT_MS) { endSession(streamId, AudioStreamProtocol.ERROR_TIMEOUT, notifyPeer = true) }
        env.send(AudioStreamProtocol.makeStartRequest(streamId, source, preferred))
        env.sessionsChanged()
        return streamId
    }

    /** Reserve a stream id to send local [source] to the peer, before asking for permissions */
    @Synchronized
    fun reserveLocalSend(source: String): String? {
        if (source !in localCapabilities.sources || sessions.size >= localCapabilities.maxStreams) {
            return null
        }
        val streamId = AudioStreamProtocol.newStreamId()
        val format = StreamFormat(channels = AudioStreamProtocol.sourceChannels(source))
        val session = Session(source, sending = true, state = State.AWAITING_CONSENT, format = format, remotelyRequested = false)
        sessions[streamId] = session
        session.timeout = env.schedule(CONSENT_TIMEOUT_MS) { endSession(streamId, AudioStreamProtocol.ERROR_PERMISSION_DENIED, notifyPeer = false) }
        env.sessionsChanged()
        return streamId
    }

    /** Source and format of a stream waiting for consent, null if there is none (anymore) */
    @Synchronized
    fun pendingOutgoing(streamId: String): Pair<String, StreamFormat>? {
        val s = sessions[streamId] ?: return null
        return if (s.sending && s.state == State.AWAITING_CONSENT) Pair(s.source, s.format) else null
    }

    @Synchronized
    fun consentGranted(streamId: String): Boolean {
        val s = sessions[streamId] ?: return false
        if (!s.sending || s.state != State.AWAITING_CONSENT) return false
        // The timeout keeps running: the user may still have the MediaProjection dialog in front of them
        env.dismissConsent(streamId)
        return true
    }

    @Synchronized
    fun consentDenied(streamId: String) {
        val s = sessions[streamId] ?: return
        if (s.sending && s.state == State.AWAITING_CONSENT) {
            endSession(streamId, AudioStreamProtocol.ERROR_PERMISSION_DENIED, notifyPeer = s.remotelyRequested)
        }
    }

    /**
     * Capture is running for an accepted stream: announce it with [payload] as the endless payload.
     * Returns false (and the caller has to release its resources) if the stream is gone.
     */
    @Synchronized
    fun outgoingReady(streamId: String, format: StreamFormat, callAudio: String?, payload: InputStream, handle: StreamHandle): Boolean {
        val s = sessions[streamId] ?: return false
        if (!s.sending || s.state != State.AWAITING_CONSENT) return false
        s.timeout?.cancel()
        s.timeout = null
        s.state = State.ACTIVE
        s.format = format
        s.handle = handle
        val np = AudioStreamProtocol.makeStartPacket(streamId, s.source, format, callAudio)
        np.payload = NetworkPacket.Payload(payload, -1)
        env.send(np)
        env.sessionsChanged()
        return true
    }

    @Synchronized
    fun captureFailed(streamId: String, error: String) {
        val s = sessions[streamId] ?: return
        // A locally started stream the peer never heard of needs no stop packet
        endSession(streamId, error, notifyPeer = s.state == State.ACTIVE || s.remotelyRequested)
    }

    /** The payload ended (closed by the peer, last frame, write failure) */
    @Synchronized
    fun streamEnded(streamId: String, error: String? = null) {
        if (sessions.containsKey(streamId)) {
            endSession(streamId, error, notifyPeer = true)
        }
    }

    @Synchronized
    fun stop(streamId: String) {
        val s = sessions[streamId] ?: return
        endSession(streamId, null, notifyPeer = s.state != State.AWAITING_CONSENT || s.remotelyRequested)
    }

    @Synchronized
    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
    }

    @Synchronized
    fun onPacketReceived(np: NetworkPacket): Boolean = when (np.type) {
        AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM -> {
            handleStreamPacket(np)
            true
        }
        AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST -> {
            handleRequestPacket(np)
            true
        }
        else -> false
    }

    private fun handleStreamPacket(np: NetworkPacket) {
        val result = AudioStreamProtocol.parse(np)
        if (result is ParseResult.Ok && result.message.action == AudioStreamProtocol.ACTION_CAPABILITIES) {
            remoteCapabilities = AudioStreamProtocol.parseCapabilities(np)
            env.sessionsChanged()
            return
        }
        if (result is ParseResult.Ok && result.message.action == AudioStreamProtocol.ACTION_STOP) {
            val s = sessions[result.message.streamId]
            if (s != null && !s.sending) {
                endSession(result.message.streamId, result.message.error.ifEmpty { null }, notifyPeer = false)
            }
            return
        }

        val message = when (result) {
            is ParseResult.Ok -> result.message
            is ParseResult.Invalid -> result.message
        }
        // Anything else is a start packet, its payload has to be closed if we refuse it
        fun refuse(reason: String) {
            np.payload?.close()
            if (AudioStreamProtocol.isValidStreamId(message.streamId)) {
                env.send(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, message.streamId, message.source, reason))
            }
        }

        if (result is ParseResult.Invalid) {
            refuse(result.error)
            val s = sessions[message.streamId]
            if (s != null && !s.sending) {
                endSession(message.streamId, result.error, notifyPeer = false)
            }
            return
        }
        if (message.action != AudioStreamProtocol.ACTION_START) {
            return
        }
        val payload = np.payload
        if (payload == null || payload.payloadSize != -1L || payload.inputStream == null) {
            refuse(AudioStreamProtocol.ERROR_INVALID_REQUEST)
            return
        }

        val existing = sessions[message.streamId]
        if (existing != null && (existing.sending || existing.state != State.REQUESTED)) {
            refuse(AudioStreamProtocol.ERROR_BUSY)
            return
        }
        if (existing == null) {
            val reason = AudioStreamProtocol.checkIncomingStart(message, localCapabilities, sessions.size, false)
            if (reason != null) {
                refuse(reason)
                return
            }
        }
        startIncoming(message, payload, existing)
    }

    private fun startIncoming(start: Message, payload: NetworkPacket.Payload, requested: Session?) {
        requested?.timeout?.cancel()
        val session = requested ?: Session(start.source, sending = false, state = State.ACTIVE, format = start.format, remotelyRequested = true)
        session.state = State.ACTIVE
        session.format = start.format
        session.timeout = null
        sessions[start.streamId] = session
        val handle = env.startPlayback(start.streamId, start, payload)
        if (handle == null) {
            payload.close()
            endSession(start.streamId, AudioStreamProtocol.ERROR_PLAYBACK_FAILED, notifyPeer = true)
            return
        }
        // startPlayback may already have reported the end of a short stream
        if (sessions[start.streamId] === session) {
            session.handle = handle
            env.sessionsChanged()
        } else {
            handle.stop()
        }
    }

    private fun handleRequestPacket(np: NetworkPacket) {
        val result = AudioStreamProtocol.parse(np)
        when (result) {
            is ParseResult.Invalid -> {
                if (AudioStreamProtocol.isValidStreamId(result.message.streamId)) {
                    env.send(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, result.message.streamId, result.message.source, result.error))
                }
            }
            is ParseResult.Ok -> {
                val m = result.message
                when (m.action) {
                    AudioStreamProtocol.ACTION_CAPABILITIES -> env.send(AudioStreamProtocol.makeCapabilitiesPacket(localCapabilities))
                    AudioStreamProtocol.ACTION_STOP -> {
                        val s = sessions[m.streamId] ?: return
                        // The receiver stopped or rejected the stream: confirm with a stop of our own if we send
                        endSession(m.streamId, m.error.ifEmpty { null }, notifyPeer = s.sending)
                    }
                    AudioStreamProtocol.ACTION_START -> handleStartRequest(m)
                }
            }
        }
    }

    private fun handleStartRequest(m: Message) {
        val decision = AudioStreamProtocol.decideStartRequest(m, localCapabilities, sessions.size, sessions.containsKey(m.streamId))
        if (decision.error != null) {
            env.send(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, m.streamId, m.source, decision.error))
            return
        }
        val session = Session(m.source, sending = true, state = State.AWAITING_CONSENT, format = decision.format, remotelyRequested = true)
        sessions[m.streamId] = session
        // Android needs a tap from the user before it lets us capture anything
        session.timeout = env.schedule(CONSENT_TIMEOUT_MS) { endSession(m.streamId, AudioStreamProtocol.ERROR_PERMISSION_DENIED, notifyPeer = true) }
        env.askConsent(m.streamId, m.source, decision.format)
        env.sessionsChanged()
    }

    @Synchronized
    private fun endSession(streamId: String, error: String?, notifyPeer: Boolean) {
        val s = sessions.remove(streamId) ?: return
        s.timeout?.cancel()
        if (s.state == State.AWAITING_CONSENT) {
            env.dismissConsent(streamId)
        }
        s.handle?.stop()
        if (notifyPeer) {
            val type = if (s.sending) AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM else AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST
            env.send(AudioStreamProtocol.makeStopPacket(type, streamId, s.source, error))
        }
        env.sessionsChanged()
    }

    companion object {
        /** The peer may have to wait for its user to accept a consent dialog */
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val CONSENT_TIMEOUT_MS = 60_000L
    }
}
