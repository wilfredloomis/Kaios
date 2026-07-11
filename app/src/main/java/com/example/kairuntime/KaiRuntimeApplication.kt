package com.example.kairuntime

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class KaiRuntimeApplication : Application() {
    val geckoRuntime: GeckoRuntime by lazy(LazyThreadSafetyMode.NONE) {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(true)
            .remoteDebuggingEnabled(false)
            .javaScriptEnabled(true)
            .aboutConfigEnabled(false)
            // KaiOS feature-phone profile: fixed 240x320 CSS viewport.
            .screenSizeOverride(240, 320)
            .displayDensityOverride(1.0f)
            .inputAutoZoomEnabled(false)
            .doubleTapZoomingEnabled(false)
            .forceUserScalableEnabled(false)
            .loginAutofillEnabled(false)
            .webManifest(true)
            // Packaged apps are served over loopback HTTP and frequently talk to
            // mixed http/https APIs. Allow all connection types (KaiOS never enforced
            // HTTPS-only).
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            .enterpriseRootsEnabled(true)
            .build()
        GeckoRuntime.create(this, settings)
    }

    companion object {
        /**
         * Mimics a Nokia 8110 4G (KaiOS 2.5) browser string. Apps such as
         * Telegram, Facebook, WhatsApp, and Google ports branch on `KAIOS` /
         * `Mobile` / `Firefox/48` tokens for layout and API selection.
         */
        const val KAIOS_USER_AGENT =
            "Mozilla/5.0 (Mobile; rv:48.0) Gecko/48.0 Firefox/48.0 KAIOS/2.5"
    }
}
