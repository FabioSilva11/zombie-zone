package com.fabiosilva.ecosdeeldra.ads

import android.app.Activity
import android.util.Log
import com.fabiosilva.ecosdeeldra.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Anúncio intersticial entre partidas (bloco ADMOB_INTERSTITIAL_ID no app/build.gradle.kts).
 * Só aparece numa pausa natural (ao sair da tela de resultado), nunca durante a partida, e a
 * frequência é limitada por Rules.shouldShowInterstitial — conforme as políticas do AdMob.
 * O aparelho de desenvolvimento recebe anúncios de teste (ADMOB_TEST_DEVICES).
 */
class InterstitialAds(private val activity: Activity) {
    private var ad: InterstitialAd? = null
    private var loading = false
    var showing = false
        private set

    val enabled: Boolean get() = AD_UNIT_ID.isNotBlank()
    val isReady: Boolean get() = ad != null

    fun load() {
        if (!enabled || !AdConsent.ready || ad != null || loading || showing) return
        loading = true
        InterstitialAd.load(activity, AD_UNIT_ID, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(loaded: InterstitialAd) {
                loading = false
                ad = loaded
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loading = false
                ad = null
                Log.w(TAG, "Intersticial não carregou: ${error.code} ${error.message}")
            }
        })
    }

    /** Mostra se houver anúncio pronto; [onDone] sempre roda (com ou sem anúncio). */
    fun show(onDone: (shown: Boolean) -> Unit) {
        val current = ad
        if (current == null) {
            load()
            onDone(false)
            return
        }
        ad = null
        showing = true
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                load()
                onDone(true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "Falha ao exibir intersticial: ${error.message}")
                load()
                onDone(false)
            }
        }
        current.show(activity)
    }

    private companion object {
        const val TAG = "InterstitialAds"
        val AD_UNIT_ID: String = BuildConfig.ADMOB_INTERSTITIAL_ID
    }
}
