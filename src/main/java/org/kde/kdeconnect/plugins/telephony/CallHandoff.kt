/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.telephony

import android.os.Build
import android.telephony.TelephonyManager
import org.kde.kdeconnect.NetworkPacket
import java.util.UUID

/**
 * Protocol logic of call handoff, see docs/specs/callhandoff.md
 */
object CallHandoff {
    const val PACKET_TYPE_TELEPHONY_REQUEST_ACTION = "kdeconnect.telephony.request_action"
    const val PACKET_TYPE_TELEPHONY_ACTION_STATUS = "kdeconnect.telephony.action_status"
    const val PACKET_TYPE_AUDIOSTREAM_REQUEST = "kdeconnect.audiostream.request"

    const val ERROR_INVALID_ACTION = "invalid_action"
    const val ERROR_UNSUPPORTED = "unsupported"
    const val ERROR_PERMISSION_DENIED = "permission_denied"
    const val ERROR_NO_CALL = "no_call"
    const val ERROR_FAILED = "failed"

    enum class Action(val wire: String) {
        ANSWER("answer"),
        REJECT("reject"),
        HANGUP("hangup");

        companion object {
            fun fromWire(value: String?): Action? = entries.firstOrNull { it.wire == value }
        }
    }

    enum class AudioRoute(val wire: String) {
        DEFAULT("default"),
        SPEAKER("speaker"),
        REMOTE("remote");

        companion object {
            /** Unknown or missing values mean DEFAULT */
            fun fromWire(value: String?): AudioRoute = entries.firstOrNull { it.wire == value } ?: DEFAULT
        }
    }

    data class Request(val action: Action?, val rawAction: String, val phoneNumber: String?, val audioRoute: AudioRoute)

    data class Result(val success: Boolean, val error: String? = null, val audioRoute: AudioRoute? = null)

    fun parseRequest(np: NetworkPacket): Request {
        val rawAction = np.getString("action")
        return Request(
            action = Action.fromWire(rawAction),
            rawAction = rawAction,
            phoneNumber = np.getStringOrNull("phoneNumber")?.takeIf { it.isNotEmpty() },
            audioRoute = AudioRoute.fromWire(np.getStringOrNull("audioRoute")),
        )
    }

    fun requestPacket(action: Action, phoneNumber: String?, audioRoute: AudioRoute? = null): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_TELEPHONY_REQUEST_ACTION)
        np["action"] = action.wire
        if (!phoneNumber.isNullOrEmpty()) np["phoneNumber"] = phoneNumber
        if (audioRoute != null) np["audioRoute"] = audioRoute.wire
        return np
    }

    /**
     * Runs a request on this phone. [canStreamCallAudio] tells whether the local audiostream
     * plugin can serve the "call" source to the peer, otherwise REMOTE falls back to DEFAULT.
     */
    fun execute(request: Request, controller: CallController, canStreamCallAudio: Boolean): Result {
        val action = request.action ?: return Result(false, ERROR_INVALID_ACTION)

        val minSdk = if (action == Action.ANSWER) Build.VERSION_CODES.O else Build.VERSION_CODES.P
        if (controller.sdkInt < minSdk) return Result(false, ERROR_UNSUPPORTED)
        if (!controller.hasAnswerPermission()) return Result(false, ERROR_PERMISSION_DENIED)

        val state = controller.callState()
        val hasSuitableCall = when (action) {
            Action.ANSWER, Action.REJECT -> state == TelephonyManager.CALL_STATE_RINGING
            Action.HANGUP -> state != TelephonyManager.CALL_STATE_IDLE
        }
        if (!hasSuitableCall) return Result(false, ERROR_NO_CALL)

        try {
            if (action == Action.ANSWER) {
                controller.acceptRingingCall()
            } else if (!controller.endCall()) {
                return Result(false, ERROR_FAILED)
            }
        } catch (e: SecurityException) {
            return Result(false, ERROR_PERMISSION_DENIED)
        } catch (e: RuntimeException) {
            return Result(false, ERROR_FAILED)
        }

        if (action != Action.ANSWER) return Result(true)

        // Android cannot capture the call itself: "remote" relies on the loudspeaker being picked
        // up by the microphone the audiostream plugin records from.
        val route = when (request.audioRoute) {
            AudioRoute.DEFAULT -> AudioRoute.DEFAULT
            AudioRoute.SPEAKER -> if (controller.setSpeakerphone(true)) AudioRoute.SPEAKER else AudioRoute.DEFAULT
            AudioRoute.REMOTE -> if (canStreamCallAudio && controller.setSpeakerphone(true)) AudioRoute.REMOTE else AudioRoute.DEFAULT
        }
        return Result(true, audioRoute = route)
    }

    fun statusPacket(request: Request, result: Result): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_TELEPHONY_ACTION_STATUS)
        np["action"] = request.rawAction
        np["success"] = result.success
        result.error?.let { np["error"] = it }
        result.audioRoute?.let { np["audioRoute"] = it.wire }
        request.phoneNumber?.let { np["phoneNumber"] = it }
        return np
    }

    fun peerAcceptsCallActions(peerIncomingCapabilities: Set<String>?): Boolean =
        peerIncomingCapabilities?.contains(PACKET_TYPE_TELEPHONY_REQUEST_ACTION) == true

    fun peerCanStreamAudio(peerIncomingCapabilities: Set<String>?): Boolean =
        peerIncomingCapabilities?.contains(PACKET_TYPE_AUDIOSTREAM_REQUEST) == true

    /** Whether a status received as controller means we should request the call audio from the peer */
    fun shouldRequestCallAudio(status: NetworkPacket, peerIncomingCapabilities: Set<String>?): Boolean =
        status.getBoolean("success") &&
            status.getString("action") == Action.ANSWER.wire &&
            status.getString("audioRoute") == AudioRoute.REMOTE.wire &&
            peerCanStreamAudio(peerIncomingCapabilities)

    fun newStreamId(): String = "call-" + UUID.randomUUID().toString()

    fun audioStartPacket(streamId: String): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_AUDIOSTREAM_REQUEST)
        np["action"] = "start"
        np["streamId"] = streamId
        np["source"] = "call"
        np["codec"] = "pcm_s16le"
        np["sampleRate"] = 48000
        np["channels"] = 1
        return np
    }

    fun audioStopPacket(streamId: String): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_AUDIOSTREAM_REQUEST)
        np["action"] = "stop"
        np["streamId"] = streamId
        return np
    }
}
