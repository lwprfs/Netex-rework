package com.mrdoob.browser

import android.app.Application

class NetexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Light / dark / follow-system, as chosen in Settings → Appearance.
        Appearance.applyNightMode(this)
    }
}
