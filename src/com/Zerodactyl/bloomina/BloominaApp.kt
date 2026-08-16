package com.Zerodactyl.bloomina

import android.app.Application

class BloominaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.schedule(this)
    }
}
