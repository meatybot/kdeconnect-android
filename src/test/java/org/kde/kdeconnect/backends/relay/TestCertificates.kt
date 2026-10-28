/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/** Self-signed device certificates like the ones SslHelper creates */
object TestCertificates {
    data class Identity(val deviceId: String, val keyPair: KeyPair, val certificate: X509Certificate)

    fun generate(deviceId: String): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val keyPair = generator.generateKeyPair()
        return Identity(deviceId, keyPair, certificate(keyPair, deviceId))
    }

    /** Same key pair, new certificate */
    fun reissue(identity: Identity, deviceId: String): Identity = Identity(deviceId, identity.keyPair, certificate(identity.keyPair, deviceId))

    private fun certificate(keyPair: KeyPair, deviceId: String): X509Certificate {
        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, deviceId)
            .addRDN(BCStyle.OU, "KDE Connect")
            .addRDN(BCStyle.O, "KDE")
            .build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date(now - 86400000L), Date(now + 86400000L * 365), name, keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

}
