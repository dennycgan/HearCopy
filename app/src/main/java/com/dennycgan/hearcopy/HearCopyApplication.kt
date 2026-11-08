package com.dennycgan.hearcopy

import android.app.Application

class HearCopyApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
