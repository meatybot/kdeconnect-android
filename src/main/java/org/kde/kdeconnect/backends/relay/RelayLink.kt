/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.relay

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.LineTooLongException
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.readLineBounded
import java.io.BufferedInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * A device link over an end-to-end TLS session spliced by a relay server. Payloads use extra relay tunnels.
 */
class RelayLink(
    context: Context,
    private val provider: RelayLinkProvider,
    @Volatile override var deviceInfo: DeviceInfo,
    @Volatile private var socket: SSLSocket,
) : BaseLink(context, provider) {
    override val name = "RelayLink"

    // Finite payloads are sent one at a time, like the LAN backend does per composite job
    private val finitePayloadLock = Object()

    override fun disconnect() {
        socket.closeSafe()
    }

    fun reset(newSocket: SSLSocket, newDeviceInfo: DeviceInfo) {
        deviceInfo = newDeviceInfo
        val oldSocket = socket
        socket = newSocket
        oldSocket.closeSafe() // Ends the old read thread
        startListening()
    }

    fun startListening() {
        val currentSocket = socket
        ThreadHelper.execute {
            try {
                currentSocket.soTimeout = 0
                val stream = BufferedInputStream(currentSocket.inputStream)
                while (true) {
                    val line = try {
                        readLineBounded(stream, MAX_PACKET_SIZE)
                    } catch (_: LineTooLongException) {
                        continue
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (line.isBlank()) {
                        continue
                    }
                    val np = try {
                        NetworkPacket.unserialize(line)
                    } catch (e: kotlinx.serialization.SerializationException) {
                        Log.w(LOG_TAG, "Ignoring malformed packet", e)
                        continue
                    }
                    receivedNetworkPacket(np)
                }
            } catch (e: Exception) {
                Log.i(LOG_TAG, "Relay link closed: ${e.message}")
                currentSocket.closeSafe()
                if (currentSocket === socket) {
                    linkProvider.onConnectionLost(this@RelayLink)
                }
            }
        }
    }

    private fun receivedNetworkPacket(np: NetworkPacket) {
        if (np.hasPayloadTransferInfo()) {
            val token = (np.payloadTransferInfo["relayToken"] as? JsonPrimitive)?.contentOrNull
            if (token.isNullOrEmpty()) {
                Log.w(LOG_TAG, "Payload packet without relay token, ignoring payload")
                np.payload = null
            } else {
                try {
                    val payloadSocket = provider.openTunnel(token, RelayLinkProvider.Role.TlsClient, RelayProtocol.Target.forCertificate(deviceInfo.certificate))
                    np.payload = NetworkPacket.Payload(payloadSocket, np.payloadSize)
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "Couldn't open payload tunnel", e)
                    np.payload = null
                }
            }
        }
        packetReceived(np)
    }

    @WorkerThread
    override fun sendPacket(np: NetworkPacket, callback: Device.SendPacketStatusCallback, sendPayloadFromSameThread: Boolean): Boolean {
        var payloadTransferStarted = false
        try {
            if (!np.hasPayload()) {
                writePacket(np)
                callback.onSuccess()
                return true
            }

            val finite = np.payloadSize >= 0
            val transfer = {
                if (finite) {
                    synchronized(finitePayloadLock) { sendWithPayload(np, callback) }
                } else {
                    sendWithPayload(np, callback)
                }
            }
            payloadTransferStarted = true
            if (sendPayloadFromSameThread) {
                transfer()
            } else {
                ThreadHelper.execute {
                    try {
                        transfer()
                    } catch (e: IOException) {
                        Log.e(LOG_TAG, "Async payload transfer failed for ${np.type}", e)
                        callback.onFailure(e)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            callback.onFailure(e)
            return false
        } finally {
            if (!payloadTransferStarted) {
                np.payload?.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun writePacket(np: NetworkPacket) {
        try {
            val out = socket.outputStream
            synchronized(this) {
                out.write(np.serialize().toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: IOException) {
            disconnect() // Main socket is broken
            throw e
        }
    }

    @Throws(IOException::class)
    private fun sendWithPayload(np: NetworkPacket, callback: Device.SendPacketStatusCallback) {
        val payload = np.payload!!
        var payloadSocket: SSLSocket? = null
        try {
            val token = provider.requestPayloadTunnel(deviceInfo.certificate)
                ?: throw IOException("Relay refused the payload tunnel")
            np.payloadTransferInfo = buildJsonObject { put("relayToken", JsonPrimitive(token)) }
            writePacket(np)
            if (np.isCanceled) {
                return
            }
            payloadSocket = provider.openTunnel(token, RelayLinkProvider.Role.TlsServer, RelayProtocol.Target.forCertificate(deviceInfo.certificate))
            val input = payload.inputStream ?: throw IOException("Payload for ${np.type} has no InputStream")
            val output = payloadSocket.outputStream
            val buffer = ByteArray(PAYLOAD_BUFFER_SIZE)
            val size = np.payloadSize
            var progress = 0L
            var lastUpdate = -1L
            while (!np.isCanceled) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                output.write(buffer, 0, read)
                progress += read
                if (size > 0 && lastUpdate + 500 < SystemClock.elapsedRealtime()) {
                    callback.onPayloadProgressChanged((100 * progress / size).toInt())
                    lastUpdate = SystemClock.elapsedRealtime()
                }
            }
            output.flush()
            Log.i(LOG_TAG, "Finished sending payload ($progress bytes)")
            if (!np.isCanceled) {
                callback.onSuccess()
            }
        } finally {
            payloadSocket?.closeSafe()
            payload.close()
        }
    }

    companion object {
        private const val LOG_TAG = "KDE/RelayLink"
        const val MAX_PACKET_SIZE: Int = 32 * 1024 * 1024
        const val PAYLOAD_BUFFER_SIZE: Int = 256 * 1024
    }
}
