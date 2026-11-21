package com.kids.monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.firestore.SetOptions
import com.kids.monitor.data.FirestoreSync

/** خدمة إمكانية وصول محدودة: تعلن عن حالتها وتنفّذ أوامر القفل عند الطلب فقط. */
class AccessibilityRelayService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        writeFlag(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // لا نحتاج محتوى الأحداث؛ الخدمة موجودة لتمكين أوامر القفل.
    }

    override fun onInterrupt() {
        // لا شيء.
    }

    override fun onDestroy() {
        instance = null
        writeFlag(false)
        super.onDestroy()
    }

    private fun writeFlag(enabled: Boolean) {
        val uid = FirestoreSync.uid() ?: return
        FirestoreSync.deviceRef(uid, FirestoreSync.deviceId(this)).set(
            mapOf("accessibilityEnabled" to enabled),
            SetOptions.merge(),
        )
    }

    companion object {
        @Volatile
        var instance: AccessibilityRelayService? = null
            private set
    }
}
