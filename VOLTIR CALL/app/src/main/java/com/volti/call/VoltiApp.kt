package com.volti.call

import android.app.Application

class VoltiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fb.init(this)
        Repo.appContext = applicationContext
    }
}
