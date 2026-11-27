package com.studiopocket.app

import android.app.Application

class StudioPocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.mulai(this)
        val sebelumnya = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diag.simpanCrash(error)
            Diag.catat("crash di thread ${thread.name}", error)
            sebelumnya?.uncaughtException(thread, error)
        }
    }
}
