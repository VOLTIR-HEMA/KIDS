package com.kids.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.UUID

class StatusSyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var firestore: FirebaseFirestore

    private val syncTask = object : Runnable {
        override fun run() {
            syncStatus()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        firestore = FirebaseFirestore.getInstance()
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
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(SHARING_ENABLED, true)
            .apply()
        handler.removeCallbacks(syncTask)
        handler.post(syncTask)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncTask)
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(SHARING_ENABLED, false)
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncStatus() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            stopSelf()
            return
        }
        val deviceId = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { id ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(DEVICE_ID, id)
                    .apply()
            }

        val online = hasValidatedInternet()
        val payload = hashMapOf<String, Any>(
            "battery" to batteryPercent(),
            "charging" to isCharging(),
            "online" to online,
            "lastSeen" to FieldValue.serverTimestamp(),
        )
        if (!online) {
            updateNotification("لا يوجد إنترنت؛ حفظ محلي ومزامنة عند عودة الاتصال")
        }
        firestore.collection("users")
            .document(user.uid)
            .collection("devices")
            .document(deviceId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                updateNotification("حالة البطارية والاتصال محدّثة")
            }
            .addOnFailureListener {
                updateNotification("تعذرت المزامنة؛ ستتم إعادة المحاولة تلقائياً")
            }
    }

    private fun batteryPercent(): Int {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity in 0..100) return capacity
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
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
            Intent(this, StatusSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
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
                description = "إشعار دائم أثناء مشاركة حالة البطارية والاتصال"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFERENCES = "status_sync"
        const val SHARING_ENABLED = "sharing_enabled"
        private const val DEVICE_ID = "device_id"
        private const val ACTION_STOP = "com.kids.monitor.STOP_STATUS_SYNC"
        private const val CHANNEL_ID = "status_sync"
        private const val NOTIFICATION_ID = 1001
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    }
}
