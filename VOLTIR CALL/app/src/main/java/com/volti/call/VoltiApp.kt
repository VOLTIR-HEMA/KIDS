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
    }
}
