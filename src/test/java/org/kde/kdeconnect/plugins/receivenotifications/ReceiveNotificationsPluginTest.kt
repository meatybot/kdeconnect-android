/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.receivenotifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReceiveNotificationsPluginTest {
    private lateinit var context: Application
    private lateinit var plugin: ReceiveNotificationsPlugin
    private lateinit var notificationManager: NotificationManager
    private val sent = mutableListOf<NetworkPacket>()

    @Before
    fun setup() {
        // App resources are not available to these tests
        context = spyk(ApplicationProvider.getApplicationContext<Application>()) {
            every { getString(R.string.receive_notifications_reply) } returns "Reply"
        }
        notificationManager = context.getSystemService(NotificationManager::class.java)
        val device = mockk<Device> {
            every { deviceId } returns "desktop"
            every { name } returns "Desktop"
            every { sendPacket(any()) } answers { sent.add(arg(0)) }
        }
        plugin = ReceiveNotificationsPlugin().apply { setContext(context, device) }
    }

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private fun desktopPacket(id: String = "42"): NetworkPacket = NetworkPacket(ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION).apply {
        this["id"] = id
        this["appName"] = "Mail"
        this["title"] = "New message"
        this["text"] = "Hello there"
        this["ticker"] = "New message: Hello there"
        this["isClearable"] = true
        this["silent"] = false
        this["time"] = "1700000000000"
        this["actions"] = strings("Archive", "Mark as read")
        this["actionIds"] = strings("archive", "mark-read")
        this["hasDefaultAction"] = true
        this["urgency"] = 2
        this["progress"] = 30
        this["category"] = "email.arrived"
        this["requestReplyId"] = "reply-42"
    }

    private fun posted(): List<Notification> = shadowOf(notificationManager).allNotifications

    private fun postedFor(id: String): Notification {
        return shadowOf(notificationManager).getNotification("kdeconnectId:desktop:$id", id.toInt())
            ?: throw AssertionError("Notification $id not posted")
    }

    private fun savedIntent(pendingIntent: android.app.PendingIntent): Intent = shadowOf(pendingIntent).savedIntent

    @Test
    fun parsesRichPacket() {
        val parsed = ReceivedNotification.fromPacket(desktopPacket())!!
        assertEquals("42", parsed.id)
        assertEquals(listOf(ReceivedNotification.Action("Archive", "archive"), ReceivedNotification.Action("Mark as read", "mark-read")), parsed.actions)
        assertTrue(parsed.hasDefaultAction)
        assertEquals(2, parsed.urgency)
        assertEquals(30, parsed.progress)
        assertEquals(NotificationCompat.CATEGORY_EMAIL, parsed.category)
        assertEquals(1700000000000L, parsed.time)
        assertEquals("reply-42", parsed.requestReplyId)
        assertEquals("New message", parsed.contentTitle)
        assertEquals("Hello there", parsed.contentText)
        assertEquals(NotificationCompat.PRIORITY_HIGH, parsed.priority)
    }

    @Test
    fun parsesLegacyPacket() {
        // As sent by older desktops: no title, no actions, no rich fields
        val np = NetworkPacket(ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION).apply {
            this["id"] = "7"
            this["appName"] = "App"
            this["ticker"] = "Something happened"
            this["isClearable"] = true
        }
        val parsed = ReceivedNotification.fromPacket(np)!!
        assertTrue(parsed.actions.isEmpty())
        assertFalse(parsed.hasDefaultAction)
        assertNull(parsed.urgency)
        assertNull(parsed.progress)
        assertNull(parsed.requestReplyId)
        assertEquals("App", parsed.contentTitle)
        assertEquals("Something happened", parsed.contentText)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, parsed.priority)
    }

    @Test
    fun labelsWithoutMatchingIdsFallBackToLabels() {
        val np = desktopPacket().apply { this["actionIds"] = strings("only-one") }
        val parsed = ReceivedNotification.fromPacket(np)!!
        assertEquals(listOf(ReceivedNotification.Action("Archive", null), ReceivedNotification.Action("Mark as read", null)), parsed.actions)
    }

    @Test
    fun rejectsIncompletePacketAndClampsValues() {
        val incomplete = NetworkPacket(ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION).apply { this["id"] = "1" }
        assertNull(ReceivedNotification.fromPacket(incomplete))

        val np = desktopPacket().apply {
            this["progress"] = 250
            this["urgency"] = 9
        }
        val parsed = ReceivedNotification.fromPacket(np)!!
        assertEquals(100, parsed.progress)
        assertNull(parsed.urgency)
    }

    @Test
    fun rendersActionsAndInlineReply() {
        plugin.onPacketReceived(desktopPacket())

        val notification = postedFor("42")
        val actions = notification.actions
        assertEquals(3, actions.size)
        // Inline reply first, then the desktop actions in order
        assertEquals(1, actions[0].remoteInputs.size)
        assertEquals(ReceiveNotificationsReceiver.KEY_REPLY, actions[0].remoteInputs[0].resultKey)
        assertEquals("Archive", actions[1].title)
        assertEquals("Mark as read", actions[2].title)

        assertEquals(30, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX))
        assertEquals(Notification.CATEGORY_EMAIL, notification.category)
        assertEquals(1700000000000L, notification.`when`)
        assertEquals("New message", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("kdeconnect:desktop:Mail", notification.group)
        assertTrue((notification.flags and Notification.FLAG_LOCAL_ONLY) != 0)
        assertNotNull(notification.deleteIntent)
    }

    @Test
    fun actionButtonSendsActionPacketWithId() {
        plugin.onPacketReceived(desktopPacket())
        val intent = savedIntent(postedFor("42").actions[2].actionIntent)

        plugin.handleIntent(intent)

        val np = sent.single { it.type == ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_ACTION }
        assertEquals("42", np.getString("key"))
        assertEquals("Mark as read", np.getString("action"))
        assertEquals("mark-read", np.getString("actionId"))
    }

    @Test
    fun legacyActionSendsLabelOnly() {
        val np = desktopPacket().apply {
            this["actionIds"] = null as JsonArray?
            this["requestReplyId"] = ""
        }
        plugin.onPacketReceived(np)
        val actions = postedFor("42").actions
        assertEquals(2, actions.size)

        plugin.handleIntent(savedIntent(actions[0].actionIntent))

        val action = sent.single { it.type == ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_ACTION }
        assertEquals("Archive", action.getString("action"))
        assertFalse(action.has("actionId"))
    }

    @Test
    fun contentIntentInvokesDefaultAction() {
        plugin.onPacketReceived(desktopPacket())
        plugin.handleIntent(savedIntent(postedFor("42").contentIntent))

        val np = sent.single { it.type == ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_ACTION }
        assertEquals("default", np.getString("actionId"))
    }

    @Test
    fun inlineReplySendsReplyPacket() {
        plugin.onPacketReceived(desktopPacket())
        val replyAction = postedFor("42").actions[0]
        val intent = savedIntent(replyAction.actionIntent)
        val results = Bundle().apply { putCharSequence(ReceiveNotificationsReceiver.KEY_REPLY, "On my way") }
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(ReceiveNotificationsReceiver.KEY_REPLY).build()), intent, results)

        plugin.handleIntent(intent)

        val np = sent.single { it.type == ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_REPLY }
        assertEquals("reply-42", np.getString("requestReplyId"))
        assertEquals("On my way", np.getString("message"))
        // Re-posted (to stop the "sending" spinner) without alerting again
        val reposted = postedFor("42")
        assertEquals(3, reposted.actions.size)
        assertTrue(reposted.`when` > 0)
        assertEquals(1, posted().size)
    }

    @Test
    fun dismissingSendsCancelRequest() {
        plugin.onPacketReceived(desktopPacket())
        plugin.handleIntent(savedIntent(postedFor("42").deleteIntent))

        val np = sent.single { it.type == ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_REQUEST && it.has("cancel") }
        assertEquals("42", np.getString("cancel"))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun remoteCancelRemovesNotificationAndGroupSummary() {
        plugin.onPacketReceived(desktopPacket("1"))
        plugin.onPacketReceived(desktopPacket("2"))
        // Two children plus the summary
        assertEquals(3, posted().size)
        assertTrue(posted().any { (it.flags and Notification.FLAG_GROUP_SUMMARY) != 0 })

        plugin.onPacketReceived(NetworkPacket(ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION).apply {
            this["id"] = "1"
            this["isCancel"] = true
        })
        val remaining = posted()
        assertEquals(1, remaining.size)
        assertEquals(0, remaining[0].flags and Notification.FLAG_GROUP_SUMMARY)

        assertTrue(sent.none { it.has("cancel") })
    }

    @Test
    fun nonClearableNotificationIsOngoing() {
        plugin.onPacketReceived(desktopPacket().apply { this["isClearable"] = false })
        val notification = postedFor("42")
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNull(notification.deleteIntent)
    }

    @Test
    fun packetTypes() {
        assertArrayEquals(arrayOf(ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION), plugin.supportedPacketTypes)
        assertEquals(
            setOf(
                ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_REQUEST,
                ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_ACTION,
                ReceiveNotificationsPlugin.PACKET_TYPE_NOTIFICATION_REPLY,
            ),
            plugin.outgoingPacketTypes.toSet(),
        )
    }
}
