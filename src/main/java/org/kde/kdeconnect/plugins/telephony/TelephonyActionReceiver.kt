/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.kde.kdeconnect.KdeConnect

/**
 * Handles the Answer / Reject / Hang up actions of the notification shown for calls on a desktop modem
 */
class TelephonyActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CALL: String = "org.kde.kdeconnect.plugins.telephony.callAction"
        const val EXTRA_DEVICE_ID: String = "deviceId"
        const val EXTRA_CALL_ACTION: String = "callAction"
        const val EXTRA_AUDIO_ROUTE: String = "audioRoute"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CALL) {
            Log.d("TelephonyActionReceiver", "Unhandled Action received: ${intent.action}")
            return
        }
        val action = CallHandoff.Action.fromWire(intent.getStringExtra(EXTRA_CALL_ACTION)) ?: return
        val audioRoute = intent.getStringExtra(EXTRA_AUDIO_ROUTE)?.let { CallHandoff.AudioRoute.fromWire(it) }
        val plugin = KdeConnect.getInstance().getDevicePlugin<TelephonyPlugin>(intent.getStringExtra(EXTRA_DEVICE_ID)) ?: return
        plugin.sendCallAction(action, audioRoute)
    }
}
