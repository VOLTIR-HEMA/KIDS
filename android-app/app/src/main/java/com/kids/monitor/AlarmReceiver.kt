package com.kids.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth

/**
 * مؤقت استيقاظ كل 15 دقيقة يعيد تشغيل خدمة المزامنة لو أوقفها النظام
 * (توفير الطاقة / إغلاق التطبيق من قائمة التشغيل). لا يظهر أي إشعار.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (FirebaseAuth.getInstance().currentUser == null) return
            MonitorService.start(context)
        } catch (e: Exception) { /* تجاهل */ }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.kids.monitor.WATCHDOG"
    }
}
