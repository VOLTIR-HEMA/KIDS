package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/** خدمة Foreground للمزامنة الدورية واستقبال أوامر التحكم عن بُعد. */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var commandsListener: ListenerRegistration? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        startForeground(NOTIFICATION_ID, buildNotification("المزامنة مفعّلة"))
        prefs().edit().putBoolean(SHARING_ENABLED, true).apply()

        handler.removeCallbacks(syncTask)
        handler.post(syncTask)
        handler.removeCallbacks(relayTask)
        handler.postDelayed(relayTask, RELAY_INTERVAL_MS)

        listenCommands()
        registerNetworkCallback()

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
        prefs().edit().putBoolean(SHARING_ENABLED, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    /** مزامنة حالة الجهاز والموقع، مع حفظ محلي عند انقطاع الإنترنت. */
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
            if (online) {
                FirestoreSync.writeLocation(this, location)
            } else {
                val queued = location.toMutableMap()
                queued["timestamp"] = System.currentTimeMillis()
                PendingStore.addMap(this, queued)
            }
        }

        updateNotification(
            if (online) {
                "المزامنة تعمل · البطارية ${DeviceInfo.battery(this)}٪"
            } else {
                "لا يوجد إنترنت · حفظ محلي ومزامنة لاحقاً"
            },
        )
    }

    /** رفع البيانات المعلّقة عند عودة الاتصال. */
    private fun flushPending() {
        val items = PendingStore.drain(this)
        if (items.isEmpty()) return
        val uid = FirestoreSync.uid() ?: return
        val did = FirestoreSync.deviceId(this)
        var last: JSONObjectLike? = null
        for (obj in items) {
            val map = HashMap<String, Any>()
            for (key in obj.keys()) {
                map[key] = obj.opt(key) ?: ""
            }
            FirestoreSync.subRef(uid, did, "locations").add(map)
            last = JSONObjectLike(
                lat = obj.optDouble("lat"),
                lng = obj.optDouble("lng"),
                accuracy = obj.optDouble("accuracy"),
                address = obj.optString("address"),
                timestamp = obj.opt("timestamp") ?: System.currentTimeMillis(),
            )
        }
        last?.let {
            FirestoreSync.deviceRef(uid, did).set(
                mapOf(
                    "location" to mapOf(
                        "lat" to it.lat,
                        "lng" to it.lng,
                        "accuracy" to it.accuracy,
                        "address" to it.address,
                        "timestamp" to it.timestamp,
                    ),
                ),
                SetOptions.merge(),
            )
        }
    }

    /** مزامنة المكالمات والرسائل وجهات الاتصال والوسائط دون تكرار. */
    private fun relayData() {
        Thread {
            if (!DeviceInfo.online(this)) return@Thread
            val last = prefs().getLong(LAST_RELAY_AT, 0L)

            if (Permissions.has(this, android.Manifest.permission.READ_CALL_LOG)) {
                Collectors.callLog(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                    .forEach { FirestoreSync.appendItem(this, "calls", it) }
            }
            if (Permissions.has(this, android.Manifest.permission.READ_SMS)) {
                Collectors.messages(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                    .forEach { FirestoreSync.appendItem(this, "messages", it) }
            }
            if (!prefs().getBoolean(CONTACTS_SYNCED, false) &&
                Permissions.has(this, android.Manifest.permission.READ_CONTACTS)
            ) {
                Collectors.contacts(this).forEach { FirestoreSync.appendItem(this, "contacts", it) }
                prefs().edit().putBoolean(CONTACTS_SYNCED, true).apply()
            }
            if (Permissions.mediaPermissions().any { Permissions.has(this, it) }) {
                Collectors.media(this).filter { (it["timestamp"] as? Long) ?: 0L > last }
                    .forEach { FirestoreSync.appendItem(this, "media", it) }
            }

            prefs().edit().putLong(LAST_RELAY_AT, System.currentTimeMillis()).apply()
        }.start()
    }

    /** الاستماع للأوامر المعلّقة من لوحة التحكم وتنفيذها. */
    private fun listenCommands() {
        if (commandsListener != null) return
        commandsListener = FirestoreSync.listenCommands(this) { commandId, data ->
            val type = data["type"]?.toString() ?: return@listenCommands
            @Suppress("UNCHECKED_CAST")
            val payload = data["payload"] as? Map<String, Any> ?: emptyMap()
            Thread {
                val result = CommandExecutor.execute(this, type, payload)
                val success = result["ok"] == true
                FirestoreSync.setCommandResult(
                    this,
                    commandId,
                    if (success) "done" else "failed",
                    result,
                )
            }.start()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    flushPending()
                    syncStatus()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            // تجاهل
        }
    }

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
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("مشاركة حالة الجهاز")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مزامنة حالة الجهاز",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "إشعار دائم أثناء مزامنة حالة الجهاز واستقبال الأوامر"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private data class JSONObjectLike(
        val lat: Double,
        val lng: Double,
        val accuracy: Double,
        val address: String,
        val timestamp: Any,
    )

    companion object {
        const val PREFERENCES = "status_sync"
        const val SHARING_ENABLED = "sharing_enabled"
        private const val LAST_RELAY_AT = "last_relay_at"
        private const val CONTACTS_SYNCED = "contacts_synced"
        private const val ACTION_STOP = "com.kids.monitor.STOP_STATUS_SYNC"
        private const val ACTION_SYNC = "com.kids.monitor.SYNC_NOW"
        private const val CHANNEL_ID = "status_sync"
        private const val NOTIFICATION_ID = 1001
        private const val SYNC_INTERVAL_MS = 2 * 60 * 1000L
        private const val RELAY_INTERVAL_MS = 15 * 60 * 1000L

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
