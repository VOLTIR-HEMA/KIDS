package com.kids.monitor

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.kids.monitor.data.FirestoreSync

/** مسؤول جهاز محدود: يقفل الشاشة عند الطلب ويعلن عن حالته. */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        FirestoreSync.updateStatus(context, mapOf("adminEnabled" to true))
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        FirestoreSync.updateStatus(context, mapOf("adminEnabled" to false))
    }
}
