package com.kids.monitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth

/**
 * عامل دوري (كل 15 دقيقة) يعيد تشغيل خدمة المزامنة لو أوقفها النظام،
 * حتى لو لم يصل إنذار AlarmManager (توفير طاقة شرس من الشركة المصنّعة).
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            if (FirebaseAuth.getInstance().currentUser != null) {
                MonitorService.start(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
