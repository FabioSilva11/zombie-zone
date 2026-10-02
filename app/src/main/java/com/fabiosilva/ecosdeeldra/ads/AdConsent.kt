package com.fabiosilva.ecosdeeldra.ads

import android.app.Activity
import android.util.Log
import com.fabiosilva.ecosdeeldra.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Consentimento de privacidade (UMP) + inicialização do SDK do AdMob.
 * Segue o guia de início rápido: pede o consentimento quando exigido (EEE/Reino Unido),
 * só depois inicializa o SDK em segundo plano e então carrega os anúncios.
 * A mensagem de consentimento é configurada no AdMob em "Privacidade e mensagens".
 */
object AdConsent {
    private const val TAG = "AdConsent"
    private val initializing = AtomicBoolean(false)
    @Volatile var ready = false
        private set

    fun canRequestAds(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()

    fun gather(activity: Activity, onReady: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "Formulário de consentimento: ${error.message}")
                    if (info.canRequestAds()) initialize(activity, onReady)
                }
            },
            { error ->
                Log.w(TAG, "Falha ao atualizar o consentimento: ${error.message}")
                if (info.canRequestAds()) initialize(activity, onReady)
            },
        )
        // Consentimento de sessões anteriores: já pode carregar enquanto atualiza.
        if (info.canRequestAds()) initialize(activity, onReady)
    }

    private fun initialize(activity: Activity, onReady: () -> Unit) {
        if (ready) {
            activity.runOnUiThread(onReady)
            return
        }
        if (!initializing.compareAndSet(false, true)) return
        val testDevices = BuildConfig.ADMOB_TEST_DEVICES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTestDeviceIds(testDevices)
                // Jogo de tiro com zumbis: anúncios adequados para adolescentes.
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
                .build(),
        )
        val context = activity.applicationContext
        Thread {
            MobileAds.initialize(context) {
                ready = true
                activity.runOnUiThread(onReady)
            }
        }.start()
    }

    fun privacyOptionsRequired(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Opções de privacidade: ${error.message}")
        }
    }
}
