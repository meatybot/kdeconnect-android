/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.kde.kdeconnect.backends.relay

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * Relay protocol helpers shared by the relay backend. Must produce the same results as
 * core/backends/relay/relayprotocol.cpp on desktop, see docs/specs/relay.md
 */
object RelayProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 1765
    const val MAX_LINE_SIZE = 8192
    const val KEY_LENGTH = 20 // base32 characters, 100 bits

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val json = Json { ignoreUnknownKeys = true }

    /** SHA-256 over the DER certificate, 64 lowercase hex characters */
    fun fingerprint(certificate: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** First 100 bits of SHA-256 over the DER SubjectPublicKeyInfo, base32, grouped as XXXX-XXXX-XXXX-XXXX-XXXX */
    fun relayKey(certificate: Certificate): String = relayKeyFromPublicKeyDer(certificate.publicKey.encoded)

    fun relayKeyFromPublicKeyDer(spkiDer: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(spkiDer)
        val key = StringBuilder()
        for (i in 0 until KEY_LENGTH) {
            // Read 5 bits starting at bit i * 5, most significant bit first
            val bit = i * 5
            val byte = bit / 8
            val shift = bit % 8
            val pair = ((hash[byte].toInt() and 0xff) shl 8) or (hash[byte + 1].toInt() and 0xff)
            val value = (pair shr (11 - shift)) and 0x1f
            if (i > 0 && i % 4 == 0) {
                key.append('-')
            }
            key.append(BASE32_ALPHABET[value])
        }
        return key.toString()
    }

    sealed interface Target {
        val value: String

        /** Normalized grouped relay key */
        data class Key(override val value: String) : Target

        /** Normalized lowercase hex fingerprint */
        data class Fingerprint(override val value: String) : Target

        fun matches(certificate: Certificate): Boolean = when (this) {
            is Key -> relayKey(certificate) == value
            is Fingerprint -> fingerprint(certificate) == value
        }

        companion object {
            fun forCertificate(certificate: Certificate): Target = Fingerprint(fingerprint(certificate))
        }
    }

    /** Accepts a relay key or a fingerprint in any case, ignoring '-', ':', '=' and whitespace. Returns null if invalid. */
    fun parseTarget(input: String): Target? {
        val compact = StringBuilder()
        for (c in input) {
            if (c == '-' || c == ':' || c == '=' || c.isWhitespace()) {
                continue
            }
            if (c.code > 127) {
                return null
            }
            compact.append(c.uppercaseChar())
        }
        return when (compact.length) {
            64 -> if (compact.all { it in '0'..'9' || it in 'A'..'F' }) {
                Target.Fingerprint(compact.toString().lowercase())
            } else {
                null
            }
            KEY_LENGTH -> if (compact.all { it in 'A'..'Z' || it in '2'..'7' }) {
                Target.Key(compact.chunked(4).joinToString("-"))
            } else {
                null
            }
            else -> null
        }
    }

    /** One JSON object per line */
    fun encode(message: JsonObject): ByteArray = (json.encodeToString(JsonObject.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)

    fun message(type: String, vararg fields: Pair<String, Any>): JsonObject {
        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        map["type"] = JsonPrimitive(type)
        for ((key, value) in fields) {
            map[key] = when (value) {
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is kotlinx.serialization.json.JsonElement -> value
                else -> throw IllegalArgumentException("Unsupported value for $key")
            }
        }
        return JsonObject(map)
    }

    /** Returns null for oversized lines, invalid JSON or a missing "type" */
    fun decode(line: String): JsonObject? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_SIZE) {
            return null
        }
        val element = try {
            json.parseToJsonElement(line.trim())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        val type = obj["type"] as? JsonPrimitive ?: return null
        if (!type.isString) {
            return null
        }
        return obj
    }

    fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    /** Messages a relay server sends on the control connection */
    sealed interface ServerMessage {
        data object StartTls : ServerMessage
        data class Welcome(val version: Int, val key: String, val fingerprint: String) : ServerMessage
        data class Tunnel(val requestId: String, val token: String, val purpose: String, val peerFingerprint: String) : ServerMessage
        data class Incoming(val token: String, val peerFingerprint: String, val peerKey: String) : ServerMessage
        data class PeerOnline(val peerFingerprint: String, val peerKey: String) : ServerMessage
        data class Error(val requestId: String, val code: String, val message: String) : ServerMessage
        data object Pong : ServerMessage
    }

    /** Returns null for unknown types or messages missing required fields */
    fun parseServerMessage(message: JsonObject): ServerMessage? {
        fun fingerprintField(key: String): String? = (parseTarget(message.string(key)) as? Target.Fingerprint)?.value
        return when (message.string("type")) {
            "starttls" -> ServerMessage.StartTls
            "welcome" -> ServerMessage.Welcome(
                (message["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null,
                message.string("key"),
                message.string("fingerprint"),
            )
            "tunnel" -> ServerMessage.Tunnel(
                message.string("requestId"),
                message.string("token").ifEmpty { return null },
                message.string("purpose"),
                fingerprintField("peerFingerprint") ?: return null,
            )
            "incoming" -> ServerMessage.Incoming(
                message.string("token").ifEmpty { return null },
                fingerprintField("peerFingerprint") ?: return null,
                message.string("peerKey"),
            )
            "peer_online" -> ServerMessage.PeerOnline(
                fingerprintField("peerFingerprint") ?: return null,
                message.string("peerKey"),
            )
            "error" -> ServerMessage.Error(message.string("requestId"), message.string("code"), message.string("message"))
            "pong" -> ServerMessage.Pong
            else -> null
        }
    }

    data class Address(val host: String, val port: Int)

    /** "host", "host:port", "[v6addr]:port" or "v6addr". Returns null if invalid. */
    fun parseAddress(address: String): Address? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        var host = trimmed
        var portPart: String? = null
        var ipv6 = false
        if (trimmed.startsWith("[")) {
            ipv6 = true
            val end = trimmed.indexOf(']')
            if (end < 0) {
                return null
            }
            host = trimmed.substring(1, end)
            val rest = trimmed.substring(end + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) {
                    return null
                }
                portPart = rest.substring(1)
            }
        } else if (trimmed.count { it == ':' } == 1) {
            val colon = trimmed.indexOf(':')
            host = trimmed.substring(0, colon)
            portPart = trimmed.substring(colon + 1)
        } else if (trimmed.contains(':')) {
            ipv6 = true
        }
        // Host names and IPv4: letters, digits, '-' and '.'. IPv6: hex digits, ':' and '.'
        if (host.isEmpty()) {
            return null
        }
        val validHost = if (ipv6) {
            host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        } else {
            host.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '.' }
        }
        if (!validHost) {
            return null
        }
        val port = if (portPart != null) {
            portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            DEFAULT_PORT
        }
        return Address(host, port)
    }
}
