/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ContactsHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.util.Timer
import java.util.TimerTask

@LoadablePlugin
class TelephonyPlugin : Plugin() {
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastPacket: NetworkPacket? = null
    private var isMuted = false

    @VisibleForTesting
    internal var callController: CallController? = null
        get() = field ?: TelecomCallController(context).also { field = it }

    // Speaker enabled by a request_action, to be restored when the call ends
    private var speakerSetForCall = false

    // Controller role, for calls on the peer's modem
    private var remoteCallNumber: String? = null
    private var remoteCallStreamId: String? = null

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            //Log.e("TelephonyPlugin", "Telephony event: $action")
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED == intent.action) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val intState = when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                // We will get a second broadcast with the phone number https://developer.android.com/reference/android/telephony/TelephonyManager#ACTION_PHONE_STATE_CHANGED
                if (!intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                if (intState != lastState) {
                    lastState = intState
                    callBroadcastReceived(intState, number)
                }
            }
        }
    }

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_telephony)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_telephony_desc)

    private fun callBroadcastReceived(state: Int, phoneNumber: String?) {
        if (isNumberBlocked(phoneNumber)) return

        val np = NetworkPacket(PACKET_TYPE_TELEPHONY)

        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)

        if (permissionCheck == PackageManager.PERMISSION_GRANTED && phoneNumber != null) {
            val result = ContactsHelper.phoneNumberLookup(context, phoneNumber)

            val name = result.name
            if (name != null) {
                np["contactName"] = name
            }

            val photoId = result.photoId
            if (photoId != null) {
                val base64photo = ContactsHelper.photoId64Encoded(context, photoId)
                if (!base64photo.isNullOrEmpty()) {
                    np["phoneThumbnail"] = base64photo
                }
            }
        } else if (phoneNumber != null) {
            np["contactName"] = phoneNumber
        }

        if (phoneNumber != null) {
            np["phoneNumber"] = phoneNumber
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                unmuteRinger()
                np["event"] = "ringing"
                device.sendPacket(np)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                np["event"] = "talking"
                device.sendPacket(np)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (speakerSetForCall) {
                    callController?.setSpeakerphone(false)
                    speakerSetForCall = false
                }
                val lastPacket = lastPacket ?: return
                // Resend a cancel of the last event (can either be "ringing" or "talking")
                lastPacket["isCancel"] = "true"
                device.sendPacket(lastPacket)

                if (isMuted) {
                    val timer = Timer()
                    timer.schedule(object : TimerTask() {
                        override fun run() {
                            unmuteRinger()
                        }
                    }, 500)
                }

                // Emit a missed call notification if needed
                if ("ringing" == lastPacket.getString("event")) {
                    np["event"] = "missedCall"
                    val phoneNumber = lastPacket.getStringOrNull("phoneNumber")
                    if (phoneNumber != null) {
                        np["phoneNumber"] = phoneNumber
                    }
                    val contactName = lastPacket.getStringOrNull("contactName")
                    if (contactName != null) {
                        np["contactName"] = contactName
                    }
                    device.sendPacket(np)
                }
            }
        }

        lastPacket = np
    }

    private fun unmuteRinger() {
        if (isMuted) {
            val am = context.getSystemService<AudioManager>() ?: return
            am.setStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            isMuted = false
        }
    }

    private fun muteRinger() {
        if (isMuted) return

        val am = context.getSystemService<AudioManager>() ?: return
        if (!am.isStreamMute(AudioManager.STREAM_RING)) {
            am.setStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            isMuted = true
        }
    }

    override val permissionExplanation: Int = R.string.telephony_permission_explanation

    override val optionalPermissionExplanation: Int = R.string.telephony_optional_permission_explanation

    override fun onCreate() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.priority = 500
        context.registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        context.unregisterReceiver(receiver)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_TELEPHONY_REQUEST_MUTE -> muteRinger()
            CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION -> handleRequestAction(np)
            CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS -> handleActionStatus(np)
            PACKET_TYPE_TELEPHONY -> handleRemoteCallEvent(np)
        }
        return true
    }

    private fun handleRequestAction(np: NetworkPacket) {
        val request = CallHandoff.parseRequest(np)
        val canStreamCallAudio = device.getPlugin(AUDIOSTREAM_PLUGIN_KEY) != null
        val result = CallHandoff.execute(request, callController!!, canStreamCallAudio)
        if (result.audioRoute == CallHandoff.AudioRoute.SPEAKER || result.audioRoute == CallHandoff.AudioRoute.REMOTE) {
            speakerSetForCall = true
        }
        device.sendPacket(CallHandoff.statusPacket(request, result))
    }

    /**
     * Asks the peer (a desktop with a ModemManager modem) to act on its call.
     * Returns false if the peer does not support call actions.
     */
    fun sendCallAction(action: CallHandoff.Action, audioRoute: CallHandoff.AudioRoute? = null): Boolean {
        if (!CallHandoff.peerAcceptsCallActions(device.deviceInfo.incomingCapabilities)) return false
        device.sendPacket(CallHandoff.requestPacket(action, remoteCallNumber, audioRoute))
        return true
    }

    private fun handleActionStatus(np: NetworkPacket) {
        if (np.getBoolean("success")) {
            if (remoteCallStreamId == null && CallHandoff.shouldRequestCallAudio(np, device.deviceInfo.incomingCapabilities)) {
                val streamId = CallHandoff.newStreamId()
                remoteCallStreamId = streamId
                device.sendPacket(CallHandoff.audioStartPacket(streamId))
            }
            return
        }
        val reason = when (np.getString("error")) {
            CallHandoff.ERROR_NO_CALL -> R.string.telephony_call_error_no_call
            CallHandoff.ERROR_PERMISSION_DENIED -> R.string.telephony_call_error_permission
            CallHandoff.ERROR_UNSUPPORTED -> R.string.telephony_call_error_unsupported
            else -> R.string.telephony_call_error_failed
        }
        showRemoteCallNotification(context.getString(R.string.telephony_call_action_failed), context.getString(reason), emptyList(), false)
    }

    private fun handleRemoteCallEvent(np: NetworkPacket) {
        if (np.getBoolean("isCancel")) {
            remoteCallNumber = null
            remoteCallStreamId?.let { device.sendPacket(CallHandoff.audioStopPacket(it)) }
            remoteCallStreamId = null
            NotificationManagerCompat.from(context).cancel(remoteCallNotificationId)
            return
        }
        val event = np.getString("event")
        if (event != "ringing" && event != "talking") return

        remoteCallNumber = np.getStringOrNull("phoneNumber")
        val caller = np.getStringOrNull("contactName") ?: remoteCallNumber ?: context.getString(R.string.telephony_unknown_caller)
        val actionsSupported = CallHandoff.peerAcceptsCallActions(device.deviceInfo.incomingCapabilities)
        val actions = mutableListOf<NotificationCompat.Action>()
        if (actionsSupported && event == "ringing") {
            actions += remoteCallAction(R.string.telephony_call_answer, CallHandoff.Action.ANSWER, CallHandoff.AudioRoute.DEFAULT)
            if (CallHandoff.peerCanStreamAudio(device.deviceInfo.incomingCapabilities)) {
                actions += remoteCallAction(R.string.telephony_call_answer_here, CallHandoff.Action.ANSWER, CallHandoff.AudioRoute.REMOTE)
            }
            actions += remoteCallAction(R.string.telephony_call_reject, CallHandoff.Action.REJECT, null)
        } else if (actionsSupported) {
            actions += remoteCallAction(R.string.telephony_call_hang_up, CallHandoff.Action.HANGUP, null)
        }
        val title = context.getString(
            if (event == "ringing") R.string.telephony_remote_call_incoming else R.string.telephony_remote_call_in_progress,
            device.name
        )
        showRemoteCallNotification(title, caller, actions, true)
    }

    private val remoteCallNotificationId: Int
        get() = ("callhandoff" + device.deviceId).hashCode()

    private fun remoteCallAction(label: Int, action: CallHandoff.Action, audioRoute: CallHandoff.AudioRoute?): NotificationCompat.Action {
        val intent = Intent(context, TelephonyActionReceiver::class.java).apply {
            this.action = TelephonyActionReceiver.ACTION_CALL
            putExtra(TelephonyActionReceiver.EXTRA_DEVICE_ID, device.deviceId)
            putExtra(TelephonyActionReceiver.EXTRA_CALL_ACTION, action.wire)
            audioRoute?.let { putExtra(TelephonyActionReceiver.EXTRA_AUDIO_ROUTE, it.wire) }
        }
        val requestCode = (device.deviceId + action.wire + audioRoute?.wire).hashCode()
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, context.getString(label), pendingIntent).build()
    }

    private fun showRemoteCallNotification(title: String, text: String, actions: List<NotificationCompat.Action>, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            return
        }
        val builder = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
        actions.forEach { builder.addAction(it) }
        @Suppress("MissingPermission") // Checked above
        NotificationManagerCompat.from(context).notify(remoteCallNotificationId, builder.build())
    }

    private fun isNumberBlocked(number: String?): Boolean {
        val blockedNumbers: List<String> = preferences.getString(KEY_PREF_BLOCKED_NUMBERS, "")!!.split("\n").dropLastWhile { it.isEmpty() }

        return blockedNumbers.any { s -> PhoneNumberUtils.compare(number, s) }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TELEPHONY_REQUEST_MUTE,
        CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION,
        CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS,
        PACKET_TYPE_TELEPHONY,
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TELEPHONY,
        CallHandoff.PACKET_TYPE_TELEPHONY_REQUEST_ACTION,
        CallHandoff.PACKET_TYPE_TELEPHONY_ACTION_STATUS,
        CallHandoff.PACKET_TYPE_AUDIOSTREAM_REQUEST,
    )

    override val requiredPermissions: Array<String> = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)

    override val optionalPermissions: Array<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun hasSettings(): Boolean = true

    override val settingsScope = SettingsScope.APP_WIDE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.telephonyplugin_preferences)
    }

    companion object {
        /**
         * Packet used for simple call events
         *
         * It contains the key "event" which maps to a string indicating the type of event:
         * - "ringing" - A phone call is incoming
         * - "missedCall" - An incoming call was not answered
         * - "sms" - An incoming SMS message
         * - Note: As of this writing (15 May 2018) the SMS interface is being improved and this type of event
         * is no longer the preferred way of handling SMS. Use the packets defined by the SMS plugin instead.
         *
         * Depending on the event, other fields may be defined
         */
        const val PACKET_TYPE_TELEPHONY: String = "kdeconnect.telephony"

        /**
         * Packet sent to indicate the user has requested the device mute its ringer
         *
         * The body should be empty
         */
        private const val PACKET_TYPE_TELEPHONY_REQUEST_MUTE = "kdeconnect.telephony.request_mute"

        private const val KEY_PREF_BLOCKED_NUMBERS = "telephony_blocked_numbers"

        private const val AUDIOSTREAM_PLUGIN_KEY = "AudioStreamPlugin"
    }
}
