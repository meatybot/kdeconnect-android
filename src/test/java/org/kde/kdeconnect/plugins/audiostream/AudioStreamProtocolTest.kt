/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.Capabilities
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.Message
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.ParseResult
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.StreamFormat
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioStreamProtocolTest {
    private fun wire(np: NetworkPacket): NetworkPacket = NetworkPacket.unserialize(np.serialize())

    private fun ok(np: NetworkPacket): Message = (AudioStreamProtocol.parse(np) as ParseResult.Ok).message

    private fun startRequestJson(fields: String) =
        NetworkPacket.unserialize("""{"id":1,"type":"kdeconnect.audiostream.request","body":{$fields}}""")

    @Test
    fun startRequestRoundTrip() {
        val m = ok(wire(AudioStreamProtocol.makeStartRequest("abc-1", AudioStreamProtocol.SOURCE_MEDIA, StreamFormat())))
        assertEquals(AudioStreamProtocol.ACTION_START, m.action)
        assertEquals("abc-1", m.streamId)
        assertEquals(StreamFormat(), m.format)
    }

    @Test
    fun startPacketRoundTripWithEndlessPayload() {
        val format = StreamFormat(channels = 1, frameDurationMs = 10)
        val np = AudioStreamProtocol.makeStartPacket("s1", AudioStreamProtocol.SOURCE_CALL, format, AudioStreamProtocol.CALL_AUDIO_LOCAL_MICROPHONE_ONLY)
        np.payload = NetworkPacket.Payload(ByteArrayInputStream(ByteArray(0)), -1)
        np.payloadTransferInfo = kotlinx.serialization.json.buildJsonObject { put("port", kotlinx.serialization.json.JsonPrimitive(1739)) }
        val parsed = wire(np)
        assertEquals(-1L, parsed.payloadSize)
        val m = ok(parsed)
        assertEquals(format, m.format)
        assertEquals(960, m.format.frameBytes)
        assertEquals(AudioStreamProtocol.CALL_AUDIO_LOCAL_MICROPHONE_ONLY, m.callAudio)
    }

    @Test
    fun desktopPacketIsAccepted() {
        // As produced by the desktop plugin (Qt serializes whole doubles without a fraction)
        val np = NetworkPacket.unserialize(
            """{"id":1761600000123,"type":"kdeconnect.audiostream","body":{"action":"start","channels":2,"codec":"pcm_s16le","frameDurationMs":20,"sampleRate":48000,"source":"system","streamId":"4f1c3a52-8b8e-4c55-9d0a-6a3f9f7f2b10"},"payloadSize":-1,"payloadTransferInfo":{"port":1740}}"""
        )
        val m = ok(np)
        assertEquals(AudioStreamProtocol.SOURCE_SYSTEM, m.source)
        assertEquals(StreamFormat(), m.format)
    }

    @Test
    fun invalidFieldsAreRejected() {
        val base = mapOf(
            "action" to "\"start\"", "streamId" to "\"x\"", "source" to "\"media\"",
            "codec" to "\"pcm_s16le\"", "sampleRate" to "48000", "channels" to "2",
        )
        fun parse(changes: Map<String, String?>): ParseResult {
            val fields = (base + changes).filterValues { it != null }.map { (k, v) -> "\"$k\":$v" }.joinToString(",")
            return AudioStreamProtocol.parse(startRequestJson(fields))
        }
        val cases = mapOf(
            mapOf("action" to "\"play\"") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("action" to null) to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("streamId" to null) to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("streamId" to "\"a b\"") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("streamId" to "\"${"a".repeat(65)}\"") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("source" to "\"radio\"") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("codec" to null) to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("codec" to "\"opus\"") to AudioStreamProtocol.ERROR_UNSUPPORTED_FORMAT,
            mapOf("sampleRate" to "100") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("sampleRate" to "\"48000\"") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("sampleRate" to "44100.5") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("channels" to "3") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("channels" to null) to AudioStreamProtocol.ERROR_INVALID_REQUEST,
            mapOf("frameDurationMs" to "1000") to AudioStreamProtocol.ERROR_INVALID_REQUEST,
        )
        for ((changes, error) in cases) {
            val result = parse(changes)
            assertTrue("$changes should be invalid", result is ParseResult.Invalid)
            assertEquals("$changes", error, (result as ParseResult.Invalid).error)
        }
        assertTrue(parse(emptyMap()) is ParseResult.Ok)
        assertTrue(parse(mapOf("sampleRate" to "48000.0")) is ParseResult.Ok)
    }

    @Test
    fun stopAndCapabilities() {
        val stop = ok(wire(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, "s", AudioStreamProtocol.SOURCE_MICROPHONE, AudioStreamProtocol.ERROR_PERMISSION_DENIED)))
        assertEquals(AudioStreamProtocol.ACTION_STOP, stop.action)
        assertEquals(AudioStreamProtocol.ERROR_PERMISSION_DENIED, stop.error)
        assertEquals("", ok(startRequestJson(""""action":"stop","streamId":"s"""")).error)

        val caps = Capabilities(listOf(AudioStreamProtocol.SOURCE_MICROPHONE, AudioStreamProtocol.SOURCE_CALL), canPlay = false, maxStreams = 2)
        assertEquals(caps, AudioStreamProtocol.parseCapabilities(wire(AudioStreamProtocol.makeCapabilitiesPacket(caps))))
        val newer = AudioStreamProtocol.parseCapabilities(
            NetworkPacket.unserialize("""{"id":1,"type":"kdeconnect.audiostream","body":{"action":"capabilities","sources":["hologram","media"]}}""")
        )
        assertEquals(listOf(AudioStreamProtocol.SOURCE_MEDIA), newer.sources)
        assertEquals(AudioStreamProtocol.MAX_STREAMS, newer.maxStreams)
        assertTrue(newer.canPlay)
    }

    @Test
    fun negotiation() {
        val local = Capabilities(listOf(AudioStreamProtocol.SOURCE_MEDIA, AudioStreamProtocol.SOURCE_MICROPHONE))
        val request = Message(AudioStreamProtocol.ACTION_START, "s", AudioStreamProtocol.SOURCE_MEDIA, StreamFormat(channels = 1))
        assertEquals(StreamFormat(channels = 1), AudioStreamProtocol.decideStartRequest(request, local, 0, false).format)
        val mic = request.copy(source = AudioStreamProtocol.SOURCE_MICROPHONE, format = StreamFormat(channels = 2))
        assertEquals(1, AudioStreamProtocol.decideStartRequest(mic, local, 0, false).format.channels)
        val odd = request.copy(format = StreamFormat(sampleRate = 44100, channels = 1))
        assertEquals(StreamFormat(), AudioStreamProtocol.decideStartRequest(odd, local, 0, false).format)

        assertEquals(AudioStreamProtocol.ERROR_UNSUPPORTED_SOURCE, AudioStreamProtocol.decideStartRequest(request.copy(source = AudioStreamProtocol.SOURCE_SYSTEM), local, 0, false).error)
        assertEquals(AudioStreamProtocol.ERROR_BUSY, AudioStreamProtocol.decideStartRequest(request, local, AudioStreamProtocol.MAX_STREAMS, false).error)
        assertEquals(AudioStreamProtocol.ERROR_BUSY, AudioStreamProtocol.decideStartRequest(request, local, 0, true).error)
        assertEquals(AudioStreamProtocol.ERROR_UNSUPPORTED_FORMAT, AudioStreamProtocol.decideStartRequest(request.copy(format = StreamFormat(codec = "opus")), local, 0, false).error)

        assertNull(AudioStreamProtocol.checkIncomingStart(request, local, 0, false))
        assertEquals(AudioStreamProtocol.ERROR_BUSY, AudioStreamProtocol.checkIncomingStart(request, local, AudioStreamProtocol.MAX_STREAMS, false))
        assertEquals(AudioStreamProtocol.ERROR_PLAYBACK_FAILED, AudioStreamProtocol.checkIncomingStart(request, local.copy(canPlay = false), 0, false))
    }

    /** Records what the controller does; timers fire only when the test says so */
    private class FakeEnv : AudioStreamController.Environment {
        val sent = mutableListOf<NetworkPacket>()
        val consents = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val stoppedHandles = mutableListOf<String>()
        var playbackAvailable = true
        val playing = mutableListOf<String>()
        val approvedCaptures = mutableListOf<String>()
        var now = 1_000L

        override fun send(np: NetworkPacket) {
            sent.add(np)
        }
        override fun askConsent(streamId: String, source: String, format: StreamFormat) {
            consents.add(streamId)
        }
        override fun startApprovedCapture(streamId: String, source: String, format: StreamFormat) {
            approvedCaptures.add(streamId)
        }
        override fun nowMs(): Long = now
        override fun dismissConsent(streamId: String) {
            dismissed.add(streamId)
        }
        override fun startPlayback(streamId: String, start: Message, payload: NetworkPacket.Payload): AudioStreamController.StreamHandle? {
            if (!playbackAvailable) return null
            playing.add(streamId)
            return AudioStreamController.StreamHandle { stoppedHandles.add(streamId) }
        }
        override fun schedule(delayMs: Long, action: () -> Unit): AudioStreamController.Cancellable {
            val entry = Pair(delayMs, action)
            timers.add(entry)
            return AudioStreamController.Cancellable { timers.remove(entry) }
        }
        override fun sessionsChanged() {}

        fun last(): Message = (AudioStreamProtocol.parse(sent.last()) as ParseResult.Ok).message
    }

    private val allSources = Capabilities(AudioStreamProtocol.ALL_SOURCES)

    private fun startPacket(streamId: String, payloadSize: Long = -1): NetworkPacket =
        AudioStreamProtocol.makeStartPacket(streamId, AudioStreamProtocol.SOURCE_MEDIA, StreamFormat()).apply {
            payload = NetworkPacket.Payload(ByteArrayInputStream(ByteArray(0)), payloadSize)
        }

    @Test
    fun remoteRequestNeedsConsentAndTimesOut() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("r1", AudioStreamProtocol.SOURCE_MICROPHONE, StreamFormat()))
        assertEquals(listOf("r1"), env.consents)
        assertTrue(env.sent.isEmpty())
        assertEquals(AudioStreamController.State.AWAITING_CONSENT, controller.sessions().single().state)
        assertEquals(1, controller.pendingOutgoing("r1")!!.second.channels)

        // Nobody answers
        assertEquals(AudioStreamController.CONSENT_TIMEOUT_MS, env.timers.single().first)
        env.timers.single().second()
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, env.sent.last().type)
        assertEquals(AudioStreamProtocol.ERROR_PERMISSION_DENIED, env.last().error)
        assertEquals(listOf("r1"), env.dismissed)
        assertTrue(controller.sessions().isEmpty())
    }

    @Test
    fun remoteRequestGrantedStreamsAndStopsOnRequest() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("r1", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertTrue(controller.consentGranted("r1"))
        var stopped = false
        val queue = FrameQueue(1 shl 16)
        assertTrue(controller.outgoingReady("r1", StreamFormat(channels = 1), AudioStreamProtocol.CALL_AUDIO_LOCAL_MICROPHONE_ONLY, queue) { stopped = true })
        val start = env.sent.last()
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, start.type)
        assertEquals(-1L, start.payloadSize)
        assertEquals(AudioStreamProtocol.CALL_AUDIO_LOCAL_MICROPHONE_ONLY, env.last().callAudio)
        assertTrue(env.timers.isEmpty())

        controller.onPacketReceived(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, "r1"))
        assertTrue(stopped)
        assertEquals(AudioStreamProtocol.ACTION_STOP, env.last().action)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, env.sent.last().type)
        assertTrue(controller.sessions().isEmpty())
        // A late grant for a stream that is gone does nothing
        assertFalse(controller.consentGranted("r1"))
    }

    @Test
    fun callAudioAfterRemoteAnswerSkipsConsentOnce() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        controller.approveCallAudio()

        // Only the call source is covered by answering the call
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("mic", AudioStreamProtocol.SOURCE_MICROPHONE, StreamFormat()))
        assertEquals(listOf("mic"), env.consents)
        assertTrue(env.approvedCaptures.isEmpty())

        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("call-1", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertEquals(listOf("call-1"), env.approvedCaptures)
        assertEquals(listOf("mic"), env.consents)
        assertEquals(AudioStreamController.State.AWAITING_CONSENT, controller.sessions().first { it.streamId == "call-1" }.state)
        assertTrue(controller.consentGranted("call-1"))
        assertTrue(controller.outgoingReady("call-1", StreamFormat(channels = 1), null, FrameQueue(1 shl 16)) {})
        assertEquals(AudioStreamProtocol.ACTION_START, env.last().action)

        // The approval is used up: the next call request asks again
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("call-2", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertEquals(listOf("mic", "call-2"), env.consents)
        assertEquals(listOf("call-1"), env.approvedCaptures)
    }

    @Test
    fun callAudioApprovalExpiresAndCanFallBackToConsent() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        controller.approveCallAudio()
        env.now += AudioStreamController.CALL_AUDIO_APPROVAL_MS
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("late", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertEquals(listOf("late"), env.consents)
        assertTrue(env.approvedCaptures.isEmpty())

        // Hanging up revokes it
        controller.approveCallAudio()
        controller.revokeCallAudioApproval()
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("after-hangup", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertEquals(listOf("late", "after-hangup"), env.consents)

        // Android refuses to start the approved capture from the background: the user is asked instead
        val fresh = FakeEnv()
        val c2 = AudioStreamController(fresh, allSources)
        c2.approveCallAudio()
        c2.onPacketReceived(AudioStreamProtocol.makeStartRequest("bg", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertEquals(listOf("bg"), fresh.approvedCaptures)
        c2.approvedCaptureRefused("bg")
        assertEquals(listOf("bg"), fresh.consents)
        assertEquals(1, fresh.timers.size)
        fresh.timers.single().second()
        assertEquals(AudioStreamProtocol.ERROR_PERMISSION_DENIED, fresh.last().error)
        assertTrue(c2.sessions().isEmpty())
    }

    @Test
    fun silencedCaptureTellsThePeer() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        controller.approveCallAudio()
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("call-1", AudioStreamProtocol.SOURCE_CALL, StreamFormat()))
        assertTrue(controller.consentGranted("call-1"))
        var released = false
        assertTrue(controller.outgoingReady("call-1", StreamFormat(channels = 1), null, FrameQueue(1 shl 16)) { released = true })

        // Android mutes the microphone for the whole call
        controller.captureFailed("call-1", AudioStreamProtocol.ERROR_SILENCED)
        assertTrue(released)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, env.sent.last().type)
        assertEquals(AudioStreamProtocol.ACTION_STOP, env.last().action)
        assertEquals(AudioStreamProtocol.ERROR_SILENCED, env.last().error)
        assertTrue(controller.sessions().isEmpty())
    }

    @Test
    fun remoteRequestRefusals() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, Capabilities(listOf(AudioStreamProtocol.SOURCE_MICROPHONE), maxStreams = 1))
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("a", AudioStreamProtocol.SOURCE_MEDIA, StreamFormat()))
        assertEquals(AudioStreamProtocol.ERROR_UNSUPPORTED_SOURCE, env.last().error)
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("b", AudioStreamProtocol.SOURCE_MICROPHONE, StreamFormat()))
        controller.onPacketReceived(AudioStreamProtocol.makeStartRequest("c", AudioStreamProtocol.SOURCE_MICROPHONE, StreamFormat()))
        assertEquals("c", env.last().streamId)
        assertEquals(AudioStreamProtocol.ERROR_BUSY, env.last().error)
        controller.consentDenied("b")
        assertEquals("b", env.last().streamId)
        assertEquals(AudioStreamProtocol.ERROR_PERMISSION_DENIED, env.last().error)

        val opus = startRequestJson(""""action":"start","streamId":"d","source":"microphone","codec":"opus","sampleRate":48000,"channels":1""")
        controller.onPacketReceived(opus)
        assertEquals(AudioStreamProtocol.ERROR_UNSUPPORTED_FORMAT, env.last().error)

        controller.onPacketReceived(AudioStreamProtocol.makeCapabilitiesRequest())
        assertEquals(AudioStreamProtocol.ACTION_CAPABILITIES, env.last().action)
        assertEquals(listOf(AudioStreamProtocol.SOURCE_MICROPHONE), AudioStreamProtocol.parseCapabilities(env.sent.last()).sources)
    }

    @Test
    fun ourRequestIsAnsweredWithAStream() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        val id = controller.requestRemote(AudioStreamProtocol.SOURCE_SYSTEM)!!
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, env.sent.last().type)
        assertEquals(AudioStreamController.REQUEST_TIMEOUT_MS, env.timers.single().first)

        controller.onPacketReceived(startPacket(id))
        assertEquals(listOf(id), env.playing)
        assertTrue(env.timers.isEmpty())
        assertEquals(AudioStreamController.State.ACTIVE, controller.sessions().single().state)

        // The sender ends it
        controller.onPacketReceived(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, id))
        assertEquals(listOf(id), env.stoppedHandles)
        assertTrue(controller.sessions().isEmpty())
        assertEquals(1, env.sent.size) // no stop sent back for a stop
    }

    @Test
    fun ourRequestRefusedOrTimedOut() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        val refused = controller.requestRemote(AudioStreamProtocol.SOURCE_CALL)!!
        controller.onPacketReceived(AudioStreamProtocol.makeStopPacket(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, refused, error = AudioStreamProtocol.ERROR_PERMISSION_DENIED))
        assertTrue(controller.sessions().isEmpty())

        val silent = controller.requestRemote(AudioStreamProtocol.SOURCE_MEDIA)!!
        env.timers.single().second()
        assertTrue(controller.sessions().isEmpty())
        assertEquals(silent, env.last().streamId)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, env.sent.last().type)
        assertEquals(AudioStreamProtocol.ERROR_TIMEOUT, env.last().error)
    }

    @Test
    fun incomingPushStreams() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources.copy(maxStreams = 1))
        controller.onPacketReceived(startPacket("p1"))
        assertEquals(listOf("p1"), env.playing)
        assertFalse(controller.sessions().single().sending)

        // Over the limit
        controller.onPacketReceived(startPacket("p2"))
        assertEquals("p2", env.last().streamId)
        assertEquals(AudioStreamProtocol.ERROR_BUSY, env.last().error)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, env.sent.last().type)

        // Same id again
        controller.onPacketReceived(startPacket("p1"))
        assertEquals(AudioStreamProtocol.ERROR_BUSY, env.last().error)

        // Local stop tells the sender
        controller.stop("p1")
        assertEquals(AudioStreamProtocol.ACTION_STOP, env.last().action)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST, env.sent.last().type)

        // Not an endless payload
        controller.onPacketReceived(startPacket("p3", payloadSize = 10))
        assertEquals(AudioStreamProtocol.ERROR_INVALID_REQUEST, env.last().error)

        // No output device
        env.playbackAvailable = false
        controller.onPacketReceived(startPacket("p4"))
        assertEquals(AudioStreamProtocol.ERROR_PLAYBACK_FAILED, env.last().error)
        assertTrue(controller.sessions().isEmpty())
    }

    @Test
    fun localSendLifecycle() {
        val env = FakeEnv()
        val controller = AudioStreamController(env, allSources)
        val id = controller.reserveLocalSend(AudioStreamProtocol.SOURCE_MEDIA)!!
        assertNotNull(controller.pendingOutgoing(id))
        assertTrue(env.sent.isEmpty())
        // User backs out of the MediaProjection dialog: the peer never heard of it
        controller.consentDenied(id)
        assertTrue(env.sent.isEmpty())

        val id2 = controller.reserveLocalSend(AudioStreamProtocol.SOURCE_MICROPHONE)!!
        assertTrue(controller.consentGranted(id2))
        controller.captureFailed(id2, AudioStreamProtocol.ERROR_CAPTURE_FAILED)
        assertTrue(env.sent.isEmpty())

        val id3 = controller.reserveLocalSend(AudioStreamProtocol.SOURCE_MICROPHONE)!!
        controller.consentGranted(id3)
        assertTrue(controller.outgoingReady(id3, StreamFormat(channels = 1), null, FrameQueue(1024)) {})
        // The receiver closed the payload
        controller.streamEnded(id3)
        assertEquals(id3, env.last().streamId)
        assertEquals(AudioStreamProtocol.ACTION_STOP, env.last().action)
        assertEquals(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, env.sent.last().type)
        assertNull(controller.reserveLocalSend("radio"))
    }

    /** Sender queue -> TCP socket -> receiver -> playback buffer, with an idle gap and teardown */
    @Test
    fun endToEndOverSocket() {
        val format = StreamFormat(channels = 1, frameDurationMs = 10)
        val server = ServerSocket(0)
        val queue = FrameQueue(1 shl 20)
        val sender = Thread {
            server.accept().use { socket ->
                val out = socket.getOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = queue.read(buf, 0, buf.size)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        }
        sender.start()

        val socket = Socket("127.0.0.1", server.localPort)
        val buffer = PlaybackBuffer(format, 20)
        val ended = CountDownLatch(1)
        val receiver = StreamReceiver(socket.getInputStream(), format, buffer, onEnd = { ended.countDown() }, clockUs = { System.nanoTime() / 1000 }, close = { socket.close() })
        receiver.start()

        val sent = (0 until 10).map { i -> ByteArray(format.frameBytes) { ((i * 7 + it) and 0xFF).toByte() } }
        sent.take(5).forEach { queue.enqueue(System.nanoTime() / 1000, it) }
        waitFor { receiver.framesDecoded == 5L }
        Thread.sleep(300) // idle: the stream must survive it
        assertEquals(1L, ended.count)
        sent.drop(5).forEach { queue.enqueue(System.nanoTime() / 1000, it) }
        waitFor { receiver.framesDecoded == 10L }

        sent.forEach { assertArrayEquals(it, buffer.nextFrame()) }
        assertEquals(0, buffer.stats().lost)

        queue.finish()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        sender.join(5000)
        assertFalse(sender.isAlive)
        assertTrue(receiver.jitterMs >= 0)
        server.close()
    }

    @Test
    fun receiverStopClosesTheConnection() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        var senderSawEof = false
        val peer = Thread {
            server.accept().use { s ->
                accepted.countDown()
                senderSawEof = s.getInputStream().read() == -1
            }
        }
        peer.start()
        val socket = Socket("127.0.0.1", server.localPort)
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val ended = CountDownLatch(1)
        val receiver = StreamReceiver(socket.getInputStream(), StreamFormat(), PlaybackBuffer(StreamFormat(), 40), onEnd = { ended.countDown() }, clockUs = { 0 }, close = { socket.close() })
        receiver.start()
        receiver.stop()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        peer.join(5000)
        assertTrue(senderSawEof)
        server.close()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
