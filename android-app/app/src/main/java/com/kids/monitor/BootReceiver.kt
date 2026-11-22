package com.kids.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kids.monitor.data.FirestoreSync

/** إعادة تشغيل المزامنة بعد إعادة تشغيل الجهاز. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        FirestoreSync.init(context)
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
            MonitorService.start(context)
        }
    }
}
