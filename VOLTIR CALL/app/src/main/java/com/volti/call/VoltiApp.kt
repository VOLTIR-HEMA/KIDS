package com.volti.call

import android.app.Application
import org.webrtc.PeerConnectionFactory

class VoltiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fb.init(this)
        Repo.appContext = applicationContext
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        } catch (e: Exception) {
            /* تمت التهيئة مسبقاً */
        }
        installCrashHandler()
        flushCrash()
    }

    /** يحفظ تقرير أي عطل محلياً ثم يرفعه للسحابة عند التشغيل التالي. */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val rec = "device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                    "\nsdk=" + android.os.Build.VERSION.SDK_INT +
                    "\nversion=" + Update.version(this) +
                    "\nthread=" + t.name +
                    "\nwhere=" + (e.stackTrace.firstOrNull()?.toString() ?: "") +
                    "\n\n" + android.util.Log.getStackTraceString(e).take(8000)
                Fb.prefs(this).edit().putString("crash_pending", rec).commit()
            } catch (x: Exception) {
                /* تجاهل */
            }
            if (prev != null) prev.uncaughtException(t, e) else kotlin.system.exitProcess(2)
        }
    }

    private fun flushCrash() {
        try {
            val rec = Fb.prefs(this).getString("crash_pending", "").orEmpty()
            if (rec.isBlank()) return
            val u = Fb.auth.currentUser ?: return
            Fb.db.collection("volti_crashes").add(
                mapOf(
                    "uid" to u.uid,
                    "time" to System.currentTimeMillis(),
                    "report" to rec,
                ),
            ).addOnCompleteListener {
                Fb.prefs(this).edit().remove("crash_pending").apply()
            }
        } catch (e: Exception) {
            /* تجاهل */
        }
    }
}
