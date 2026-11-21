package com.kids.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.firestore.SetOptions
import com.kids.monitor.data.DeviceInfo
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.PendingStore

/** يصدّر الإشعارات الواردة إلى Firestore بعد تفعيل صلاحية قراءة الإشعارات. */
class NotificationRelayService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        writeFlag(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val item = mapOf(
            "app" to sbn.packageName,
            "title" to title,
            "body" to body,
            "timestamp" to System.currentTimeMillis(),
        )

        if (DeviceInfo.online(this)) {
            FirestoreSync.appendItem(this, "notifications", item)
        } else {
            PendingStore.addMap(this, item)
        }
    }

    private fun writeFlag(enabled: Boolean) {
        val uid = FirestoreSync.uid() ?: return
        FirestoreSync.deviceRef(uid, FirestoreSync.deviceId(this)).set(
            mapOf("notificationsEnabled" to enabled),
            SetOptions.merge(),
        )
    }

    companion object {
        @Volatile
        var instance: NotificationRelayService? = null
            private set

        fun clearAll() {
            instance?.cancelAllNotifications()
        }
    }
}
