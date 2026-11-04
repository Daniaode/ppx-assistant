package com.akari.ppx

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.akari.ppx.data.FrameworkScopeState
import com.akari.ppx.data.Prefs

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        context = base
    }

    override fun onCreate() {
        super.onCreate()
        FrameworkScopeState.init()
        Prefs.syncMirror()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }
}
