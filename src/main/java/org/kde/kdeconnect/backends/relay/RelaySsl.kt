/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object RelaySsl {
    // Device certificates are self-signed: chains are not verified, the peer certificate is pinned instead
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val acceptAll: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) = Unit
        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) = Unit
    })

    /** TLS context presenting our device certificate. Peers must be checked with [verifyPin]. */
    fun createContext(certificate: Certificate, privateKey: PrivateKey): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("key", privateKey, CharArray(0), arrayOf(certificate))
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, CharArray(0))
        // Same TLS version as the LAN backend (SslHelper)
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(keyManagerFactory.keyManagers, acceptAll, SecureRandom())
        return context
    }

    sealed interface PinResult {
        data object Match : PinResult
        data object NoCertificate : PinResult
        data object Mismatch : PinResult
    }

    /** Checks the peer certificate against the key or fingerprint we expect to talk to */
    fun checkPin(pin: RelayProtocol.Target, peerCertificate: Certificate?): PinResult = when {
        peerCertificate == null -> PinResult.NoCertificate
        pin.matches(peerCertificate) -> PinResult.Match
        else -> PinResult.Mismatch
    }

    /** A paired device must present exactly the certificate stored when pairing */
    fun isAcceptableForTrustedDevice(storedCertificate: Certificate?, peerCertificate: Certificate): Boolean =
        storedCertificate != null && storedCertificate.encoded.contentEquals(peerCertificate.encoded)

    /** Returns the verified peer certificate, or throws after closing the socket */
    @Throws(SSLPeerUnverifiedException::class)
    fun verifyPin(socket: SSLSocket, pin: RelayProtocol.Target): Certificate {
        val peer = try {
            socket.session.peerCertificates.firstOrNull()
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        when (checkPin(pin, peer)) {
            PinResult.Match -> return peer!!
            PinResult.NoCertificate -> {
                socket.close()
                throw SSLPeerUnverifiedException("Peer did not present a certificate")
            }
            PinResult.Mismatch -> {
                socket.close()
                throw SSLPeerUnverifiedException("Peer certificate does not match the expected ${if (pin is RelayProtocol.Target.Key) "key" else "fingerprint"}")
            }
        }
    }
}
