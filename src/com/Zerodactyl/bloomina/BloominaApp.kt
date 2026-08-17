package com.Zerodactyl.bloomina

import android.app.Application

class BloominaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (UpdateScheduler.isEnabled(this)) {
            UpdateScheduler.schedule(this)
        } else {
            UpdateScheduler.cancel(this)
        }
    }
}
