// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** User-consented Android app-playback capture, constrained by each source app's capture policy. */
class SystemAudioCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "com.syncinit.app.extra.MEDIA_PROJECTION_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.syncinit.app.extra.MEDIA_PROJECTION_RESULT_DATA"
        const val ACTION_STOP = "com.syncinit.app.action.STOP_SYSTEM_AUDIO_CAPTURE"
        private const val NOTIFICATION_CHANNEL_ID = "system_audio_capture"
        private const val NOTIFICATION_ID = 6104
        private const val TAG = "SyncInItCapture"
        private const val SAMPLE_RATE_HZ = SystemAudioCaptureBuffer.SAMPLE_RATE_HZ
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES =
            SystemAudioCaptureBuffer.CHUNK_FRAMES * SystemAudioCaptureBuffer.BYTES_PER_FRAME
    }

    private val running = AtomicBoolean(false)
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            SystemAudioCaptureBuffer.stop("Android revoked the MediaProjection session")
            stopCapture(stopProjection = false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0)
            ?: return stopWithError("MediaProjection result code is missing", startId)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: return stopWithError("MediaProjection consent data is missing", startId)

        try {
            startForegroundCapture(resultCode, resultData)
        } catch (error: Exception) {
            stopWithError(error.message ?: error.javaClass.simpleName, startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCapture(resultCode: Int, resultData: Intent) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("Android playback capture requires Android 10 or newer")
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: throw SecurityException("Android did not provide a MediaProjection session")
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(CHANNEL_MASK)
            .build()
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING)
        if (minBufferBytes <= 0) {
            throw IOException("Android rejected the 48 kHz stereo AudioRecord format: $minBufferBytes")
        }
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(max(minBufferBytes, CHUNK_BYTES * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IOException("Android could not initialize the playback-capture AudioRecord")
        }

        recorder = audioRecord
        SystemAudioCaptureBuffer.begin()
        running.set(true)
        captureThread = Thread({ captureLoop(audioRecord) }, "syncinit-system-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        try {
            audioRecord.startRecording()
            SystemAudioCaptureBuffer.statusListener?.invoke(
                "Android playback capture started · 48 kHz stereo PCM16 · media/game/unknown usages."
            )
            val chunk = ByteArray(CHUNK_BYTES)
            var chunksCaptured = 0L
            while (running.get()) {
                var filled = 0
                while (filled < chunk.size && running.get()) {
                    val count = audioRecord.read(
                        chunk,
                        filled,
                        chunk.size - filled,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count < 0) throw IOException("AudioRecord.read failed with code $count")
                    if (count > 0) filled += count
                }
                if (filled == chunk.size && running.get()) {
                    SystemAudioCaptureBuffer.append(chunk)
                    chunksCaptured += 1L
                    if (chunksCaptured == 1L || chunksCaptured % 250L == 0L) {
                        SystemAudioCaptureBuffer.statusListener?.invoke(
                            "SYSTEM_AUDIO_CAPTURE chunks=${chunksCaptured} capturedFrames=${chunksCaptured * SystemAudioCaptureBuffer.CHUNK_FRAMES}"
                        )
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                SystemAudioCaptureBuffer.fail(error.message ?: error.javaClass.simpleName)
                Log.e(TAG, "Playback capture failed", error)
            }
        } finally {
            running.set(false)
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            if (recorder === audioRecord) recorder = null
            captureThread = null
            if (projection != null) {
                runCatching { projection?.unregisterCallback(projectionCallback) }
                runCatching { projection?.stop() }
                projection = null
            }
            stopForeground(true)
            stopSelf()
        }
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        running.set(false)
        runCatching { recorder?.stop() }
        captureThread?.interrupt()
        if (stopProjection) {
            runCatching { projection?.unregisterCallback(projectionCallback) }
            runCatching { projection?.stop() }
            projection = null
        }
        SystemAudioCaptureBuffer.stop("Capture service stopped")
    }

    private fun stopWithError(message: String, startId: Int): Int {
        SystemAudioCaptureBuffer.fail(message)
        stopCapture()
        stopForeground(true)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "System-audio capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Visible notification while Sync In It captures eligible app playback."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sync In It · System-audio capture")
            .setContentText("Capturing eligible app playback for synchronized speakers")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop capture", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
