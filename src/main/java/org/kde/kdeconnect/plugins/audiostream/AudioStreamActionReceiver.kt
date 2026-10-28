/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Team <kdeconnect@kde.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.audiostream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.kde.kdeconnect.KdeConnect

/** Deny and stop buttons of the audio stream notifications */
class AudioStreamActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DENY -> {
                val plugin = KdeConnect.getInstance().getDevicePlugin<AudioStreamPlugin>(intent.getStringExtra(EXTRA_DEVICE_ID)) ?: return
                plugin.controller.consentDenied(intent.getStringExtra(EXTRA_STREAM_ID) ?: return)
            }
            ACTION_STOP_ALL -> {
                for (device in KdeConnect.getInstance().devices.values) {
                    device.getPlugin(AudioStreamPlugin::class.java)?.controller?.stopAll()
                }
            }
        }
    }

    companion object {
        private const val ACTION_DENY = "org.kde.kdeconnect.plugins.audiostream.DENY"
        private const val ACTION_STOP_ALL = "org.kde.kdeconnect.plugins.audiostream.STOP_ALL"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_STREAM_ID = "streamId"

        fun denyIntent(context: Context, deviceId: String, streamId: String) =
            Intent(context, AudioStreamActionReceiver::class.java).apply {
                action = ACTION_DENY
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_STREAM_ID, streamId)
            }

        fun stopAllIntent(context: Context) =
            Intent(context, AudioStreamActionReceiver::class.java).apply { action = ACTION_STOP_ALL }
    }
}
