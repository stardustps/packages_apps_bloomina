package com.Zerodactyl.bloomina

import android.app.Application
import com.topjohnwu.superuser.Shell

class BloominaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Preload the root shell config early so first use in the flasher is fast.
        Shell.getShell {}
    }
}
