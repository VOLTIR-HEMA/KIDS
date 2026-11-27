package com.volti.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** شاشة المكالمة: WebRTC + عداد إنهاء تلقائي مشترك عبر Firestore. */
class CallActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context, peerUid: String, peerName: String, video: Boolean) {
            ctx.startActivity(
                Intent(ctx, CallActivity::class.java)
                    .putExtra("peer", peerUid)
                    .putExtra("name", peerName)
                    .putExtra("video", video)
                    .putExtra("incoming", false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun startIncoming(ctx: Context, callId: String, peerUid: String, peerName: String, video: Boolean) {
            ctx.startActivity(
                Intent(ctx, CallActivity::class.java)
                    .putExtra("callId", callId)
                    .putExtra("peer", peerUid)
                    .putExtra("name", peerName)
                    .putExtra("video", video)
                    .putExtra("incoming", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private lateinit var peerUid: String
    private lateinit var peerName: String
    private var video = false
    private var incoming = false
    private var callId = ""
    private var isCaller = true

    private lateinit var doc: DocumentReference
    private val registered = ArrayList<ListenerRegistration>()
    private val handler = Handler(Looper.getMainLooper())

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var remoteSink: SurfaceViewRenderer? = null
    private var localView: SurfaceViewRenderer? = null

    private var statusText: TextView? = null
    private var timerText: TextView? = null
    private var banner: TextView? = null
    private var muteBtn: LinearLayout? = null
    private var camBtn: LinearLayout? = null
    private var remoteBox: FrameLayout? = null

    private var muted = false
    private var camOff = false
    private var answered = false
    private var remoteSet = false
    private var ended = false
    private var connectedAt = 0L
    private var deadline = 0L
    private var lastVib = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerUid = intent.getStringExtra("peer").orEmpty()
        peerName = intent.getStringExtra("name").orEmpty().ifBlank { peerUid }
        video = intent.getBooleanExtra("video", false)
        incoming = intent.getBooleanExtra("incoming", false)
        callId = intent.getStringExtra("callId").orEmpty()
        if (peerUid.isBlank()) {
            finish()
            return
        }
        setContentView(buildUi())
        isCaller = !incoming
        Repo.isContact(peerUid) { mine ->
            if (mine) {
                beginCall()
                return@isContact
            }
            if (!incoming) {
                android.widget.Toast.makeText(this, "المكالمات متاحة لجهات الاتصال فقط — أضف الشخص أولاً", android.widget.Toast.LENGTH_LONG).show()
                finish()
                return@isContact
            }
            // مكالمة واردة: مسموحة إذا كان المتصل أضافني لجهات اتصاله أيضاً
            Repo.theyHaveMe(peerUid) { theirs ->
                if (theirs) {
                    beginCall()
                } else {
                    android.widget.Toast.makeText(this, "متصل غير موجود في جهات اتصالك — تم رفض المكالمة", android.widget.Toast.LENGTH_LONG).show()
                    if (callId.isNotBlank()) {
                        try { Fb.db.collection(Fb.CALLS).document(callId).update("state", "declined") } catch (e: Exception) { /* تجاهل */ }
                    }
                    finish()
                }
            }
        }
    }

    private fun beginCall() {
        ensurePerms()
        if (incoming) {
            if (callId.isBlank()) {
                finish()
                return
            }
            doc = Fb.db.collection(Fb.CALLS).document(callId)
            setupPeer()
            watchCallDoc()
            watchCandidates()
            statusText?.text = "مكالمة واردة — جارٍ الرد"
        } else {
            createCall()
        }
        startTicker()
    }

    // ------------------------------------------------------------ الواجهة

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B141A")) }

        remoteBox = FrameLayout(this)
        root.addView(remoteBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(70), dp(20), dp(20))
        }
        info.addView(avatarView(this@CallActivity, peerName, 96))
        info.addView(label(this@CallActivity, peerName, 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })
        statusText = label(this@CallActivity, if (video) "مكالمة مرئية" else "مكالمة صوتية", 14f, Color.parseColor("#AFC3CB")).apply {
            gravity = Gravity.CENTER
        }
        info.addView(statusText)
        timerText = label(this@CallActivity, "00:00", 15f, Color.parseColor("#D6F5EC")).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        info.addView(timerText)
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        banner = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E53935"))
            }
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(14), dp(12), dp(14), 0)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(22))
        }
        val mb = circleButton("🎙", "كتم") { toggleMute() }
        val cb = circleButton("📷", if (video) "الكاميرا" else "فيديو") { toggleVideo() }
        muteBtn = mb
        camBtn = cb
        val switchBtn = circleButton("🔄", "تبديل") { switchCamera() }
        val cdBtn = circleButton("⏱", "عداد تلقائي") { countdownDialog() }
        val endBtn = circleButton("✖", "إنهاء", Color.parseColor("#E53935")) { hangup("user") }
        for (b in listOf<View>(mb, cb, switchBtn, cdBtn, endBtn)) {
            controls.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        localView = SurfaceViewRenderer(this).apply { visibility = if (video) View.VISIBLE else View.GONE }
        root.addView(localView, FrameLayout.LayoutParams(dp(96), dp(136), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(50), dp(14), 0)
        })
        return root
    }

    private fun circleButton(icon: String, text: String, color: Int = Color.parseColor("#1F2C34"), onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            addView(TextView(this@CallActivity).apply {
                this.text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            })
            addView(TextView(this@CallActivity).apply {
                this.text = text
                textSize = 11f
                setTextColor(Color.parseColor("#AFC3CB"))
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun ensurePerms() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (need.isNotEmpty()) androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 77)
    }

    // ------------------------------------------------------------ WebRTC

    private fun iceList(): List<PeerConnection.IceServer> =
        Fb.iceServers(this).mapNotNull { raw ->
            val p = raw.split("|")
            if (p.isEmpty() || p[0].isBlank()) return@mapNotNull null
            val b = PeerConnection.IceServer.builder(p[0].trim())
            if (p.size > 2) b.setUsername(p[1].trim()).setPassword(p[2].trim())
            b.createIceServer()
        }

    private fun setupPeer() {
        try {
            val egl = EglBase.create()
            eglBase = egl
            val f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f

            val cfg = PeerConnection.RTCConfiguration(iceList())
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

            pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let { sendCandidate(it) } }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    runOnUiThread {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> markConnected()
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                statusText?.text = "ضعف في الاتصال…"
                            else -> {}
                        }
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) runOnUiThread { attachRemote(track) }
                }
            })

            // صوت
            val audioSource: AudioSource = f.createAudioSource(MediaConstraints())
            val at = f.createAudioTrack("audio0", audioSource)
            audioTrack = at
            pc?.addTrack(at, listOf("stream0"))

            // فيديو
            if (video) {
                val cap = createCapturer()
                if (cap != null) {
                    capturer = cap
                    val src: VideoSource = f.createVideoSource(false)
                    val h = SurfaceTextureHelper.create("VoltiCapture", egl.eglBaseContext)
                    helper = h
                    cap.initialize(h, this, src.capturerObserver)
                    cap.startCapture(640, 480, 24)
                    val vt = f.createVideoTrack("video0", src)
                    videoTrack = vt
                    pc?.addTrack(vt, listOf("stream0"))
                    val lv = localView
                    if (lv != null) {
                        lv.init(egl.eglBaseContext, null)
                        lv.setMirror(true)
                        lv.setEnableHardwareScaler(true)
                        vt.addSink(lv)
                    }
                }
            }

            val rv = SurfaceViewRenderer(this)
            remoteSink = rv
            rv.init(egl.eglBaseContext, null)
            rv.setEnableHardwareScaler(true)
            remoteBox?.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (!video) rv.visibility = View.GONE

            watchCandidates()
        } catch (e: Exception) {
            statusText?.text = "تعذر تشغيل المحرك: " + (e.localizedMessage ?: "")
        }
    }

    private fun createCapturer(): CameraVideoCapturer? {
        try {
            val c2 = Camera2Enumerator(this)
            for (n in c2.deviceNames) if (c2.isFrontFacing(n)) return c2.createCapturer(n, null)
            for (n in c2.deviceNames) return c2.createCapturer(n, null)
        } catch (e: Exception) { /* تجاهل */ }
        try {
            val c1 = Camera1Enumerator(true)
            for (n in c1.deviceNames) if (c1.isFrontFacing(n)) return c1.createCapturer(n, null)
            for (n in c1.deviceNames) return c1.createCapturer(n, null)
        } catch (e: Exception) { /* تجاهل */ }
        return null
    }

    private fun attachRemote(track: VideoTrack) {
        try {
            val rv = remoteSink ?: return
            rv.visibility = View.VISIBLE
            track.addSink(rv)
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun offerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
    }

    private fun offerObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (sdp == null) return
            pc?.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    doc.update("offer", mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description))
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, sdp)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun answerObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (sdp == null) return
            pc?.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    doc.update(
                        mapOf(
                            "answer" to mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                            "state" to "active",
                            "startedAt" to System.currentTimeMillis(),
                        ),
                    )
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, sdp)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun createCall() {
        val me = Repo.uid()
        doc = Fb.db.collection(Fb.CALLS).document()
        callId = doc.id
        doc.set(
            mapOf(
                "from" to me,
                "to" to peerUid,
                "fromName" to Fb.myName(this).ifBlank { "مستخدم" },
                "toName" to peerName,
                "video" to video,
                "state" to "ringing",
                "createdAt" to System.currentTimeMillis(),
            ),
        ).addOnSuccessListener {
            setupPeer()
            pc?.createOffer(offerObserver(), offerConstraints())
            watchCallDoc()
            statusText?.text = "جارٍ الاتصال بـ " + peerName + "…"
        }.addOnFailureListener { e ->
            statusText?.text = "تعذر بدء المكالمة: " + (e.localizedMessage ?: "")
        }
    }

    private fun watchCallDoc() {
        if (!::doc.isInitialized) return
        registered += doc.addSnapshotListener { snap, _ ->
            if (snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onCallState(snap)
        }
    }

    private fun onCallState(snap: DocumentSnapshot) {
        val state = snap.getString("state").orEmpty()
        if (state == "ended" || state == "declined") {
            if (!ended) {
                ended = true
                toast("انتهت المكالمة")
                finishCall()
            }
            return
        }
        if (state == "active" && connectedAt == 0L) markConnected()

        @Suppress("UNCHECKED_CAST")
        val dl = (snap.get("deadlineAt") as? Number)?.toLong() ?: 0L
        if (dl > 0 && dl != deadline) {
            deadline = dl
            lastVib = 0L
            vibrate()
            banner?.visibility = View.VISIBLE
            toast("تم ضبط عداد إنهاء المكالمة")
        }

        val offer = snap.get("offer") as? Map<*, *>
        val answer = snap.get("answer") as? Map<*, *>
        if (isCaller) {
            if (answer != null && !remoteSet) {
                remoteSet = true
                val type = answer["type"]?.toString().orEmpty().ifBlank { "answer" }
                val sdp = answer["sdp"]?.toString().orEmpty()
                if (sdp.isNotBlank()) {
                    pc?.setRemoteDescription(simpleSdpObserver(), SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
                }
            }
        } else if (offer != null && !answered) {
            answered = true
            val type = offer["type"]?.toString().orEmpty().ifBlank { "offer" }
            val sdp = offer["sdp"]?.toString().orEmpty()
            if (sdp.isNotBlank()) {
                pc?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        pc?.createAnswer(answerObserver(), offerConstraints())
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            }
        }
    }

    private fun simpleSdpObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun sendCandidate(c: IceCandidate) {
        if (!::doc.isInitialized) return
        val col = if (isCaller) "callerCandidates" else "calleeCandidates"
        doc.collection(col).add(
            mapOf("sdpMid" to (c.sdpMid ?: ""), "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp),
        )
    }

    private var watching = false

    private fun watchCandidates() {
        if (watching || !::doc.isInitialized) return
        watching = true
        val col = if (isCaller) "calleeCandidates" else "callerCandidates"
        registered += doc.collection(col).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type != DocumentChange.Type.ADDED) return@forEach
                val d = ch.document
                val sdp = d.getString("candidate") ?: return@forEach
                val mid = d.getString("sdpMid").orEmpty()
                val idx = (d.getLong("sdpMLineIndex") ?: 0L).toInt()
                try { pc?.addIceCandidate(IceCandidate(mid, idx, sdp)) } catch (e: Exception) { /* تجاهل */ }
            }
        }
    }

    private fun markConnected() {
        if (connectedAt == 0L) {
            connectedAt = System.currentTimeMillis()
            statusText?.text = if (video) "مكالمة مرئية متصلة" else "مكالمة صوتية متصلة"
            statusText?.setTextColor(Color.parseColor("#25D366"))
        }
    }

    // ------------------------------------------------------------ العداد

    private fun countdownDialog() {
        val labels = arrayOf("دقيقة واحدة", "3 دقائق", "5 دقائق", "10 دقائق", "مدة مخصصة…")
        AlertDialog.Builder(this)
            .setTitle("عداد إنهاء المكالمة تلقائياً")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> setDeadline(60)
                    1 -> setDeadline(180)
                    2 -> setDeadline(300)
                    3 -> setDeadline(600)
                    else -> customDialog()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun customDialog() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        val min = editText(this, "دقائق", InputType.TYPE_CLASS_NUMBER).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val sec = editText(this, "ثوانٍ", InputType.TYPE_CLASS_NUMBER).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        row.addView(min)
        row.addView(sec)
        AlertDialog.Builder(this)
            .setTitle("مدة مخصصة")
            .setView(row)
            .setPositiveButton("تشغيل") { _, _ ->
                val total = (min.text.toString().toIntOrNull() ?: 0) * 60 + (sec.text.toString().toIntOrNull() ?: 0)
                if (total in 5..86400) setDeadline(total)
                else toast("أدخل مدة بين 5 ثوانٍ و 24 ساعة")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun setDeadline(seconds: Int) {
        if (!::doc.isInitialized) return
        val at = System.currentTimeMillis() + seconds * 1000L
        doc.update("deadlineAt", at).addOnFailureListener { e -> toast("فشل ضبط العداد: " + (e.localizedMessage ?: "")) }
    }

    private fun startTicker() {
        handler.post(object : Runnable {
            override fun run() {
                if (ended) return
                val now = System.currentTimeMillis()
                if (connectedAt > 0) timerText?.text = Fb.duration(now - connectedAt)
                if (deadline > 0) {
                    val left = deadline - now
                    if (left <= 0) {
                        hangup("timeout")
                        return
                    }
                    banner?.text = "تم ضبط عداد إنهاء المكالمة · " + Fb.duration(left)
                    banner?.visibility = View.VISIBLE
                    if (now - lastVib > 60_000) {
                        lastVib = now
                        vibrate()
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 260, 160, 260), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 260, 160, 260), -1)
            }
        } catch (e: Exception) { /* تجاهل */ }
    }

    // ------------------------------------------------------------ أزرار

    private fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        switchLabel(muteBtn, if (muted) "🔇" else "🎙")
        toast(if (muted) "تم كتم المايك" else "تم تشغيل المايك")
    }

    private fun toggleVideo() {
        if (videoTrack == null) {
            toast("المكالمة الصوتية لا تحتوي كاميرا")
            return
        }
        camOff = !camOff
        videoTrack?.setEnabled(!camOff)
        localView?.visibility = if (camOff) View.GONE else View.VISIBLE
        switchLabel(camBtn, if (camOff) "📵" else "📷")
    }

    private fun switchCamera() {
        try {
            (capturer as? CameraVideoCapturer)?.switchCamera(null)
            toast("تم تبديل الكاميرا")
        } catch (e: Exception) {
            toast("تعذر تبديل الكاميرا")
        }
    }

    private fun switchLabel(btn: LinearLayout?, icon: String) {
        (btn?.getChildAt(0) as? TextView)?.text = icon
    }

    private fun hangup(reason: String) {
        if (ended) return
        ended = true
        if (::doc.isInitialized) {
            doc.update(
                mapOf(
                    "state" to "ended",
                    "endedAt" to System.currentTimeMillis(),
                    "endedBy" to Repo.uid(),
                    "endReason" to reason,
                ),
            )
        }
        if (reason == "timeout") toast("انتهى العداد — أُغلقت المكالمة")
        finishCall()
    }

    private fun finishCall() {
        handler.removeCallbacksAndMessages(null)
        releaseAll()
        finish()
    }

    private fun releaseAll() {
        for (r in registered) try { r.remove() } catch (e: Exception) { /* تجاهل */ }
        registered.clear()
        try { capturer?.stopCapture() } catch (e: Exception) { /* تجاهل */ }
        try { capturer?.dispose() } catch (e: Exception) { /* تجاهل */ }
        capturer = null
        try { helper?.dispose() } catch (e: Exception) { /* تجاهل */ }
        helper = null
        try { videoTrack?.dispose() } catch (e: Exception) { /* تجاهل */ }
        try { audioTrack?.dispose() } catch (e: Exception) { /* تجاهل */ }
        try { pc?.close() } catch (e: Exception) { /* تجاهل */ }
        try { pc?.dispose() } catch (e: Exception) { /* تجاهل */ }
        pc = null
        try { factory?.dispose() } catch (e: Exception) { /* تجاهل */ }
        factory = null
        try { remoteSink?.release() } catch (e: Exception) { /* تجاهل */ }
        try { localView?.release() } catch (e: Exception) { /* تجاهل */ }
        try { eglBase?.release() } catch (e: Exception) { /* تجاهل */ }
        eglBase = null
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        hangup("user")
    }

    override fun onDestroy() {
        if (!ended) {
            // إغلاق نظيف إذا خرج المستخدم فجأة
            if (::doc.isInitialized) {
                doc.update("state", "ended", "endedAt", System.currentTimeMillis(), "endedBy", Repo.uid())
            }
        }
        releaseAll()
        super.onDestroy()
    }
}
