/*
 * SPDX-FileCopyrightText: 2026 meatybot <avatarcc@tuta.io>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.kde.kdeconnect.plugins.audiostream

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * Foreground service hosting audio capture (types mediaProjection/microphone, which Android
 * requires for capturing) and keeping incoming playback visible (type mediaPlayback). Shows the
 * notification with the stop button for every active stream of every device.
 */
class AudioStreamService : Service() {
    private var foregroundTypes = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            startCapture(intent)
        } else {
            update()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID)
        val plugin = KdeConnect.getInstance().getDevicePlugin<AudioStreamPlugin>(deviceId)
        val pending = if (streamId != null) plugin?.controller?.pendingOutgoing(streamId) else null
        if (plugin == null || streamId == null || pending == null) {
            update()
            return
        }
        val (source, format) = pending
        val needsProjection = source == AudioStreamProtocol.SOURCE_MEDIA || source == AudioStreamProtocol.SOURCE_SYSTEM
        val captureType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && needsProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        // Android 14 requires being in the foreground with the capture type before getMediaProjection()
        if (!enterForeground(captureType)) {
            plugin.controller.captureFailed(streamId, AudioStreamProtocol.ERROR_PERMISSION_DENIED)
            update()
            return
        }

        var projection: MediaProjection? = null
        try {
            if (needsProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
                projection = data?.let { getSystemService<MediaProjectionManager>()?.getMediaProjection(resultCode, it) }
                    ?: throw IllegalStateException("No MediaProjection")
                projection.registerCallback(object : MediaProjection.Callback() {
                    // The user stopped the capture from the system UI
                    override fun onStop() = plugin.controller.stop(streamId)
                }, Handler(Looper.getMainLooper()))
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO not granted")
            }
            startCaptureThread(plugin, streamId, source, format, projection)
        } catch (e: Exception) {
            // SecurityException, IllegalStateException, UnsupportedOperationException from the media APIs
            Log.w(LOG_TAG, "Could not start capturing $source", e)
            projection?.stop()
            plugin.controller.captureFailed(
                streamId,
                if (e is SecurityException) AudioStreamProtocol.ERROR_PERMISSION_DENIED else AudioStreamProtocol.ERROR_CAPTURE_FAILED,
            )
        }
        update()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCaptureThread(plugin: AudioStreamPlugin, streamId: String, source: String, format: AudioStreamProtocol.StreamFormat, projection: MediaProjection?) {
        val record = AudioRecordFactory.create(source, format, projection)
        val bytesPerMs = format.sampleRate * format.channels * format.bytesPerSample / 1000
        val queueBytes = bytesPerMs * SENDER_QUEUE_MS + FrameCodec.HEADER_SIZE * (SENDER_QUEUE_MS / format.frameDurationMs + 1)
        // Closed by the link when the receiver goes away or once the last frame was sent
        val queue = FrameQueue(queueBytes) { plugin.controller.streamEnded(streamId) }
        val capture = AudioCaptureThread(record, format, queue, projection) {
            plugin.controller.captureFailed(streamId, AudioStreamProtocol.ERROR_CAPTURE_FAILED)
        }
        val callAudio = if (source == AudioStreamProtocol.SOURCE_CALL) AudioStreamProtocol.CALL_AUDIO_LOCAL_MICROPHONE_ONLY else null
        val handle = AudioStreamController.StreamHandle {
            capture.stop()
            queue.finish()
        }
        capture.start()
        if (!plugin.controller.outgoingReady(streamId, format, callAudio, queue, handle)) {
            capture.stop()
            queue.close()
        }
    }

    private fun enterForeground(extraType: Int): Boolean {
        val sessions = activeSessions()
        var types = foregroundTypes or extraType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sessions.any { !it.second.sending }) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types == 0) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, sessions), types)
            foregroundTypes = types
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, SecurityException (type not allowed right now)
            Log.w(LOG_TAG, "Could not enter the foreground", e)
            false
        }
    }

    /** Show the current streams, or go away when there are none */
    private fun update() {
        val sessions = activeSessions()
        if (sessions.isEmpty()) {
            foregroundTypes = 0
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Capture types stay while something is captured; recomputing them would demote us
        if (sessions.none { it.second.sending }) {
            foregroundTypes = 0
        }
        if (!enterForeground(0)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            postFallback(this, sessions)
        }
    }

    companion object {
        private const val LOG_TAG = "AudioStreamService"
        private const val NOTIFICATION_ID = 0x41530001
        private const val SENDER_QUEUE_MS = 250
        private const val ACTION_CAPTURE = "org.kde.kdeconnect.plugins.audiostream.CAPTURE"
        private const val ACTION_REFRESH = "org.kde.kdeconnect.plugins.audiostream.REFRESH"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_STREAM_ID = "streamId"
        private const val EXTRA_PROJECTION_RESULT = "projectionResult"
        private const val EXTRA_PROJECTION_DATA = "projectionData"

        @Volatile
        private var instance: AudioStreamService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Called from a visible activity, which is what allows starting a capture service */
        fun startCapture(context: Context, deviceId: String, streamId: String, projectionResult: Int, projectionData: Intent?) {
            val intent = Intent(context, AudioStreamService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_STREAM_ID, streamId)
                putExtra(EXTRA_PROJECTION_RESULT, projectionResult)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** The set of streams changed */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            mainHandler.post {
                val service = instance
                val sessions = activeSessions()
                if (service != null) {
                    service.update()
                } else if (sessions.isNotEmpty()) {
                    try {
                        ContextCompat.startForegroundService(appContext, Intent(appContext, AudioStreamService::class.java).setAction(ACTION_REFRESH))
                    } catch (e: Exception) {
                        // Background start not allowed (Android 12+): playback still runs, show a regular notification
                        Log.w(LOG_TAG, "Could not start the service", e)
                        postFallback(appContext, sessions)
                    }
                } else {
                    appContext.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
                }
            }
        }

        private fun activeSessions(): List<Pair<String, AudioStreamController.SessionInfo>> =
            KdeConnect.getInstance().devices.values.flatMap { device ->
                val plugin = device.getPlugin(AudioStreamPlugin::class.java) ?: return@flatMap emptyList()
                plugin.controller.sessions()
                    .filter { it.state == AudioStreamController.State.ACTIVE }
                    .map { Pair(device.name, it) }
            }

        private fun postFallback(context: Context, sessions: List<Pair<String, AudioStreamController.SessionInfo>>) {
            context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(context, sessions))
        }

        private fun buildNotification(context: Context, sessions: List<Pair<String, AudioStreamController.SessionInfo>>): Notification {
            val lines = sessions.map { (deviceName, info) ->
                val source = AudioStreamPlugin.sourceLabel(context, info.source)
                if (info.sending) {
                    context.getString(R.string.audiostream_sending, source, deviceName)
                } else {
                    context.getString(R.string.audiostream_playing, source, deviceName)
                }
            }
            val stop = PendingIntent.getBroadcast(
                context, 0, AudioStreamActionReceiver.stopAllIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = lines.joinToString("\n")
            return NotificationCompat.Builder(context, NotificationHelper.Channels.MEDIA_CONTROL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.audiostream_notification_title))
                .setContentText(lines.firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(R.drawable.ic_stop, context.getString(R.string.audiostream_stop_all), stop)
                .build()
        }
    }
}
