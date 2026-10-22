package com.transportlogger

import android.app.Application

/** Applies the chosen theme before any screen is created. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this)
    }
}
