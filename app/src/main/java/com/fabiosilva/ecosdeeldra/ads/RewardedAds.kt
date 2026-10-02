package com.fabiosilva.ecosdeeldra.ads

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Anúncios premiados do AdMob. Usa o ID de TESTE do Google; troque [AD_UNIT_ID]
 * (e o APPLICATION_ID no AndroidManifest) pelos IDs reais antes de publicar.
 */
class RewardedAds(private val activity: Activity) {
    private var ad: RewardedAd? = null
    private var loading = false
    var showing = false
        private set
    var onStateChanged: (() -> Unit)? = null

    val isReady: Boolean get() = ad != null

    fun load() {
        if (ad != null || loading || showing) return
        loading = true
        RewardedAd.load(activity, AD_UNIT_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(loaded: RewardedAd) {
                loading = false
                ad = loaded
                onStateChanged?.invoke()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loading = false
                ad = null
                Log.w(TAG, "Anúncio não carregou: ${error.code} ${error.message}")
                onStateChanged?.invoke()
            }
        })
    }

    /** Mostra o anúncio; [onResult] recebe true somente se a recompensa foi ganha. */
    fun show(onResult: (Boolean) -> Unit) {
        val current = ad
        if (current == null) {
            load()
            onResult(false)
            return
        }
        ad = null
        showing = true
        var earned = false
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                onResult(earned)
                load()
                onStateChanged?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "Falha ao exibir anúncio: ${error.message}")
                onResult(false)
                load()
                onStateChanged?.invoke()
            }
        }
        current.show(activity) { earned = true }
    }

    private companion object {
        const val TAG = "RewardedAds"
        const val AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}
