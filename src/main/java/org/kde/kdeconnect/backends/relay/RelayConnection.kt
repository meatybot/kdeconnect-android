/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.relay

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.readLineBounded
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Control connection to a relay server: registration, tunnel requests and notifications.
 * Runs its own thread; listener callbacks are called from it. See docs/specs/relay.md
 */
class RelayConnection(
    private val certificate: Certificate,
    private val sslContext: SSLContext,
    private val listener: Listener,
) {
    enum class Status { Disabled, Connecting, Registered, Offline }

    interface Listener {
        fun onStatusChanged(status: Status)
        fun onTunnelReady(requestId: String, token: String, purpose: String, peerFingerprint: String)
        fun onRequestFailed(requestId: String, code: String)
        fun onIncoming(token: String, peerFingerprint: String)
        fun onPeerOnline(peerFingerprint: String)
    }

    @Volatile
    var status: Status = Status.Disabled
        private set

    /** Our key, derived locally. Only meaningful while registered. */
    val key: String = RelayProtocol.relayKey(certificate)

    @Volatile
    var address: RelayProtocol.Address? = null
        private set

    private val lock = Object()
    private var thread: Thread? = null
    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var watchList: List<String> = emptyList()
    private val nextRequestId = AtomicLong(1)
    @Volatile
    private var retryNow = false

    fun start(address: RelayProtocol.Address) {
        synchronized(lock) {
            if (thread != null && this.address == address) {
                return
            }
            stopLocked()
            this.address = address
            val newThread = Thread({ run(address) }, "RelayConnection")
            newThread.isDaemon = true
            thread = newThread
            newThread.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            address = null
        }
        setStatus(Status.Disabled)
    }

    private fun stopLocked() {
        val oldThread = thread
        thread = null
        socket?.closeSafe()
        socket = null
        output = null
        oldThread?.interrupt()
    }

    fun retryNow() {
        retryNow = true
        synchronized(lock) {
            if (status == Status.Offline) {
                thread?.interrupt()
            }
        }
    }

    private fun setStatus(newStatus: Status) {
        if (status != newStatus) {
            status = newStatus
            listener.onStatusChanged(newStatus)
        }
    }

    private fun isCurrent(): Boolean = synchronized(lock) { thread === Thread.currentThread() }

    private fun run(address: RelayProtocol.Address) {
        var retryMs = INITIAL_RETRY_MS
        while (isCurrent()) {
            setStatus(Status.Connecting)
            try {
                session(address) { retryMs = INITIAL_RETRY_MS }
            } catch (e: IOException) {
                Log.i(LOG_TAG, "Relay connection ended: ${e.message}")
            }
            synchronized(lock) {
                socket?.closeSafe()
                socket = null
                output = null
            }
            if (!isCurrent()) {
                return
            }
            setStatus(Status.Offline)
            try {
                if (!retryNow) {
                    Thread.sleep(retryMs)
                }
            } catch (_: InterruptedException) {
                // Woken up by retryNow() or stop()
            }
            retryNow = false
            retryMs = minOf(retryMs * 2, MAX_RETRY_MS)
        }
    }

    @Throws(IOException::class)
    private fun session(address: RelayProtocol.Address, onRegistered: () -> Unit) {
        val plain = Socket()
        plain.connect(InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS)
        plain.keepAlive = true
        plain.soTimeout = REGISTRATION_TIMEOUT_MS
        val plainOut = plain.getOutputStream()
        plainOut.write(RelayProtocol.encode(RelayProtocol.message("hello", "version" to RelayProtocol.VERSION)))
        plainOut.flush()
        val first = RelayProtocol.decode(readLineBounded(plain.getInputStream(), RelayProtocol.MAX_LINE_SIZE))
        if (first == null || RelayProtocol.parseServerMessage(first) != RelayProtocol.ServerMessage.StartTls) {
            plain.closeSafe()
            throw IOException("Unexpected reply from relay server")
        }

        // The relay is only trusted with metadata, its certificate is not verified (see spec)
        val tls = sslContext.socketFactory.createSocket(plain, address.host, address.port, true) as SSLSocket
        tls.useClientMode = true
        synchronized(lock) {
            if (!isCurrent()) {
                tls.closeSafe()
                return
            }
            socket = tls
        }
        tls.startHandshake()
        val input: InputStream = tls.inputStream
        synchronized(lock) { output = tls.outputStream }

        var lastPing = System.currentTimeMillis()
        tls.soTimeout = PING_INTERVAL_MS
        while (isCurrent()) {
            val line = try {
                readLineBounded(input, RelayProtocol.MAX_LINE_SIZE)
            } catch (_: SocketTimeoutException) {
                if (status != Status.Registered) {
                    throw IOException("Registration timed out")
                }
                if (System.currentTimeMillis() - lastPing > 3 * PING_INTERVAL_MS) {
                    throw IOException("No reply from relay server")
                }
                send(RelayProtocol.message("ping"))
                continue
            }
            lastPing = System.currentTimeMillis()
            val message = RelayProtocol.decode(line) ?: throw IOException("Malformed message from relay server")
            when (val parsed = RelayProtocol.parseServerMessage(message)) {
                is RelayProtocol.ServerMessage.Welcome -> {
                    if (parsed.version != RelayProtocol.VERSION) {
                        throw IOException("Unsupported relay protocol version ${parsed.version}")
                    }
                    if (parsed.key != key) {
                        Log.w(LOG_TAG, "Relay reported a different key than ours, ignoring it")
                    }
                    onRegistered()
                    setStatus(Status.Registered)
                    val watched = synchronized(lock) { watchList }
                    if (watched.isNotEmpty()) {
                        setWatchList(watched)
                    }
                }
                is RelayProtocol.ServerMessage.Tunnel -> listener.onTunnelReady(parsed.requestId, parsed.token, parsed.purpose, parsed.peerFingerprint)
                is RelayProtocol.ServerMessage.Incoming -> listener.onIncoming(parsed.token, parsed.peerFingerprint)
                is RelayProtocol.ServerMessage.PeerOnline -> listener.onPeerOnline(parsed.peerFingerprint)
                is RelayProtocol.ServerMessage.Error -> {
                    Log.i(LOG_TAG, "Relay error ${parsed.code}: ${parsed.message}")
                    if (parsed.requestId.isNotEmpty()) {
                        listener.onRequestFailed(parsed.requestId, parsed.code)
                    } else if (parsed.code == "replaced") {
                        throw IOException("Another connection registered with our certificate")
                    }
                }
                else -> Unit
            }
        }
    }

    private fun send(message: kotlinx.serialization.json.JsonObject): Boolean {
        val bytes = RelayProtocol.encode(message)
        synchronized(lock) {
            val out = output ?: return false
            return try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Failed to send to relay: ${e.message}")
                socket?.closeSafe()
                false
            }
        }
    }

    /** Returns the request id, or null if not registered. Blocking, call from a worker thread. */
    fun requestTunnel(target: RelayProtocol.Target, purpose: String): String? {
        if (status != Status.Registered) {
            return null
        }
        val requestId = nextRequestId.getAndIncrement().toString()
        val sent = send(RelayProtocol.message("connect", "requestId" to requestId, "target" to target.value, "purpose" to purpose))
        return if (sent) requestId else null
    }

    fun setWatchList(fingerprints: List<String>) {
        synchronized(lock) { watchList = fingerprints }
        if (status == Status.Registered) {
            send(RelayProtocol.message("watch", "fingerprints" to JsonArray(fingerprints.map { JsonPrimitive(it) })))
        }
    }

    companion object {
        private const val LOG_TAG = "KDE/RelayConnection"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val REGISTRATION_TIMEOUT_MS = 15000
        private const val PING_INTERVAL_MS = 60000
        private const val INITIAL_RETRY_MS = 5000L
        private const val MAX_RETRY_MS = 5 * 60 * 1000L
    }
}
