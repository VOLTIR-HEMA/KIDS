package com.volti.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (Fb.auth.currentUser != null) NotifyService.start(context)
        } catch (e: Exception) { /* تجاهل */ }
    }
}
