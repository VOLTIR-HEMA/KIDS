package com.kids.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** فتح التطبيق بكود سري من لوحة الاتصال: *#*#2003#*#* */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                context.startActivity(open)
            } catch (e: Exception) {
                // قد تمنع بعض الأنظمة فتح نشاط من الخلفية
            }
        }
    }
}
