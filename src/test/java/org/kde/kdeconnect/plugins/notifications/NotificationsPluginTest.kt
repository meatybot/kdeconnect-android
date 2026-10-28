/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.notifications

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NotificationsPluginTest {
    private lateinit var context: Application
    private lateinit var plugin: NotificationsPlugin

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        plugin = NotificationsPlugin().apply { setContext(context, mockk<Device>(relaxed = true)) }
    }

    private fun action(title: String, requestCode: Int): Notification.Action {
        val intent = Intent("test.action.$title")
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as Icon?, title, pendingIntent).build()
    }

    private fun actionPacket(key: String, label: String, actionId: String?) = NetworkPacket("kdeconnect.notification.action").apply {
        this["key"] = key
        this["action"] = label
        if (actionId != null) {
            this["actionId"] = actionId
        }
    }

    private fun broadcastActions() = shadowOf(context).broadcastIntents.map { it.action }

    @Test
    fun legacyActionPacketTriggersMatchingAction() {
        plugin.pendingActions["key1"] = mutableListOf(action("Archive", 1), action("Delete", 2))
        plugin.onPacketReceived(actionPacket("key1", "Delete", null))
        assertEquals(listOf("test.action.Delete"), broadcastActions())
    }

    @Test
    fun actionIdFromNewerDesktopIsIgnored() {
        // Newer desktops always send the label too, ids are opaque to Android
        plugin.pendingActions["key1"] = mutableListOf(action("Archive", 1))
        plugin.onPacketReceived(actionPacket("key1", "Archive", "0"))
        assertEquals(listOf("test.action.Archive"), broadcastActions())
    }

    @Test
    fun unknownKeyOrLabelDoesNothing() {
        plugin.pendingActions["key1"] = mutableListOf(action("Archive", 1))
        plugin.onPacketReceived(actionPacket("key2", "Archive", null))
        plugin.onPacketReceived(actionPacket("key1", "Delete", null))
        assertTrue(broadcastActions().isEmpty())
    }

    @Test
    fun supportedPacketTypesUnchanged() {
        assertEquals(
            setOf("kdeconnect.notification.request", "kdeconnect.notification.reply", "kdeconnect.notification.action"),
            plugin.supportedPacketTypes.toSet(),
        )
        assertEquals(listOf("kdeconnect.notification"), plugin.outgoingPacketTypes.toList())
    }
}
