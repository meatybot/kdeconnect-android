/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect.KdeConnect

/**
 * Invisible activity collecting what Android needs before we can capture audio: the microphone
 * permission, and for media/system the MediaProjection consent dialog. Capture itself has to
 * start from a foreground service, which this activity being visible allows.
 */
class AudioStreamConsentActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private lateinit var streamId: String
    private lateinit var source: String
    private var answered = false

    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterRecordPermission() else deny()
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            grant(result.resultCode, data)
        } else {
            deny()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        streamId = intent.getStringExtra(EXTRA_STREAM_ID) ?: return finish()
        val pending = plugin()?.controller?.pendingOutgoing(streamId) ?: return finish()
        source = pending.first
        if (savedInstanceState != null) {
            // Recreated: the result of the dialog we launched is still delivered to us
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            afterRecordPermission()
        } else {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun plugin(): AudioStreamPlugin? = KdeConnect.getInstance().getDevicePlugin<AudioStreamPlugin>(deviceId)

    private fun afterRecordPermission() {
        val needsProjection = source == AudioStreamProtocol.SOURCE_MEDIA || source == AudioStreamProtocol.SOURCE_SYSTEM
        if (needsProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService<MediaProjectionManager>() ?: return deny()
            // Playback capture follows what the projection covers: with "A single app" (the default
            // since Android 14) only that app would be heard, so ask for the whole display
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionConsent.launch(captureIntent)
        } else if (needsProjection) {
            deny()
        } else {
            grant(RESULT_CANCELED, null)
        }
    }

    private fun grant(resultCode: Int, projectionData: Intent?) {
        answered = true
        val plugin = plugin()
        if (plugin != null && plugin.controller.consentGranted(streamId)) {
            AudioStreamService.startCapture(this, deviceId, streamId, resultCode, projectionData)
        }
        finish()
    }

    private fun deny() {
        answered = true
        plugin()?.controller?.consentDenied(streamId)
        finish()
    }

    override fun onDestroy() {
        // Leaving without answering (back gesture) counts as a no, but not a configuration change
        if (!answered && isFinishing && ::streamId.isInitialized) {
            plugin()?.controller?.consentDenied(streamId)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_STREAM_ID = "streamId"

        fun intent(context: Context, deviceId: String, streamId: String) =
            Intent(context, AudioStreamConsentActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_STREAM_ID, streamId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
