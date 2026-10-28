/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.backends.relay.RelayProtocol.Target

class ConnectTargetsTest {
    private val a = TestCertificates.generate("aaaaaaaa_aaaa_aaaa_aaaa_aaaaaaaaaaaa")
    private val b = TestCertificates.generate("bbbbbbbb_bbbb_bbbb_bbbb_bbbbbbbbbbbb")

    @Test
    fun matchesByKeyOrFingerprintOnce() {
        val targets = ConnectTargets()
        assertTrue(targets.isEmpty())
        targets.add(Target.Key(RelayProtocol.relayKey(a.certificate)))
        targets.add(Target.Key(RelayProtocol.relayKey(a.certificate))) // Replaces, doesn't duplicate
        targets.add(Target.forCertificate(b.certificate))
        assertFalse(targets.take(TestCertificates.generate("cccccccc_cccc_cccc_cccc_cccccccccccc").certificate))
        assertTrue(targets.take(a.certificate))
        assertFalse(targets.take(a.certificate))
        assertTrue(targets.take(b.certificate))
        assertTrue(targets.isEmpty())
    }

    @Test
    fun expires() {
        var now = 1000L
        val targets = ConnectTargets(expiryMs = 100, clock = { now })
        targets.add(Target.forCertificate(a.certificate))
        now += 99
        assertFalse(targets.isEmpty())
        now += 1
        assertFalse(targets.take(a.certificate))
        assertTrue(targets.isEmpty())
    }
}
