package com.cerniera.sveglia

import android.app.Application
import com.cerniera.kit.Crash

class SvegliaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Sveglia")
        Daylight.load(this)
    }
}
