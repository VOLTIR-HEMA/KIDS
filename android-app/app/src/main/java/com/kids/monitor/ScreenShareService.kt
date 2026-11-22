package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.StorageUploader
import java.io.File

/**
 * مشاركة الشاشة عبر MediaProjection: لقطة كل 4 ثوانٍ تُرفع إلى
 * users/{uid}/devices/{deviceId}/screen/ وتُحدَّث في live/screen.
 * إذن MediaProjection يطلبه النظام مرة لكل جلسة (قيد أندرويد).
 */
class ScreenShareService : Service() {

    val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastRemotePath: String? = null
    private var capturing = false

    private val captureTask = object : Runnable {
        override fun run() {
            captureFrame()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        FirestoreSync.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val metrics = resources.displayMetrics
        val scale = minOf(1f, MAX_WIDTH.toFloat() / metrics.widthPixels.toFloat())
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) / 2 * 2
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) / 2 * 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData)
        virtualDisplay = projection?.createVirtualDisplay(
            "kids-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler,
        )
        handler.post(captureTask)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(captureTask)
        try { virtualDisplay?.release() } catch (e: Exception) { /* تجاهل */ }
        try { imageReader?.close() } catch (e: Exception) { /* تجاهل */ }
        try { projection?.stop() } catch (e: Exception) { /* تجاهل */ }
        virtualDisplay = null
        imageReader = null
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun captureFrame() {
        if (capturing) return
        val reader = imageReader ?: return
        capturing = true
        Thread {
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@Thread
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val bitmapWidth = image.width + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                val file = File(cacheDir, "screen_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
                }
                bitmap.recycle()
                cropped.recycle()

                val path = "screen/${file.name}"
                val url = StorageUploader.uploadSync(file, path)
                val inline = if (url == null) StorageUploader.inlineData(file) else null
                if (url != null || inline != null) {
                    FirestoreSync.setLive(
                        this, "screen",
                        mapOf(
                            "name" to file.name,
                            "url" to (url ?: ""),
                            "dataUrl" to (inline ?: ""),
                            "type" to "image",
                            "mimeType" to "image/jpeg",
                            "size" to file.length(),
                            "screen" to true,
                            "timestamp" to System.currentTimeMillis(),
                            "error" to "",
                        ),
                    )
                    if (url != null) {
                        val old = lastRemotePath
                        lastRemotePath = path
                        if (old != null) StorageUploader.delete(old)
                    }
                } else {
                    FirestoreSync.setLive(
                        this, "screen",
                        mapOf("error" to "تعذر رفع لقطة الشاشة", "timestamp" to System.currentTimeMillis()),
                    )
                }
                file.delete()
            } catch (e: Exception) {
                /* تجاهل: اللقطة الاختيارية قد تفشل دون تعطيل الخدمة */
            } finally {
                try { image?.close() } catch (e: Exception) { /* تجاهل */ }
                capturing = false
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "إيقاف",
            stopService,
        ).build()
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("مشاركة الشاشة")
            .setContentText("جارٍ إرسال لقطات الشاشة")
            .setContentIntent(openApp)
            .setPriority(Notification.PRIORITY_MIN)
            .setSound(null)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مشاركة الشاشة",
                NotificationManager.IMPORTANCE_NONE,
            ).apply {
                description = "خدمة التقاط الشاشة"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "com.kids.monitor.STOP_SCREEN_SHARE"
        private const val CHANNEL_ID = "screen_share_v2"
        private const val NOTIFICATION_ID = 1002
        private const val CAPTURE_INTERVAL_MS = 4000L
        private const val MAX_WIDTH = 720

        @Volatile
        private var instance: ScreenShareService? = null

        fun isRunning(): Boolean = instance != null

        fun captureNow() {
            val service = instance ?: return
            service.handler.post { service.captureFrame() }
        }
    }
}
