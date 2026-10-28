/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.receivenotifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService
import androidx.core.graphics.scale
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class ReceiveNotificationsPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_receive_notifications)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_receive_notifications_desc)

    override val isEnabledByDefault: Boolean = false

    // Currently shown notifications by remote id, needed to re-post them after a reply
    private val shown = HashMap<String, ReceivedNotification>()
    private val icons = HashMap<String, Bitmap>()
    // Remote ids of the shown notifications of each group
    private val groups = HashMap<String, MutableSet<String>>()

    override fun onCreate() {
        // request all existing notifications
        val np = NetworkPacket(PACKET_TYPE_NOTIFICATION_REQUEST)
        np["request"] = true
        device.sendPacket(np)
    }

    override fun onDestroy() {
        val notificationManager = context.getSystemService<NotificationManager>() ?: return
        for (id in shown.keys.toList()) {
            cancel(notificationManager, id)
        }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val notificationManager = context.getSystemService<NotificationManager>() ?: return true

        val id = np.getStringOrNull("id")
        if (id == null) {
            Log.e(TAG, "Received notification packet lacks an id")
            return true
        }

        if (np.getBoolean("isCancel")) {
            cancel(notificationManager, id)
            return true
        }

        val received = ReceivedNotification.fromPacket(np)
        if (received == null) {
            Log.e(TAG, "Received notification packet lacks properties")
            return true
        }

        val payload = np.payload
        if (payload != null && payload.payloadSize != 0L) {
            val icon = decodeIcon(payload)
            if (icon != null) {
                icons[id] = icon
            }
        }

        post(notificationManager, received, null)
        return true
    }

    private fun decodeIcon(payload: NetworkPacket.Payload): Bitmap? {
        val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        var icon = payload.inputStream?.let { BitmapFactory.decodeStream(it) }
        payload.close()
        if (icon != null && (icon.width > width || icon.height > height)) {
            // older API levels don't scale notification icons automatically, therefore:
            icon = icon.scale(width, height, false)
        }
        return icon
    }

    private fun tag(id: String) = "kdeconnectId:${device.deviceId}:$id"

    private fun intId(id: String) = id.toIntOrNull() ?: id.hashCode()

    private fun groupKey(received: ReceivedNotification) = "kdeconnect:${device.deviceId}:${received.appName}"

    private fun broadcast(action: String, id: String, discriminator: String, mutable: Boolean, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, ReceiveNotificationsReceiver::class.java).apply {
            this.action = action
            // Unique data so every button of every notification gets its own PendingIntent
            data = Uri.Builder().scheme("kdeconnect-notification").authority(device.deviceId)
                .appendPath(id).appendPath(discriminator).build()
            putExtra(ReceiveNotificationsReceiver.EXTRA_DEVICE_ID, device.deviceId)
            putExtra(ReceiveNotificationsReceiver.EXTRA_NOTIFICATION_ID, id)
            extras()
        }
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE // RemoteInput needs to fill in the reply
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private fun post(notificationManager: NotificationManager, received: ReceivedNotification, replyHistory: CharSequence?) {
        val id = received.id
        val channel = if (received.urgency == ReceivedNotification.URGENCY_CRITICAL) {
            NotificationHelper.Channels.HIGHPRIORITY
        } else {
            NotificationHelper.Channels.RECEIVENOTIFICATION
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setContentTitle(received.contentTitle)
            .setContentText(received.contentText)
            .setTicker(received.ticker)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(icons[id])
            .setAutoCancel(true)
            .setLocalOnly(true) // to avoid bouncing the notification back to other kdeconnect nodes
            .setDefaults(Notification.DEFAULT_ALL)
            .setSilent(received.silent || replyHistory != null)
            .setOnlyAlertOnce(received.progress != null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(received.contentText))
            .setPriority(received.priority)
            .setGroup(groupKey(received))

        if (received.title != null) {
            builder.setSubText(received.appName)
        }
        received.category?.let { builder.setCategory(it) }
        received.time?.let { builder.setWhen(it).setShowWhen(true) }
        received.progress?.let { builder.setProgress(100, it, false) }
        if (replyHistory != null) {
            builder.setRemoteInputHistory(arrayOf(replyHistory))
        }

        if (received.hasDefaultAction) {
            builder.setContentIntent(broadcast(ReceiveNotificationsReceiver.ACTION_INVOKE, id, "default", false) {
                putExtra(ReceiveNotificationsReceiver.EXTRA_ACTION_ID, ReceivedNotification.DEFAULT_ACTION)
            })
        } else {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        if (received.isClearable) {
            builder.setDeleteIntent(broadcast(ReceiveNotificationsReceiver.ACTION_DISMISS, id, "dismiss", false) {})
        } else {
            builder.setOngoing(true)
        }

        val replyId = received.requestReplyId
        if (replyId != null) {
            val remoteInput = RemoteInput.Builder(ReceiveNotificationsReceiver.KEY_REPLY)
                .setLabel(context.getString(R.string.receive_notifications_reply))
                .build()
            val replyIntent = broadcast(ReceiveNotificationsReceiver.ACTION_REPLY, id, "reply", true) {
                putExtra(ReceiveNotificationsReceiver.EXTRA_REPLY_ID, replyId)
            }
            val action = NotificationCompat.Action.Builder(R.drawable.ic_baseline_send_24, context.getString(R.string.receive_notifications_reply), replyIntent)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()
            builder.addAction(action)
        }

        received.actions.forEachIndexed { index, action ->
            val pendingIntent = broadcast(ReceiveNotificationsReceiver.ACTION_INVOKE, id, "action$index", false) {
                putExtra(ReceiveNotificationsReceiver.EXTRA_ACTION_LABEL, action.label)
                putExtra(ReceiveNotificationsReceiver.EXTRA_ACTION_ID, action.id)
            }
            builder.addAction(0, action.label, pendingIntent)
        }

        shown[id] = received
        notificationManager.notify(tag(id), intId(id), builder.build())
        addToGroup(notificationManager, received)
    }

    private fun addToGroup(notificationManager: NotificationManager, received: ReceivedNotification) {
        val key = groupKey(received)
        val members = groups.getOrPut(key) { mutableSetOf() }
        members.add(received.id)
        // A summary makes Android bundle the notifications of one app of this device
        if (members.size >= 2) {
            val summary = NotificationCompat.Builder(context, NotificationHelper.Channels.RECEIVENOTIFICATION)
                .setContentTitle(received.appName)
                .setSubText(device.name)
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(key)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setLocalOnly(true)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(key, GROUP_SUMMARY_ID, summary)
        }
    }

    private fun cancel(notificationManager: NotificationManager, id: String) {
        notificationManager.cancel(tag(id), intId(id))
        icons.remove(id)
        val received = shown.remove(id) ?: return
        val key = groupKey(received)
        val members = groups[key] ?: return
        members.remove(id)
        if (members.size < 2) {
            notificationManager.cancel(key, GROUP_SUMMARY_ID)
        }
        if (members.isEmpty()) {
            groups.remove(key)
        }
    }

    /** Handles the PendingIntents of notifications posted by this plugin. */
    fun handleIntent(intent: Intent) {
        val id = intent.getStringExtra(ReceiveNotificationsReceiver.EXTRA_NOTIFICATION_ID) ?: return
        when (intent.action) {
            ReceiveNotificationsReceiver.ACTION_INVOKE -> {
                val actionId = intent.getStringExtra(ReceiveNotificationsReceiver.EXTRA_ACTION_ID)
                val label = intent.getStringExtra(ReceiveNotificationsReceiver.EXTRA_ACTION_LABEL) ?: ""
                sendAction(id, label, actionId)
            }
            ReceiveNotificationsReceiver.ACTION_REPLY -> {
                val replyId = intent.getStringExtra(ReceiveNotificationsReceiver.EXTRA_REPLY_ID) ?: return
                val message = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ReceiveNotificationsReceiver.KEY_REPLY) ?: return
                sendReply(replyId, message.toString())
                // Re-post to stop the progress indicator Android shows after sending a reply
                val received = shown[id] ?: return
                val notificationManager = context.getSystemService<NotificationManager>() ?: return
                post(notificationManager, received, message)
            }
            ReceiveNotificationsReceiver.ACTION_DISMISS -> {
                val received = shown[id]
                val notificationManager = context.getSystemService<NotificationManager>()
                if (notificationManager != null) {
                    cancel(notificationManager, id)
                }
                if (received?.isClearable != false) {
                    val np = NetworkPacket(PACKET_TYPE_NOTIFICATION_REQUEST)
                    np["cancel"] = id
                    device.sendPacket(np)
                }
            }
        }
    }

    private fun sendAction(key: String, label: String, actionId: String?) {
        val np = NetworkPacket(PACKET_TYPE_NOTIFICATION_ACTION)
        np["key"] = key
        np["action"] = label
        if (actionId != null) {
            np["actionId"] = actionId
        }
        device.sendPacket(np)
    }

    private fun sendReply(replyId: String, message: String) {
        val np = NetworkPacket(PACKET_TYPE_NOTIFICATION_REPLY)
        np["requestReplyId"] = replyId
        np["message"] = message
        device.sendPacket(np)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_NOTIFICATION)

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_NOTIFICATION_REQUEST,
        PACKET_TYPE_NOTIFICATION_ACTION,
        PACKET_TYPE_NOTIFICATION_REPLY,
    )

    override val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf()
    }

    override val permissionExplanation: Int = R.string.receive_notifications_permission_explanation

    companion object {
        private const val TAG = "KDE/ReceiveNotifications"
        private const val GROUP_SUMMARY_ID = 0
        const val PACKET_TYPE_NOTIFICATION = "kdeconnect.notification"
        const val PACKET_TYPE_NOTIFICATION_REQUEST = "kdeconnect.notification.request"
        const val PACKET_TYPE_NOTIFICATION_ACTION = "kdeconnect.notification.action"
        const val PACKET_TYPE_NOTIFICATION_REPLY = "kdeconnect.notification.reply"
    }
}
