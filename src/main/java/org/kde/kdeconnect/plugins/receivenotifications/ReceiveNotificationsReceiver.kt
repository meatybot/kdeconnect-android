/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.receivenotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.kde.kdeconnect.KdeConnect

/** Receives the buttons, inline replies and dismissals of notifications shown by ReceiveNotificationsPlugin. */
class ReceiveNotificationsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INVOKE = "org.kde.kdeconnect.plugins.receivenotifications.invoke"
        const val ACTION_REPLY = "org.kde.kdeconnect.plugins.receivenotifications.reply"
        const val ACTION_DISMISS = "org.kde.kdeconnect.plugins.receivenotifications.dismiss"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_ACTION_LABEL = "actionLabel"
        const val EXTRA_REPLY_ID = "replyId"
        const val KEY_REPLY = "kdeconnect_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val plugin = KdeConnect.getInstance().getDevicePlugin<ReceiveNotificationsPlugin>(deviceId)
        if (plugin == null) {
            Log.w("ReceiveNotifications", "Device $deviceId is not available, ignoring ${intent.action}")
            return
        }
        plugin.handleIntent(intent)
    }
}
