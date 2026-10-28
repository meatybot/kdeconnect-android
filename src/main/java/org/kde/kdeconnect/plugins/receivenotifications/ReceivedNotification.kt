/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.receivenotifications

import androidx.core.app.NotificationCompat
import org.kde.kdeconnect.NetworkPacket

/** A notification received from the peer, parsed from a kdeconnect.notification packet. */
data class ReceivedNotification(
    val id: String,
    val appName: String,
    val ticker: String,
    val title: String?,
    val text: String?,
    val isClearable: Boolean,
    val silent: Boolean,
    val requestReplyId: String?,
    val actions: List<Action>,
    val hasDefaultAction: Boolean,
    val urgency: Int?,
    val progress: Int?,
    val category: String?,
    val time: Long?,
) {
    /** id is null for peers that only send labels, the label is then what identifies the action. */
    data class Action(val label: String, val id: String?)

    val contentTitle: String
        get() = title?.takeIf { it.isNotEmpty() } ?: appName

    val contentText: String
        get() = if (title.isNullOrEmpty()) ticker else text ?: ""

    val priority: Int
        get() = when (urgency) {
            URGENCY_LOW -> NotificationCompat.PRIORITY_LOW
            URGENCY_CRITICAL -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

    companion object {
        const val URGENCY_LOW = 0
        const val URGENCY_CRITICAL = 2
        const val DEFAULT_ACTION = "default"

        fun fromPacket(np: NetworkPacket): ReceivedNotification? {
            val id = np.getStringOrNull("id") ?: return null
            val appName = np.getStringOrNull("appName") ?: return null
            val ticker = np.getStringOrNull("ticker") ?: return null

            val labels = np.getStringList("actions", emptyList())
            // Ids are only usable if they match the labels one to one
            val ids = np.getStringList("actionIds")?.takeIf { it.size == labels.size }
            val actions = labels.mapIndexed { index, label -> Action(label, ids?.get(index)) }

            return ReceivedNotification(
                id = id,
                appName = appName,
                ticker = ticker,
                title = np.getStringOrNull("title"),
                text = np.getStringOrNull("text"),
                isClearable = np.getBoolean("isClearable", true),
                silent = np.getBoolean("silent", false),
                requestReplyId = np.getStringOrNull("requestReplyId")?.takeIf { it.isNotEmpty() },
                actions = actions,
                hasDefaultAction = np.getBoolean("hasDefaultAction", false),
                urgency = np.getIntOrNull("urgency")?.takeIf { it in URGENCY_LOW..URGENCY_CRITICAL },
                progress = np.getIntOrNull("progress")?.coerceIn(0, 100),
                category = np.getStringOrNull("category")?.let { categoryFor(it) },
                time = np.getStringOrNull("time")?.toLongOrNull(),
            )
        }

        // Maps freedesktop notification categories to the Android ones
        private fun categoryFor(category: String): String? = when (category.substringBefore('.')) {
            "email" -> NotificationCompat.CATEGORY_EMAIL
            "im" -> NotificationCompat.CATEGORY_MESSAGE
            "call" -> NotificationCompat.CATEGORY_CALL
            "transfer" -> NotificationCompat.CATEGORY_PROGRESS
            "network", "device" -> NotificationCompat.CATEGORY_STATUS
            else -> null
        }
    }
}
