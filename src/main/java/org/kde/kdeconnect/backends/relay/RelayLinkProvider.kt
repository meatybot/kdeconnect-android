/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import kotlinx.serialization.SerializationException
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.helpers.security.RsaHelper
import org.kde.kdeconnect.helpers.security.SslHelper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Connects to devices on other networks through a user-configured relay server. See docs/specs/relay.md
 */
class RelayLinkProvider(private val context: Context) : BaseLinkProvider() {
    enum class Role { TlsServer, TlsClient }

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val visibleDevices = ConcurrentHashMap<String, RelayLink>()
    private val pendingLinkRequests = ConcurrentHashMap<String, RelayProtocol.Target>() // requestId -> pin
    private val pendingPayloadRequests = ConcurrentHashMap<String, CompletableFuture<String>>() // requestId -> token
    private val pendingTunnels = AtomicInteger(0)

    @Volatile
    private var isStopped = true

    @Volatile
    private var lastWatchList: List<String>? = null

    private val sslContext: SSLContext by lazy {
        RelaySsl.createContext(SslHelper.certificate, RsaHelper.getPrivateKey(context))
    }

    private val connection: RelayConnection by lazy {
        RelayConnection(SslHelper.certificate, sslContext, object : RelayConnection.Listener {
            override fun onStatusChanged(status: RelayConnection.Status) {
                Log.i(LOG_TAG, "Relay status: $status")
                if (status == RelayConnection.Status.Registered) {
                    lastWatchList = null
                    refreshWatchList()
                } else {
                    pendingLinkRequests.clear()
                    pendingPayloadRequests.values.forEach { it.completeExceptionally(IOException("Relay disconnected")) }
                    pendingPayloadRequests.clear()
                }
                _status.postValue(status)
            }

            override fun onTunnelReady(requestId: String, token: String, purpose: String, peerFingerprint: String) {
                // peerFingerprint is never trusted: the pin comes from what we asked for
                when (purpose) {
                    "link" -> pendingLinkRequests.remove(requestId)?.let { pin ->
                        ThreadHelper.execute { linkTunnel(token, Role.TlsServer, pin) }
                    }
                    "payload" -> pendingPayloadRequests.remove(requestId)?.complete(token)
                }
            }

            override fun onRequestFailed(requestId: String, code: String) {
                if (pendingLinkRequests.remove(requestId) != null) {
                    Log.i(LOG_TAG, "Connect request failed: $code")
                }
                pendingPayloadRequests.remove(requestId)?.completeExceptionally(IOException("Relay error $code"))
            }

            override fun onIncoming(token: String, peerFingerprint: String) {
                // The peer pins us; we pin the certificate the relay says it belongs to (and the stored one if paired)
                ThreadHelper.execute { linkTunnel(token, Role.TlsClient, RelayProtocol.Target.Fingerprint(peerFingerprint)) }
            }

            override fun onPeerOnline(peerFingerprint: String) {
                if (visibleDevices.values.any { RelayProtocol.fingerprint(it.deviceInfo.certificate) == peerFingerprint }) {
                    return
                }
                // Only one side initiates, so both don't open a tunnel at the same time
                if (RelayProtocol.fingerprint(SslHelper.certificate) < peerFingerprint) {
                    connectTo(RelayProtocol.Target.Fingerprint(peerFingerprint))
                }
            }
        })
    }

    private val _status = MutableLiveData(RelayConnection.Status.Disabled)
    val status: LiveData<RelayConnection.Status> = _status

    /** Our relay key, derived from our certificate */
    val relayKey: String
        get() = RelayProtocol.relayKey(SslHelper.certificate)

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_RELAY_ENABLED || key == KEY_RELAY_SERVER) {
            ThreadHelper.execute { applyConfig() }
        }
    }

    private val deviceListListener = KdeConnect.DeviceListChangedCallback {
        ThreadHelper.execute { refreshWatchList() }
    }

    @Synchronized
    private fun applyConfig() {
        val address = if (!isStopped && preferences.getBoolean(KEY_RELAY_ENABLED, false)) {
            RelayProtocol.parseAddress(preferences.getString(KEY_RELAY_SERVER, "") ?: "")
        } else {
            null
        }
        if (address != null) {
            connection.start(address)
        } else {
            connection.stop()
            visibleDevices.values.forEach { it.disconnect() }
        }
    }

    override fun onStart() {
        isStopped = false
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        KdeConnect.getInstance().addDeviceListChangedCallback(LOG_TAG, deviceListListener)
        ThreadHelper.execute { applyConfig() }
    }

    override fun onStop() {
        isStopped = true
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        KdeConnect.getInstance().removeDeviceListChangedCallback(LOG_TAG)
        ThreadHelper.execute { applyConfig() }
    }

    override fun onNetworkChange(network: Network?) {
        if (!isStopped) {
            connection.retryNow()
        }
    }

    override fun onConnectionLost(link: BaseLink) {
        if (visibleDevices.remove(link.deviceId, link)) {
            super.onConnectionLost(link)
            // The relay tells us again if the paired device is still online
            lastWatchList = null
            ThreadHelper.execute { refreshWatchList() }
        }
    }

    override val name: String = "RelayLinkProvider"
    override val priority: Int = 5

    /** Asks the relay for a link to the device with this key or fingerprint. Returns false if not registered. */
    fun connectTo(target: RelayProtocol.Target): Boolean {
        if (target.matches(SslHelper.certificate)) {
            return false
        }
        if (connection.status != RelayConnection.Status.Registered) {
            return false
        }
        ThreadHelper.execute {
            val requestId = connection.requestTunnel(target, "link") ?: return@execute
            pendingLinkRequests[requestId] = target
        }
        return true
    }

    private fun refreshWatchList() {
        if (connection.status != RelayConnection.Status.Registered) {
            return
        }
        val fingerprints = TrustedDevices.getAllTrustedDevices(context).mapNotNull {
            try {
                RelayProtocol.fingerprint(TrustedDevices.getDeviceCertificate(context, it))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "No certificate for trusted device $it", e)
                null
            }
        }.sorted()
        if (fingerprints != lastWatchList) {
            lastWatchList = fingerprints
            connection.setWatchList(fingerprints)
        }
    }

    /** Blocks until the relay hands out a payload tunnel token for the device with this certificate */
    @WorkerThread
    @Throws(IOException::class)
    fun requestPayloadTunnel(peerCertificate: Certificate): String? {
        val future = CompletableFuture<String>()
        val requestId = synchronized(pendingPayloadRequests) {
            val id = connection.requestTunnel(RelayProtocol.Target.forCertificate(peerCertificate), "payload") ?: return null
            pendingPayloadRequests[id] = future
            id
        }
        return try {
            future.get(TUNNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message ?: "Payload tunnel request failed", e)
        } catch (e: TimeoutException) {
            throw IOException("Relay didn't answer the payload tunnel request", e)
        } finally {
            pendingPayloadRequests.remove(requestId)
        }
    }

    /** Attaches to a relay tunnel and runs end-to-end TLS, returning the socket once the peer matches the pin */
    @WorkerThread
    @Throws(IOException::class)
    fun openTunnel(token: String, role: Role, pin: RelayProtocol.Target): SSLSocket {
        val address = connection.address ?: throw IOException("No relay server configured")
        return openTunnel(sslContext, address, token, role, pin)
    }

    @WorkerThread
    private fun linkTunnel(token: String, role: Role, pin: RelayProtocol.Target) {
        if (pendingTunnels.incrementAndGet() > MAX_PENDING_TUNNELS) {
            pendingTunnels.decrementAndGet()
            Log.w(LOG_TAG, "Too many pending tunnels, ignoring")
            return
        }
        var socket: SSLSocket? = null
        try {
            socket = openTunnel(token, role, pin)
            val peerCertificate = socket.session.peerCertificates[0]
            val deviceInfo = exchangeIdentity(socket, peerCertificate) ?: run {
                socket.closeSafe()
                return
            }
            addOrUpdateLink(socket, deviceInfo)
            socket = null
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Relay tunnel failed: ${e.message}")
        } catch (e: SerializationException) {
            Log.w(LOG_TAG, "Invalid identity packet over relay", e)
        } finally {
            socket?.closeSafe()
            pendingTunnels.decrementAndGet()
        }
    }

    @Throws(IOException::class, SerializationException::class)
    private fun exchangeIdentity(socket: SSLSocket, peerCertificate: Certificate): DeviceInfo? {
        val myIdentity = DeviceHelper.getDeviceInfo(context).toIdentityPacket()
        socket.outputStream.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        socket.soTimeout = IDENTITY_TIMEOUT_MS
        val identity = NetworkPacket.unserialize(readLineBounded(socket.inputStream, MAX_IDENTITY_PACKET_SIZE))
        if (!DeviceInfo.isValidIdentityPacket(identity)) {
            Log.w(LOG_TAG, "Invalid identity packet")
            return null
        }
        val deviceId = identity.getString("deviceId")
        val certDeviceId = SslHelper.getCommonNameFromCertificate(peerCertificate as X509Certificate)
        if (deviceId != certDeviceId || deviceId == DeviceHelper.getDeviceId(context)) {
            Log.w(LOG_TAG, "Device id in identity doesn't match the certificate: $deviceId vs $certDeviceId")
            return null
        }
        val protocolVersion = identity.getInt("protocolVersion")
        if (protocolVersion < 8) {
            Log.w(LOG_TAG, "Ignoring device using too old protocol version $protocolVersion")
            return null
        }
        if (TrustedDevices.isTrustedDevice(context, deviceId)) {
            val stored = if (TrustedDevices.isCertificateStored(context, deviceId)) TrustedDevices.getDeviceCertificate(context, deviceId) else null
            if (!RelaySsl.isAcceptableForTrustedDevice(stored, peerCertificate)) {
                Log.w(LOG_TAG, "Paired device presented a different certificate, rejecting $deviceId")
                return null
            }
            if (DeviceInfo.loadProtocolVersionFromSettings(context, deviceId) > protocolVersion) {
                Log.w(LOG_TAG, "Refusing protocol downgrade from $deviceId")
                return null
            }
        }
        return DeviceInfo.fromIdentityPacketAndCert(identity, peerCertificate)
    }

    @Synchronized
    private fun addOrUpdateLink(socket: SSLSocket, deviceInfo: DeviceInfo) {
        val existing = visibleDevices[deviceInfo.id]
        if (existing != null) {
            if (existing.deviceInfo.certificate != deviceInfo.certificate) {
                Log.e(LOG_TAG, "Link replacement with a different certificate, aborting")
                socket.closeSafe()
                return
            }
            existing.reset(socket, deviceInfo)
            onDeviceInfoUpdated(deviceInfo)
            return
        }
        if (!TrustedDevices.isTrustedDevice(context, deviceInfo.id) && visibleDevices.size >= MAX_UNPAIRED_CONNECTIONS) {
            Log.w(LOG_TAG, "Too many devices, ignoring ${deviceInfo.id}")
            socket.closeSafe()
            return
        }
        val link = RelayLink(context, this, deviceInfo, socket)
        visibleDevices[deviceInfo.id] = link
        onConnectionReceived(link)
        link.startListening()
    }

    companion object {
        private const val LOG_TAG = "KDE/RelayLinkProvider"
        const val KEY_RELAY_ENABLED = "relay_enabled"
        const val KEY_RELAY_SERVER = "relay_server"
        private const val MAX_IDENTITY_PACKET_SIZE = 1024 * 512
        private const val IDENTITY_TIMEOUT_MS = 10000
        private const val MAX_UNPAIRED_CONNECTIONS = 42
        private const val MAX_PENDING_TUNNELS = 8
        private const val TUNNEL_TIMEOUT_MS = 30000L

        /** Attaches to a relay tunnel and runs end-to-end TLS, returning the socket once the peer matches the pin */
        @Throws(IOException::class)
        fun openTunnel(sslContext: SSLContext, address: RelayProtocol.Address, token: String, role: Role, pin: RelayProtocol.Target): SSLSocket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(address.host, address.port), TUNNEL_TIMEOUT_MS.toInt())
                plain.keepAlive = true
                plain.soTimeout = TUNNEL_TIMEOUT_MS.toInt()
                plain.getOutputStream().write(RelayProtocol.encode(RelayProtocol.message("attach", "token" to token)))
                plain.getOutputStream().flush()
                val tls = sslContext.socketFactory.createSocket(plain, address.host, address.port, true) as SSLSocket
                if (role == Role.TlsServer) {
                    tls.useClientMode = false
                    tls.needClientAuth = true
                } else {
                    tls.useClientMode = true
                }
                tls.startHandshake()
                RelaySsl.verifyPin(tls, pin)
                return tls
            } catch (e: IOException) {
                plain.closeSafe()
                throw e
            }
        }
    }
}
