package com.fabiosilva.ecosdeeldra

import android.app.Application
import com.fabiosilva.ecosdeeldra.notify.Reminders
import com.google.android.gms.ads.MobileAds

class ZombieZoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // A orientação da tela de anúncio fica a cargo do próprio AdMob: forçar retrato
        // cortava os vídeos de paisagem (bug da versão anterior).
        MobileAds.initialize(this)
        Reminders.createChannel(this)
    }
}
