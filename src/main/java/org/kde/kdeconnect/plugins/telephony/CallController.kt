/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * The parts of the platform call handling used by call handoff, abstracted for testing.
 */
interface CallController {
    val sdkInt: Int

    fun hasAnswerPermission(): Boolean

    /** One of TelephonyManager.CALL_STATE_* */
    fun callState(): Int

    /** Throws SecurityException if the permission was revoked */
    fun acceptRingingCall()

    /** Returns false if there was no call to end. Throws SecurityException if the permission was revoked */
    fun endCall(): Boolean

    /** Returns false if the loudspeaker could not be selected */
    fun setSpeakerphone(on: Boolean): Boolean
}

class TelecomCallController(private val context: Context) : CallController {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    private val telecomManager: TelecomManager?
        get() = context.getSystemService<TelecomManager>()

    override fun hasAnswerPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is a required permission of the plugin
    override fun callState(): Int {
        val tm = context.getSystemService<TelephonyManager>() ?: return TelephonyManager.CALL_STATE_IDLE
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.callStateForSubscription
            } else {
                @Suppress("DEPRECATION")
                tm.callState
            }
        } catch (e: SecurityException) {
            TelephonyManager.CALL_STATE_IDLE
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasAnswerPermission(), SecurityException handled by the caller
    override fun acceptRingingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION") // InCallService is only available to the default dialer
            telecomManager?.acceptRingingCall() ?: throw IllegalStateException("No TelecomManager")
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasAnswerPermission(), SecurityException handled by the caller
    override fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        @Suppress("DEPRECATION") // InCallService is only available to the default dialer
        return telecomManager?.endCall() ?: false
    }

    override fun setSpeakerphone(on: Boolean): Boolean {
        val am = context.getSystemService<AudioManager>() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                am.clearCommunicationDevice()
                return true
            }
            val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
            return am.setCommunicationDevice(speaker)
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
        return true
    }
}
