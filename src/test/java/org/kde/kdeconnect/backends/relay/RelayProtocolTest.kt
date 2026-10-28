/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.backends.relay.RelayProtocol.ServerMessage
import org.kde.kdeconnect.backends.relay.RelayProtocol.Target
import org.kde.kdeconnect.backends.relay.RelayProtocol.string
import java.security.MessageDigest
import java.security.cert.CertificateFactory

class RelayProtocolTest {
    private val testVector = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.classLoader!!.getResourceAsStream("relay-test-vector.pem"))

    @Test
    fun sharedTestVectorMatchesDesktop() {
        // Same expected values as tests/relaytest.cpp in kdeconnect-kde, see docs/specs/relay.md
        assertEquals("fc1fb3d3d33b2342e45c74b1a613dcdfe5e1f029d668249f504952a9a8041e31", RelayProtocol.fingerprint(testVector))
        val spkiHash = MessageDigest.getInstance("SHA-256").digest(testVector.publicKey.encoded).joinToString("") { "%02x".format(it) }
        assertEquals("a2d2e306d0372fb596fd6f647a05d6b79054b5ee052ea58171efc3144d54fe8c", spkiHash)
        assertEquals("ULJO-GBWQ-G4X3-LFX5-N5SH", RelayProtocol.relayKey(testVector))
    }

    @Test
    fun keyDependsOnlyOnPublicKey() {
        val a = TestCertificates.generate("aaaaaaaa_aaaa_aaaa_aaaa_aaaaaaaaaaaa")
        val b = TestCertificates.generate("bbbbbbbb_bbbb_bbbb_bbbb_bbbbbbbbbbbb")
        val reissued = TestCertificates.reissue(a, "cccccccc_cccc_cccc_cccc_cccccccccccc")
        assertEquals(RelayProtocol.relayKey(a.certificate), RelayProtocol.relayKey(reissued.certificate))
        assertNotEquals(RelayProtocol.fingerprint(a.certificate), RelayProtocol.fingerprint(reissued.certificate))
        assertNotEquals(RelayProtocol.relayKey(a.certificate), RelayProtocol.relayKey(b.certificate))
        assertTrue(RelayProtocol.relayKey(a.certificate).matches(Regex("^([A-Z2-7]{4}-){4}[A-Z2-7]{4}$")))
    }

    @Test
    fun parseTarget() {
        assertEquals(Target.Key("ULJO-GBWQ-G4X3-LFX5-N5SH"), RelayProtocol.parseTarget(" uljo gbwq-g4x3=lfx5n5sh "))
        assertEquals(
            Target.Fingerprint("fc1fb3d3d33b2342e45c74b1a613dcdfe5e1f029d668249f504952a9a8041e31"),
            RelayProtocol.parseTarget("FC:1F:B3:D3:D3:3B:23:42:E4:5C:74:B1:A6:13:DC:DF:E5:E1:F0:29:D6:68:24:9F:50:49:52:A9:A8:04:1E:31"),
        )
        assertNull(RelayProtocol.parseTarget("ULJO-GBWQ-G4X3-LFX5-N5S1"))
        assertNull(RelayProtocol.parseTarget("ULJO-GBWQ-G4X3-LFX5"))
        assertNull(RelayProtocol.parseTarget("g".repeat(64)))
        assertNull(RelayProtocol.parseTarget(""))
        assertNull(RelayProtocol.parseTarget("ULJO-GBWQ-G4X3-LFX5-N5S\u00c4"))

        val target = RelayProtocol.parseTarget("ULJO-GBWQ-G4X3-LFX5-N5SH")!!
        assertTrue(target.matches(testVector))
        assertTrue(Target.forCertificate(testVector).matches(testVector))
        assertFalse(target.matches(TestCertificates.generate("dddddddd_dddd_dddd_dddd_dddddddddddd").certificate))
    }

    @Test
    fun encodeDecode() {
        val message = RelayProtocol.message("connect", "requestId" to "7", "target" to "x", "version" to 1)
        val bytes = RelayProtocol.encode(message)
        val line = String(bytes, Charsets.UTF_8)
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
        assertEquals(message, RelayProtocol.decode(line))
        assertEquals("7", RelayProtocol.decode(line)!!.string("requestId"))

        assertNull(RelayProtocol.decode("{\"target\":\"x\"}\n"))
        assertNull(RelayProtocol.decode("{\"type\":1}\n"))
        assertNull(RelayProtocol.decode("[1,2]\n"))
        assertNull(RelayProtocol.decode("{\"type\":\"x\"\n"))
        assertNull(RelayProtocol.decode("{\"type\":\"x\",\"pad\":\"" + "a".repeat(RelayProtocol.MAX_LINE_SIZE) + "\"}\n"))
    }

    @Test
    fun parseServerMessages() {
        val fp = "0a".repeat(32)
        assertEquals(ServerMessage.StartTls, RelayProtocol.parseServerMessage(RelayProtocol.message("starttls")))
        assertEquals(
            ServerMessage.Welcome(1, "KEY", fp),
            RelayProtocol.parseServerMessage(RelayProtocol.message("welcome", "version" to 1, "key" to "KEY", "fingerprint" to fp)),
        )
        assertEquals(
            ServerMessage.Tunnel("3", "tok", "payload", fp),
            RelayProtocol.parseServerMessage(RelayProtocol.message("tunnel", "requestId" to "3", "token" to "tok", "purpose" to "payload", "peerFingerprint" to fp.uppercase())),
        )
        assertEquals(
            ServerMessage.Incoming("tok", fp, "K"),
            RelayProtocol.parseServerMessage(RelayProtocol.message("incoming", "token" to "tok", "peerFingerprint" to fp, "peerKey" to "K")),
        )
        assertEquals(ServerMessage.PeerOnline(fp, "K"), RelayProtocol.parseServerMessage(RelayProtocol.message("peer_online", "peerFingerprint" to fp, "peerKey" to "K")))
        assertEquals(
            ServerMessage.Error("9", "not_found", "No such device"),
            RelayProtocol.parseServerMessage(RelayProtocol.message("error", "requestId" to "9", "code" to "not_found", "message" to "No such device")),
        )
        // Missing or malformed required fields
        assertNull(RelayProtocol.parseServerMessage(RelayProtocol.message("incoming", "token" to "tok", "peerFingerprint" to "nothex")))
        assertNull(RelayProtocol.parseServerMessage(RelayProtocol.message("tunnel", "requestId" to "3", "purpose" to "link", "peerFingerprint" to fp)))
        assertNull(RelayProtocol.parseServerMessage(RelayProtocol.message("welcome", "key" to "KEY")))
        assertNull(RelayProtocol.parseServerMessage(RelayProtocol.message("something_new")))
        // Arrays are encoded as JSON arrays
        val watch = RelayProtocol.message("watch", "fingerprints" to JsonArray(listOf(JsonPrimitive(fp))))
        assertEquals("{\"type\":\"watch\",\"fingerprints\":[\"$fp\"]}\n", String(RelayProtocol.encode(watch), Charsets.UTF_8))
    }

    @Test
    fun parseAddress() {
        assertEquals(RelayProtocol.Address("relay.example.org", 1765), RelayProtocol.parseAddress("relay.example.org"))
        assertEquals(RelayProtocol.Address("10.0.0.1", 4000), RelayProtocol.parseAddress(" 10.0.0.1:4000 "))
        assertEquals(RelayProtocol.Address("::1", 99), RelayProtocol.parseAddress("[::1]:99"))
        assertEquals(RelayProtocol.Address("fe80::1", 1765), RelayProtocol.parseAddress("fe80::1"))
        for (bad in listOf("host:0", "host:70000", "host:abc", ":1765", "", "[::1", "bad:address:here:x", "host name:1", "[relay.org]:1")) {
            assertNull(bad, RelayProtocol.parseAddress(bad))
        }
    }
}
