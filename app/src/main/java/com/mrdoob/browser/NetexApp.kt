package com.mrdoob.browser

import android.app.Application
import kotlin.concurrent.thread

class NetexApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Appearance.applyNightMode(this)
        DevToolsPrefs.init(this)
        NavConfirm.init(this)
        BrowserPrefs.primeNavigationConfirmation(this)
        ProtectionEngine.init(this)

        if (DevToolsPrefs.global(DevToolsPrefs.Flag.PRESERVE_RESTART)) {
            thread(name = "netex-restore", isDaemon = true) {
                NavigationTracker.restore(this)
                NetworkLog.restore(this)
            }
        }
    }
}
