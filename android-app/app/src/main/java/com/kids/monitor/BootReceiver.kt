package com.kids.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** إعادة تشغيل المزامنة بعد إعادة تشغيل الجهاز. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        ) {
            MonitorService.start(context)
        }
    }
}
