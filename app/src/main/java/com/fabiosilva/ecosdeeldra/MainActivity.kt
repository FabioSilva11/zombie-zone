package com.fabiosilva.ecosdeeldra

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.view.View
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.net.URI
import java.net.URLDecoder

object NavigationPolicy {
    private const val LOCAL_HOST = "appassets.androidplatform.net"
    private const val GAME_PATH_PREFIX = "/assets/game/"

    fun isAllowedTopLevel(url: String): Boolean {
        return runCatching {
            val uri = URI(url)
            val decodedPath = URLDecoder.decode(uri.rawPath.orEmpty(), Charsets.UTF_8.name())
            uri.scheme == "https" &&
                uri.host == LOCAL_HOST &&
                decodedPath.startsWith(GAME_PATH_PREFIX) &&
                !decodedPath.split('/').contains("..")
        }.getOrDefault(false)
    }
}

object GameLifecycleBridge {
    fun scriptFor(active: Boolean): String = if (active) {
        "window.dispatchEvent(new Event('zombie-zone-app-resume'))"
    } else {
        "window.dispatchEvent(new Event('zombie-zone-app-pause'))"
    }
}

object AdDisplayPolicy {
    fun shouldEnterImmersiveMode(hasWindowFocus: Boolean, rewardedAdShowing: Boolean): Boolean =
        hasWindowFocus && !rewardedAdShowing
}

class MainActivity : ComponentActivity() {
    private lateinit var gameView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var rewardedAd: RewardedAd? = null
    private var isRewardedAdLoading = false
    private var isRewardedAdShowing = false
    private var pendingRewardCallbackId: String? = null
    private var pendingRewardGrant = false

    private fun loadRewardedAd() {
        if (rewardedAd != null || isRewardedAdLoading || isRewardedAdShowing) return
        isRewardedAdLoading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            REWARDED_AD_UNIT_ID,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isRewardedAdLoading = false
                    ad.setImmersiveMode(false)
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            finishRewardedAd(
                                granted = pendingRewardGrant,
                                reason = if (pendingRewardGrant) "earned" else "dismissed",
                            )
                        }

                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            android.util.Log.w(
                                "EcosWeb",
                                "Rewarded ad failed to show: ${error.code} ${error.message}",
                            )
                            finishRewardedAd(granted = false, reason = "show-failed")
                        }
                    }
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isRewardedAdLoading = false
                    rewardedAd = null
                    android.util.Log.w("EcosWeb", "Rewarded ad failed to load: ${error.message}")
                }
            },
        )
    }

    private fun finishRewardedAd(granted: Boolean, reason: String) {
        val callbackId = pendingRewardCallbackId
        pendingRewardCallbackId = null
        pendingRewardGrant = false
        isRewardedAdShowing = false
        if (callbackId != null) deliverRewardResult(callbackId, granted, reason)
        enterImmersiveMode()
        loadRewardedAd()
    }

    private fun deliverRewardResult(callbackId: String, granted: Boolean, reason: String = "") {
        val js = "window.__rewardedAdBridge?.resolve(${jsString(callbackId)}, ${granted.toString()}, ${jsString(reason)})"
        gameView.post { gameView.evaluateJavascript(js, null) }
    }

    private fun jsString(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    inner class AdBridge {
        @JavascriptInterface
        fun showRewarded(callbackId: String) {
            runOnUiThread {
                val ad = rewardedAd
                if (ad == null) {
                    pendingRewardCallbackId = callbackId
                    deliverRewardResult(callbackId, false, "ad-not-ready")
                    loadRewardedAd()
                    return@runOnUiThread
                }
                rewardedAd = null
                pendingRewardCallbackId = callbackId
                pendingRewardGrant = false
                isRewardedAdShowing = true
                exitImmersiveMode()
                ad.setImmersiveMode(false)
                ad.show(this@MainActivity) { _: RewardItem ->
                    pendingRewardGrant = true
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        gameView = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 11, 20))
            isLongClickable = false
            setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "EcosWeb",
                        "${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                    )
                    return true
                }
            }
            addJavascriptInterface(AdBridge(), "AndroidAds")
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = request.isForMainFrame &&
                    !NavigationPolicy.isAllowedTopLevel(request.url.toString())
            }
        }

        setContentView(gameView)
        loadRewardedAd()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gameView.canGoBack()) {
                    gameView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        gameView.loadUrl(GAME_URL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (AdDisplayPolicy.shouldEnterImmersiveMode(hasFocus, isRewardedAdShowing)) {
            enterImmersiveMode()
        } else if (isRewardedAdShowing) {
            exitImmersiveMode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::gameView.isInitialized) {
            gameView.onResume()
            gameView.resumeTimers()
            gameView.evaluateJavascript(GameLifecycleBridge.scriptFor(active = true), null)
        }
    }

    override fun onPause() {
        if (::gameView.isInitialized) {
            gameView.evaluateJavascript(GameLifecycleBridge.scriptFor(active = false), null)
            gameView.onPause()
            gameView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::gameView.isInitialized) gameView.destroy()
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun exitImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    private companion object {
        const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        const val GAME_URL = "https://appassets.androidplatform.net/assets/game/index.html"
    }
}
