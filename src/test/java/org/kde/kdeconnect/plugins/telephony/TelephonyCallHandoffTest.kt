/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.telephony.CallHandoff.Action
import org.kde.kdeconnect.plugins.telephony.CallHandoff.AudioRoute
import org.kde.kdeconnect_tp.R
import org.robolectric.Shadows.shadowOf

private class FakeCallController(
    override var sdkInt: Int = Build.VERSION_CODES.P,
    var permission: Boolean = true,
    var state: Int = TelephonyManager.CALL_STATE_RINGING,
    var endCallResult: Boolean = true,
    var speakerAvailable: Boolean = true,
    var throwOnAction: RuntimeException? = null,
) : CallController {
    var accepted = 0
    var ended = 0
    val speakerCalls = mutableListOf<Boolean>()

    override fun hasAnswerPermission() = permission
    override fun callState() = state

    override fun acceptRingingCall() {
        throwOnAction?.let { throw it }
        accepted++
        state = TelephonyManager.CALL_STATE_OFFHOOK
    }

    override fun endCall(): Boolean {
        throwOnAction?.let { throw it }
        ended++
        return endCallResult
    }

    override fun setSpeakerphone(on: Boolean): Boolean {
        speakerCalls += on
        return !on || speakerAvailable
    }
}

@RunWith(AndroidJUnit4::class)
class TelephonyCallHandoffTest {
    private lateinit var context: Context
    private val sent = mutableListOf<NetworkPacket>()
    private val peerIncoming = mutableSetOf<String>()
    private var peerHasAudioStreamPlugin = false

    private fun request(action: String, phoneNumber: String? = null, audioRoute: String? = null): NetworkPacket {
        val np = NetworkPacket(CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION)
        np["action"] = action
        phoneNumber?.let { np["phoneNumber"] = it }
        audioRoute?.let { np["audioRoute"] = it }
        return NetworkPacket.unserialize(np.serialize())
    }

    private fun execute(packet: NetworkPacket, controller: CallController, canStream: Boolean = false): CallHandoff.Result =
        CallHandoff.execute(CallHandoff.parseRequest(packet), controller, canStream)

    private fun makePlugin(controller: CallController): TelephonyPlugin {
        val info = DeviceInfo("peer_id", mockk(relaxed = true), "Desktop", DeviceType.DESKTOP, 8, peerIncoming, emptySet())
        val audioPlugin = mockk<Plugin>()
        val device = mockk<Device> {
            every { sendPacket(any()) } answers { sent += NetworkPacket.unserialize(arg<NetworkPacket>(0).serialize()) }
            every { deviceInfo } returns info
            every { deviceId } returns "peer_id"
            every { name } returns "Desktop"
            every { getPlugin("AudioStreamPlugin") } answers { if (peerHasAudioStreamPlugin) audioPlugin else null }
        }
        return TelephonyPlugin().apply {
            setContext(context, device)
            callController = controller
        }
    }

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Unit tests run without the app resources: strings resolve to "string:<id>"
        val resources = mockk<Resources>(relaxed = true) {
            every { getString(any()) } answers { "string:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "string:${firstArg<Int>()}" }
        }
        context = object : ContextWrapper(app) {
            override fun getResources(): Resources = resources
        }
    }

    private fun postedActionTitles(): List<String> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(nm).allNotifications.single()
        return notification.actions.orEmpty().map { it.title.toString() }
    }

    private fun str(id: Int) = "string:$id"

    @Test
    fun parsesRequests() {
        val r = CallHandoff.parseRequest(request("answer", "+15550100", "remote"))
        assertEquals(Action.ANSWER, r.action)
        assertEquals("+15550100", r.phoneNumber)
        assertEquals(AudioRoute.REMOTE, r.audioRoute)

        val defaults = CallHandoff.parseRequest(request("hangup", audioRoute = "loud"))
        assertEquals(Action.HANGUP, defaults.action)
        assertNull(defaults.phoneNumber)
        assertEquals(AudioRoute.DEFAULT, defaults.audioRoute)

        val invalid = CallHandoff.parseRequest(request("explode"))
        assertNull(invalid.action)
        assertEquals("explode", invalid.rawAction)
    }

    @Test
    fun rejectsInvalidActionWithoutTouchingTheCall() {
        val controller = FakeCallController()
        val result = execute(request("explode"), controller)
        assertEquals(CallHandoff.Result(false, CallHandoff.ERROR_INVALID_ACTION), result)
        assertEquals(0, controller.accepted + controller.ended)
    }

    @Test
    fun gatesOnApiLevel() {
        // acceptRingingCall needs API 26, endCall needs API 28
        val api25 = FakeCallController(sdkInt = Build.VERSION_CODES.N_MR1)
        assertEquals(CallHandoff.ERROR_UNSUPPORTED, execute(request("answer"), api25).error)
        assertEquals(CallHandoff.ERROR_UNSUPPORTED, execute(request("reject"), api25).error)

        val api27 = FakeCallController(sdkInt = Build.VERSION_CODES.O_MR1)
        assertTrue(execute(request("answer"), api27).success)
        assertEquals(CallHandoff.ERROR_UNSUPPORTED, execute(request("hangup"), api27).error)
        assertEquals(CallHandoff.ERROR_UNSUPPORTED, execute(request("reject"), api27).error)
        assertEquals(0, api27.ended)

        val api28 = FakeCallController(sdkInt = Build.VERSION_CODES.P)
        assertTrue(execute(request("reject"), api28).success)
        assertEquals(1, api28.ended)
    }

    @Test
    fun gatesOnPermission() {
        val controller = FakeCallController(permission = false)
        assertEquals(CallHandoff.ERROR_PERMISSION_DENIED, execute(request("answer"), controller).error)
        assertEquals(0, controller.accepted)

        // Revoked between the check and the call
        val revoked = FakeCallController(throwOnAction = SecurityException("revoked"))
        assertEquals(CallHandoff.ERROR_PERMISSION_DENIED, execute(request("hangup"), revoked).error)
    }

    @Test
    fun gatesOnCallState() {
        val idle = FakeCallController(state = TelephonyManager.CALL_STATE_IDLE)
        assertEquals(CallHandoff.ERROR_NO_CALL, execute(request("answer"), idle).error)
        assertEquals(CallHandoff.ERROR_NO_CALL, execute(request("hangup"), idle).error)

        val talking = FakeCallController(state = TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(CallHandoff.ERROR_NO_CALL, execute(request("answer"), talking).error)
        assertEquals(CallHandoff.ERROR_NO_CALL, execute(request("reject"), talking).error)
        assertTrue(execute(request("hangup"), talking).success)
    }

    @Test
    fun reportsFailures() {
        assertEquals(CallHandoff.ERROR_FAILED, execute(request("reject"), FakeCallController(endCallResult = false)).error)
        assertEquals(CallHandoff.ERROR_FAILED, execute(request("answer"), FakeCallController(throwOnAction = IllegalStateException())).error)
    }

    @Test
    fun appliesAudioRoutes() {
        val normal = FakeCallController()
        assertEquals(AudioRoute.DEFAULT, execute(request("answer"), normal).audioRoute)
        assertTrue(normal.speakerCalls.isEmpty())

        val speaker = FakeCallController()
        assertEquals(AudioRoute.SPEAKER, execute(request("answer", audioRoute = "speaker"), speaker).audioRoute)
        assertEquals(listOf(true), speaker.speakerCalls)

        val noSpeaker = FakeCallController(speakerAvailable = false)
        val r = execute(request("answer", audioRoute = "speaker"), noSpeaker)
        assertTrue(r.success)
        assertEquals(AudioRoute.DEFAULT, r.audioRoute)

        // Remote needs the local audiostream plugin
        assertEquals(AudioRoute.DEFAULT, execute(request("answer", audioRoute = "remote"), FakeCallController(), canStream = false).audioRoute)
        assertEquals(AudioRoute.REMOTE, execute(request("answer", audioRoute = "remote"), FakeCallController(), canStream = true).audioRoute)

        // No route for reject / hangup
        assertNull(execute(request("reject", audioRoute = "speaker"), FakeCallController()).audioRoute)
    }

    @Test
    fun decidesAudioRequestFromPeerCapabilities() {
        val ok = NetworkPacket(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS)
        ok["action"] = "answer"
        ok["success"] = true
        ok["audioRoute"] = "remote"
        val withAudio = setOf(CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST, CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION)
        val withoutAudio = setOf(CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION)

        assertTrue(CallHandoff.shouldRequestCallAudio(ok, withAudio))
        assertFalse(CallHandoff.shouldRequestCallAudio(ok, withoutAudio))
        assertFalse(CallHandoff.shouldRequestCallAudio(ok, null))

        ok["audioRoute"] = "default"
        assertFalse(CallHandoff.shouldRequestCallAudio(ok, withAudio))
        ok["audioRoute"] = "remote"
        ok["success"] = false
        assertFalse(CallHandoff.shouldRequestCallAudio(ok, withAudio))
    }

    @Test
    fun pluginAnswersRequestWithStatus() {
        val controller = FakeCallController()
        val plugin = makePlugin(controller)
        peerHasAudioStreamPlugin = true

        plugin.onPacketReceived(request("answer", "+15550100", "remote"))

        assertEquals(1, controller.accepted)
        val status = sent.single()
        assertEquals(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS, status.type)
        assertEquals("answer", status.getString("action"))
        assertTrue(status.getBoolean("success"))
        assertEquals("remote", status.getString("audioRoute"))
        assertEquals("+15550100", status.getString("phoneNumber"))
        assertFalse(status.has("error"))
    }

    @Test
    fun pluginReportsErrorWithoutAudioStream() {
        val plugin = makePlugin(FakeCallController(state = TelephonyManager.CALL_STATE_IDLE))
        plugin.onPacketReceived(request("reject"))
        val status = sent.single()
        assertFalse(status.getBoolean("success", true))
        assertEquals(CallHandoff.ERROR_NO_CALL, status.getString("error"))
        assertFalse(status.has("audioRoute"))
    }

    @Test
    fun controllerRoundTripForDesktopModemCall() {
        peerIncoming += CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION
        peerIncoming += CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST
        val plugin = makePlugin(FakeCallController())

        val ringing = NetworkPacket(TelephonyPlugin.PACKET_TYPE_TELEPHONY)
        ringing["event"] = "ringing"
        ringing["phoneNumber"] = "+15550100"
        plugin.onPacketReceived(ringing)
        assertEquals(
            listOf(str(R.string.telephony_call_answer), str(R.string.telephony_call_answer_here), str(R.string.telephony_call_reject)),
            postedActionTitles()
        )

        assertTrue(plugin.sendCallAction(Action.ANSWER, AudioRoute.REMOTE))
        val req = sent.single()
        assertEquals(CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION, req.type)
        assertEquals("answer", req.getString("action"))
        assertEquals("remote", req.getString("audioRoute"))
        assertEquals("+15550100", req.getString("phoneNumber"))

        val status = NetworkPacket(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS)
        status["action"] = "answer"
        status["success"] = true
        status["audioRoute"] = "remote"
        plugin.onPacketReceived(status)
        plugin.onPacketReceived(status) // duplicate must not start a second stream

        val talking = NetworkPacket(TelephonyPlugin.PACKET_TYPE_TELEPHONY)
        talking["event"] = "talking"
        talking["phoneNumber"] = "+15550100"
        plugin.onPacketReceived(talking)
        assertEquals(listOf(str(R.string.telephony_call_hang_up)), postedActionTitles())

        val audio = sent.filter { it.type == CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST }
        assertEquals(1, audio.size)
        assertEquals("start", audio[0].getString("action"))
        assertEquals("call", audio[0].getString("source"))
        assertEquals("pcm_s16le", audio[0].getString("codec"))
        assertEquals(48000, audio[0].getInt("sampleRate"))
        assertEquals(1, audio[0].getInt("channels"))
        val streamId = audio[0].getString("streamId")
        assertTrue(streamId.startsWith("call-"))

        val ended = NetworkPacket(TelephonyPlugin.PACKET_TYPE_TELEPHONY)
        ended["event"] = "talking"
        ended["isCancel"] = true
        plugin.onPacketReceived(ended)

        val stop = sent.last()
        assertEquals(CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST, stop.type)
        assertEquals("stop", stop.getString("action"))
        assertEquals(streamId, stop.getString("streamId"))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun controllerDoesNothingForOldPeers() {
        val plugin = makePlugin(FakeCallController())
        assertFalse(plugin.sendCallAction(Action.HANGUP))

        // Informational notification only
        val ringing = NetworkPacket(TelephonyPlugin.PACKET_TYPE_TELEPHONY)
        ringing["event"] = "ringing"
        plugin.onPacketReceived(ringing)
        assertTrue(postedActionTitles().isEmpty())

        val status = NetworkPacket(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS)
        status["action"] = "answer"
        status["success"] = true
        status["audioRoute"] = "remote"
        plugin.onPacketReceived(status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun declaresPacketTypes() {
        val plugin = TelephonyPlugin()
        assertTrue(CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION in plugin.supportedPacketTypes)
        assertTrue(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS in plugin.supportedPacketTypes)
        assertTrue(CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS in plugin.outgoingPacketTypes)
        assertTrue(CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST in plugin.outgoingPacketTypes)
    }
}
