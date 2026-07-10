package com.example.kairuntime

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class KaiRuntimeApplication : Application() {
    val geckoRuntime: GeckoRuntime by lazy(LazyThreadSafetyMode.NONE) {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(false)
            .remoteDebuggingEnabled(false)
            .screenSizeOverride(240, 320)
            .displayDensityOverride(resources.displayMetrics.density)
            .build()
        GeckoRuntime.create(this, settings)
    }
}
