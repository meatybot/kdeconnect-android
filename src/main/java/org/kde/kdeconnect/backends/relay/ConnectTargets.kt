/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import java.security.cert.Certificate

/**
 * Devices the user asked to connect to by relay key or certificate fingerprint. When a link whose
 * certificate matches appears (on any backend) the target is consumed and pairing gets requested.
 */
class ConnectTargets(
    private val expiryMs: Long = EXPIRY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val target: RelayProtocol.Target, val added: Long)

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun add(target: RelayProtocol.Target) {
        prune()
        entries.removeAll { it.target == target }
        entries.add(Entry(target, clock()))
    }

    /** Removes and returns true if a non-expired target matches the certificate */
    @Synchronized
    fun take(certificate: Certificate): Boolean {
        prune()
        val index = entries.indexOfFirst { it.target.matches(certificate) }
        if (index < 0) {
            return false
        }
        entries.removeAt(index)
        return true
    }

    @Synchronized
    fun isEmpty(): Boolean {
        prune()
        return entries.isEmpty()
    }

    private fun prune() {
        val now = clock()
        entries.removeAll { now - it.added >= expiryMs }
    }

    companion object {
        const val EXPIRY_MS: Long = 5 * 60 * 1000L
    }
}
