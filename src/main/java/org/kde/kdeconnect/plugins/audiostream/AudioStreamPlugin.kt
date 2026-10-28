/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Team <kdeconnect@kde.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.audiostream

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.Message
import org.kde.kdeconnect.plugins.audiostream.AudioStreamProtocol.StreamFormat
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class AudioStreamPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_audiostream)

    override val description: String
        get() = context.getString(R.string.pref_plugin_audiostream_desc)

    override val supportedPacketTypes: Array<String> =
        arrayOf(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST)

    override val outgoingPacketTypes: Array<String> =
        arrayOf(AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM, AudioStreamProtocol.PACKET_TYPE_AUDIOSTREAM_REQUEST)

    // Only needed to send audio: asked for when a stream is started, not to load the plugin
    override val optionalPermissions: Array<String>
        get() = arrayOf(Manifest.permission.RECORD_AUDIO)

    override val optionalPermissionExplanation: Int = R.string.audiostream_optional_permission_explanation

    private val handler = Handler(Looper.getMainLooper())

    val controller: AudioStreamController by lazy { AudioStreamController(PluginEnvironment(), localCapabilities(context)) }

    override fun onCreate() {
        controller.onConnected()
    }

    override fun onDestroy() {
        if (isDeviceInitialized) {
            controller.stopAll()
        }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean = controller.onPacketReceived(np)

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        val entries = mutableListOf(
            PluginUiMenuEntry(context.getString(R.string.audiostream_send)) { parentActivity -> chooseLocalSource(parentActivity) },
            PluginUiMenuEntry(context.getString(R.string.audiostream_receive)) { parentActivity -> chooseRemoteSource(parentActivity) },
        )
        if (controller.sessions().isNotEmpty()) {
            entries.add(PluginUiMenuEntry(context.getString(R.string.audiostream_stop_all)) { _ -> controller.stopAll() })
        }
        return entries
    }

    private fun chooseLocalSource(activity: Activity) {
        val sources = controller.localCapabilities.sources
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audiostream_send)
            .setItems(sources.map { sourceLabel(activity, it) }.toTypedArray()) { _, which ->
                val streamId = controller.reserveLocalSend(sources[which])
                if (streamId == null) {
                    Toast.makeText(activity, R.string.audiostream_error_busy, Toast.LENGTH_LONG).show()
                } else {
                    activity.startActivity(AudioStreamConsentActivity.intent(activity, device.deviceId, streamId))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseRemoteSource(activity: Activity) {
        val sources = controller.remoteCapabilities?.sources ?: AudioStreamProtocol.ALL_SOURCES
        if (sources.isEmpty()) {
            Toast.makeText(activity, R.string.audiostream_error_no_remote_sources, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audiostream_receive)
            .setItems(sources.map { sourceLabel(activity, it) }.toTypedArray()) { _, which ->
                if (controller.requestRemote(sources[which]) == null) {
                    Toast.makeText(activity, R.string.audiostream_error_busy, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun consentNotificationId(streamId: String) = CONSENT_NOTIFICATION_BASE + (device.deviceId + streamId).hashCode() % 100000

    private inner class PluginEnvironment : AudioStreamController.Environment {
        override fun send(np: NetworkPacket) = device.sendPacket(np)

        override fun askConsent(streamId: String, source: String, format: StreamFormat) {
            val notificationManager = NotificationManagerCompat.from(context)
            if (!notificationManager.areNotificationsEnabled()) {
                // Nobody could ever answer
                Log.w(LOG_TAG, "Notifications are disabled, refusing audio request")
                handler.post { controller.consentDenied(streamId) }
                return
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val requestCode = consentNotificationId(streamId)
            val allow = PendingIntent.getActivity(context, requestCode, AudioStreamConsentActivity.intent(context, device.deviceId, streamId), flags)
            val deny = PendingIntent.getBroadcast(context, requestCode, AudioStreamActionReceiver.denyIntent(context, device.deviceId, streamId), flags)
            val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(device.name)
                .setContentText(context.getString(R.string.audiostream_consent_text, device.name, sourceLabel(context, source)))
                .setContentIntent(allow)
                .setDeleteIntent(deny)
                .addAction(R.drawable.ic_mic_black, context.getString(R.string.audiostream_allow), allow)
                .addAction(R.drawable.ic_stop, context.getString(R.string.audiostream_deny), deny)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            context.getSystemService<NotificationManager>()?.notify(requestCode, notification)
        }

        override fun dismissConsent(streamId: String) {
            context.getSystemService<NotificationManager>()?.cancel(consentNotificationId(streamId))
        }

        override fun startPlayback(streamId: String, start: Message, payload: NetworkPacket.Payload): AudioStreamController.StreamHandle? {
            val input = payload.inputStream ?: return null
            val buffer = PlaybackBuffer(start.format, LATENCY_MS)
            val player = try {
                AudioPlayer(start.format, buffer, start.source)
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "No audio output", e)
                return null
            } catch (e: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Unsupported output format", e)
                return null
            }
            val receiver = StreamReceiver(input, start.format, buffer, onEnd = { controller.streamEnded(streamId) }, close = { payload.close() })
            receiver.start()
            player.start()
            return AudioStreamController.StreamHandle {
                receiver.stop()
                player.stop()
            }
        }

        override fun schedule(delayMs: Long, action: () -> Unit): AudioStreamController.Cancellable {
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            return AudioStreamController.Cancellable { handler.removeCallbacks(runnable) }
        }

        override fun sessionsChanged() {
            AudioStreamService.refresh(context)
        }
    }

    companion object {
        private const val LOG_TAG = "AudioStreamPlugin"
        private const val LATENCY_MS = 100
        private const val CONSENT_NOTIFICATION_BASE = 0x41530000

        fun localCapabilities(context: Context): AudioStreamProtocol.Capabilities {
            val sources = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sources.add(AudioStreamProtocol.SOURCE_MEDIA)
                sources.add(AudioStreamProtocol.SOURCE_SYSTEM)
            }
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                sources.add(AudioStreamProtocol.SOURCE_CALL)
                sources.add(AudioStreamProtocol.SOURCE_MICROPHONE)
            }
            return AudioStreamProtocol.Capabilities(sources)
        }

        fun sourceLabel(context: Context, source: String): String = context.getString(
            when (source) {
                AudioStreamProtocol.SOURCE_MEDIA -> R.string.audiostream_source_media
                AudioStreamProtocol.SOURCE_SYSTEM -> R.string.audiostream_source_system
                AudioStreamProtocol.SOURCE_CALL -> R.string.audiostream_source_call
                else -> R.string.audiostream_source_microphone
            }
        )
    }
}
