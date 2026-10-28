/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kde.kdeconnect.backends.relay.RelayProtocol.Target
import org.kde.kdeconnect.backends.relay.RelayProtocol.string
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.readLineBounded
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Exercises the Android client side (tunnel attach, end-to-end TLS, pinning, control connection)
 * over real loopback sockets against a minimal relay that splices tunnels and answers the control
 * protocol as described in docs/specs/relay.md. The real relay server is tested in kdeconnect-kde.
 */
class RelayTunnelTest {
    private val a = TestCertificates.generate("aaaaaaaa_aaaa_aaaa_aaaa_aaaaaaaaaaaa")
    private val b = TestCertificates.generate("bbbbbbbb_bbbb_bbbb_bbbb_bbbbbbbbbbbb")
    private val intruder = TestCertificates.generate("iiiiiiii_iiii_iiii_iiii_iiiiiiiiiiii")
    private val serverIdentity = TestCertificates.generate("relay")

    private lateinit var relay: FakeRelay

    @Before
    fun setUp() {
        relay = FakeRelay(serverIdentity)
    }

    @After
    fun tearDown() {
        relay.close()
    }

    private fun context(identity: TestCertificates.Identity) = RelaySsl.createContext(identity.certificate, identity.keyPair.private)

    private fun open(identity: TestCertificates.Identity, token: String, role: RelayLinkProvider.Role, pin: Target): SSLSocket =
        RelayLinkProvider.openTunnel(context(identity), relay.address, token, role, pin)

    /** Opens both ends concurrently and returns (server side, client side) results */
    private fun pair(
        serverId: TestCertificates.Identity, serverPin: Target,
        clientId: TestCertificates.Identity, clientPin: Target,
    ): Pair<Result<SSLSocket>, Result<SSLSocket>> {
        val token = relay.newToken()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val server = executor.submit<SSLSocket> { open(serverId, token, RelayLinkProvider.Role.TlsServer, serverPin) }
            val client = executor.submit<SSLSocket> { open(clientId, token, RelayLinkProvider.Role.TlsClient, clientPin) }
            val r1 = runCatching { server.get(20, TimeUnit.SECONDS) }
            val r2 = runCatching { client.get(20, TimeUnit.SECONDS) }
            return r1 to r2
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun endToEndTlsWithPinnedCertificates() {
        val (server, client) = pair(a, Target.Key(RelayProtocol.relayKey(b.certificate)), b, Target.forCertificate(a.certificate))
        val s = server.getOrThrow()
        val c = client.getOrThrow()
        assertEquals(b.certificate, s.session.peerCertificates[0])
        assertEquals(a.certificate, c.session.peerCertificates[0])

        s.outputStream.write("hello from a\n".toByteArray())
        s.outputStream.flush()
        c.outputStream.write("hello from b\n".toByteArray())
        c.outputStream.flush()
        assertEquals("hello from a\n", readLineBounded(c.inputStream, 100))
        assertEquals("hello from b\n", readLineBounded(s.inputStream, 100))

        // Payload sized data through the same kind of tunnel
        val data = ByteArray(512 * 1024) { (it * 31 + 7).toByte() }
        val writer = Thread {
            s.outputStream.write(data)
            s.outputStream.flush()
            s.close()
        }
        writer.start()
        val received = c.inputStream.readBytes()
        writer.join()
        assertArrayEquals(data, received)
        c.closeSafe()
    }

    @Test
    fun wrongPeerRejectedByServerSide() {
        val (server, client) = pair(a, Target.Key(RelayProtocol.relayKey(b.certificate)), intruder, Target.forCertificate(a.certificate))
        val error = server.exceptionOrNull()?.cause ?: server.exceptionOrNull()
        assertTrue("expected pin failure, got $error", error is SSLPeerUnverifiedException)
        client.getOrNull()?.let { socket ->
            // The intruder never gets application data: the server side closed right after the handshake
            try {
                assertEquals(-1, socket.inputStream.read())
            } catch (_: IOException) {
            }
            socket.closeSafe()
        }
    }

    @Test
    fun wrongPeerRejectedByClientSide() {
        val (server, client) = pair(intruder, Target.Key(RelayProtocol.relayKey(b.certificate)), b, Target.forCertificate(a.certificate))
        val error = client.exceptionOrNull()?.cause ?: client.exceptionOrNull()
        assertTrue("expected pin failure, got $error", error is SSLPeerUnverifiedException)
        server.getOrNull()?.closeSafe()
    }

    @Test
    fun pinChecks() {
        assertEquals(RelaySsl.PinResult.Match, RelaySsl.checkPin(Target.Key(RelayProtocol.relayKey(a.certificate)), a.certificate))
        assertEquals(RelaySsl.PinResult.Match, RelaySsl.checkPin(Target.forCertificate(a.certificate), a.certificate))
        assertEquals(RelaySsl.PinResult.Mismatch, RelaySsl.checkPin(Target.forCertificate(a.certificate), b.certificate))
        assertEquals(RelaySsl.PinResult.Mismatch, RelaySsl.checkPin(Target.Key(RelayProtocol.relayKey(a.certificate)), b.certificate))
        assertEquals(RelaySsl.PinResult.NoCertificate, RelaySsl.checkPin(Target.forCertificate(a.certificate), null))
        // A key pin survives a reissued certificate, a fingerprint pin does not
        val reissued = TestCertificates.reissue(a, a.deviceId).certificate
        assertEquals(RelaySsl.PinResult.Match, RelaySsl.checkPin(Target.Key(RelayProtocol.relayKey(a.certificate)), reissued))
        assertEquals(RelaySsl.PinResult.Mismatch, RelaySsl.checkPin(Target.forCertificate(a.certificate), reissued))
        // Paired devices must present the stored certificate
        assertTrue(RelaySsl.isAcceptableForTrustedDevice(a.certificate, a.certificate))
        assertTrue(!RelaySsl.isAcceptableForTrustedDevice(a.certificate, reissued))
        assertTrue(!RelaySsl.isAcceptableForTrustedDevice(null, a.certificate))
    }

    @Test
    fun invalidTokenClosesTunnel() {
        try {
            open(a, "unknown", RelayLinkProvider.Role.TlsClient, Target.forCertificate(b.certificate))
            fail("Expected failure")
        } catch (_: IOException) {
        }
    }

    @Test
    fun controlConnectionRegistersAndHandlesTunnels() {
        val events = LinkedBlockingQueue<String>()
        val listener = object : RelayConnection.Listener {
            override fun onStatusChanged(status: RelayConnection.Status) {
                events.add("status:$status")
            }
            override fun onTunnelReady(requestId: String, token: String, purpose: String, peerFingerprint: String) {
                events.add("tunnel:$requestId:$token:$purpose:$peerFingerprint")
            }
            override fun onRequestFailed(requestId: String, code: String) {
                events.add("failed:$requestId:$code")
            }
            override fun onIncoming(token: String, peerFingerprint: String) {
                events.add("incoming:$token:$peerFingerprint")
            }
            override fun onPeerOnline(peerFingerprint: String) {
                events.add("online:$peerFingerprint")
            }
        }
        val connection = RelayConnection(a.certificate, context(a), listener)
        connection.start(relay.address)
        try {
            assertEquals("status:Connecting", events.poll(10, TimeUnit.SECONDS))
            assertEquals("status:Registered", events.poll(10, TimeUnit.SECONDS))
            assertEquals(RelayConnection.Status.Registered, connection.status)
            assertEquals(RelayProtocol.relayKey(a.certificate), connection.key)
            // The relay identified us by the client certificate presented in the control TLS session
            assertEquals(RelayProtocol.fingerprint(a.certificate), relay.registeredFingerprints.poll(5, TimeUnit.SECONDS))

            val bFingerprint = RelayProtocol.fingerprint(b.certificate)
            val requestId = connection.requestTunnel(Target.Key(RelayProtocol.relayKey(b.certificate)), "link")!!
            assertEquals("connect:$requestId:${RelayProtocol.relayKey(b.certificate)}:link", relay.received.poll(5, TimeUnit.SECONDS))
            relay.sendToClient(RelayProtocol.message("tunnel", "requestId" to requestId, "token" to "tok1", "purpose" to "link", "peerFingerprint" to bFingerprint))
            assertEquals("tunnel:$requestId:tok1:link:$bFingerprint", events.poll(5, TimeUnit.SECONDS))

            relay.sendToClient(RelayProtocol.message("error", "requestId" to "42", "code" to "not_found", "message" to "x"))
            assertEquals("failed:42:not_found", events.poll(5, TimeUnit.SECONDS))

            relay.sendToClient(RelayProtocol.message("incoming", "token" to "tok2", "peerFingerprint" to bFingerprint, "peerKey" to "K"))
            assertEquals("incoming:tok2:$bFingerprint", events.poll(5, TimeUnit.SECONDS))

            relay.sendToClient(RelayProtocol.message("peer_online", "peerFingerprint" to bFingerprint, "peerKey" to "K"))
            assertEquals("online:$bFingerprint", events.poll(5, TimeUnit.SECONDS))

            connection.setWatchList(listOf(bFingerprint))
            assertEquals("watch:[\"$bFingerprint\"]", relay.received.poll(5, TimeUnit.SECONDS))

            // Server drops us: back to offline, then we reconnect when asked
            relay.dropClients()
            assertEquals("status:Offline", events.poll(10, TimeUnit.SECONDS))
            connection.retryNow()
            assertEquals("status:Connecting", events.poll(10, TimeUnit.SECONDS))
            assertEquals("status:Registered", events.poll(10, TimeUnit.SECONDS))
            // The watch list is sent again after re-registering
            assertEquals("watch:[\"$bFingerprint\"]", relay.received.poll(5, TimeUnit.SECONDS))
        } finally {
            connection.stop()
        }
        assertEquals(RelayConnection.Status.Disabled, connection.status)
    }

    /** Minimal relay: control connections answer hello/starttls/welcome and log requests, data connections get spliced by token */
    private class FakeRelay(identity: TestCertificates.Identity) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sslContext = RelaySsl.createContext(identity.certificate, identity.keyPair.private)
        private val executor = Executors.newCachedThreadPool()
        private val waiting = ConcurrentHashMap<String, Socket>()
        private val tokens = ConcurrentHashMap.newKeySet<String>()
        private val controls = ConcurrentHashMap.newKeySet<SSLSocket>()
        val received = LinkedBlockingQueue<String>()
        val registeredFingerprints = LinkedBlockingQueue<String>()
        val address = RelayProtocol.Address("127.0.0.1", server.localPort)
        private var counter = 0

        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    executor.execute { handle(socket) }
                }
            }
        }

        @Synchronized
        fun newToken(): String = "token${counter++}".also { tokens.add(it) }

        fun sendToClient(message: kotlinx.serialization.json.JsonObject) {
            for (control in controls) {
                synchronized(control) {
                    control.outputStream.write(RelayProtocol.encode(message))
                    control.outputStream.flush()
                }
            }
        }

        fun dropClients() {
            controls.forEach { it.closeSafe() }
            controls.clear()
        }

        private fun handle(socket: Socket) {
            try {
                val first = RelayProtocol.decode(readLineBounded(socket.getInputStream(), RelayProtocol.MAX_LINE_SIZE)) ?: run {
                    socket.close()
                    return
                }
                when (first.string("type")) {
                    "hello" -> control(socket)
                    "attach" -> attach(socket, first.string("token"))
                    else -> socket.close()
                }
            } catch (_: IOException) {
                socket.closeSafe()
            }
        }

        private fun control(socket: Socket) {
            socket.getOutputStream().write(RelayProtocol.encode(RelayProtocol.message("starttls")))
            socket.getOutputStream().flush()
            val tls = sslContext.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
            tls.useClientMode = false
            tls.needClientAuth = true
            tls.startHandshake()
            val peer = tls.session.peerCertificates[0] as X509Certificate
            registeredFingerprints.add(RelayProtocol.fingerprint(peer))
            synchronized(tls) {
                tls.outputStream.write(RelayProtocol.encode(RelayProtocol.message(
                    "welcome", "version" to RelayProtocol.VERSION, "key" to RelayProtocol.relayKey(peer), "fingerprint" to RelayProtocol.fingerprint(peer),
                )))
                tls.outputStream.flush()
            }
            controls.add(tls)
            while (true) {
                val message = RelayProtocol.decode(readLineBounded(tls.inputStream, RelayProtocol.MAX_LINE_SIZE)) ?: break
                when (message.string("type")) {
                    "connect" -> received.add("connect:${message.string("requestId")}:${message.string("target")}:${message.string("purpose")}")
                    "watch" -> received.add("watch:${message["fingerprints"]}")
                    "ping" -> synchronized(tls) {
                        tls.outputStream.write(RelayProtocol.encode(RelayProtocol.message("pong")))
                        tls.outputStream.flush()
                    }
                }
            }
        }

        private fun attach(socket: Socket, token: String) {
            if (!tokens.contains(token)) {
                socket.close()
                return
            }
            val other = synchronized(waiting) {
                waiting.remove(token) ?: run {
                    waiting[token] = socket
                    null
                }
            } ?: return
            tokens.remove(token)
            val done = CountDownLatch(2)
            fun pipe(from: Socket, to: Socket) = executor.execute {
                try {
                    from.getInputStream().copyTo(to.getOutputStream())
                } catch (_: IOException) {
                } finally {
                    to.closeSafe()
                    from.closeSafe()
                    done.countDown()
                }
            }
            pipe(other, socket)
            pipe(socket, other)
        }

        override fun close() {
            server.closeSafe()
            dropClients()
            waiting.values.forEach { it.closeSafe() }
            executor.shutdownNow()
        }
    }
}
