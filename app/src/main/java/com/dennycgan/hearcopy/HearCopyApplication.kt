package com.dennycgan.hearcopy

import android.app.Application

class HearCopyApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Synchronous, before any recording can start in this process.
        container.captureFiles.clearTemp()
        container.notifications.createChannels()
    }
}
