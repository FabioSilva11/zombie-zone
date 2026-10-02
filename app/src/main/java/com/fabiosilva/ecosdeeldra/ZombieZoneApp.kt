package com.fabiosilva.ecosdeeldra

import android.app.Application
import com.fabiosilva.ecosdeeldra.notify.Reminders

class ZombieZoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // O SDK do AdMob é inicializado depois do consentimento (ads/AdConsent.kt).
        Reminders.createChannel(this)
    }
}
