package com.example.kairuntime.runtime

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.kairuntime.KaiRuntimeApplication
import com.example.kairuntime.R
import com.example.kairuntime.database.AppDatabase
import com.example.kairuntime.database.InstalledKaiApp
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.net.BindException
import java.net.ServerSocket
import kotlin.math.max

class RuntimeActivity : Activity() {
    private lateinit var app: InstalledKaiApp
    private lateinit var server: AppHttpServer
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private var geckoView: GeckoView? = null
    private lateinit var console: TextView
    private val ports = linkedSetOf<WebExtension.Port>()
    private val trustedPorts = linkedSetOf<WebExtension.Port>()
    private var runtimeExtension: WebExtension? = null
    private var pendingLocationCallback: PendingApiCallback? = null
    private var pendingNotification: PendingNotification? = null
    private var pendingGeckoPermission: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var canGoBack = false
    private val handler = Handler(Looper.getMainLooper())
    private val consoleLines = ArrayDeque<String>()
    private lateinit var toolbarView: LinearLayout
    private lateinit var appSurface: FrameLayout
    private lateinit var keypadContainer: LinearLayout
    private lateinit var consoleContainer: ScrollView
    private lateinit var toggleKeysButton: Button
    private lateinit var toggleLogButton: Button
    private var keypadVisible = false
    private var consoleVisible = false
    private var toolbarVisible = true
    private val hideToolbarRunnable = Runnable { setToolbarVisible(false) }
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val installed = appId?.let { AppDatabase(this).use { db -> db.get(it) } }
        if (installed == null) {
            Toast.makeText(this, "Application is no longer installed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        app = installed
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        try {
            val runtimeScript = assets.open("extensions/kai-runtime/page-runtime.js").use { it.readBytes() }
            server = try {
                AppHttpServer(File(app.installationPath), app.port, runtimeScript)
            } catch (_: BindException) {
                val replacementPort = allocateReplacementPort()
                AppDatabase(this).use { it.updatePort(app.id, replacementPort) }
                app = app.copy(port = replacementPort)
                AppHttpServer(File(app.installationPath), app.port, runtimeScript)
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start app server: ${error.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val appView = GeckoView(this)
        geckoView = appView
        setContentView(createContent(appView))
        applyImmersiveFullscreen()
        runtime = (application as KaiRuntimeApplication).geckoRuntime
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .userAgentOverride(KAIOS_USER_AGENT)
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
                .build(),
        )
        session.permissionDelegate = permissionDelegate
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.promptDelegate = promptDelegate
        session.open(runtime)
        appView.setSession(session)
        installRuntimeExtension()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (canGoBack) session.goBack() else finish()
            return true
        }
        val key = hardwareKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
        sendKey(key, if (event.action == KeyEvent.ACTION_DOWN) "down" else "up", event.repeatCount > 0)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION -> pendingLocationCallback?.also {
                pendingLocationCallback = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestLocation(it)
                else respond(it.port, it.callbackId, false, error = "Android location permission denied")
            }
            REQUEST_NOTIFICATIONS -> pendingNotification?.also {
                pendingNotification = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showNotification(it)
                else respond(it.port, it.callbackId, false, error = "Android notification permission denied")
            }
            REQUEST_GECKO_PERMISSION -> pendingGeckoPermission?.also {
                pendingGeckoPermission = null
                if (grantResults.isNotEmpty() && grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }) it.grant()
                else it.reject()
            }
        }
    }


    @Deprecated("Deprecated in Android; kept for GeckoView file picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_PICKER) return
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt == null || result == null) return

        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
        }
        data?.data?.let { if (it !in uris) uris += it }
        val response = when {
            resultCode != RESULT_OK || uris.isEmpty() -> prompt.dismiss()
            uris.size == 1 -> prompt.confirm(applicationContext, uris.first())
            else -> prompt.confirm(applicationContext, uris.toTypedArray())
        }
        result.complete(response)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::session.isInitialized) {
            runtimeExtension?.let { extension ->
                runCatching { session.webExtensionController.setMessageDelegate(extension, null, NATIVE_APP) }
            }
            session.permissionDelegate = null
            session.navigationDelegate = null
            session.contentDelegate = null
            session.promptDelegate = null
        }
        ports.toList().forEach { port -> runCatching { port.disconnect() } }
        ports.clear()
        trustedPorts.clear()
        runtimeExtension = null
        val filePrompt = pendingFilePrompt
        val fileResult = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (filePrompt != null && fileResult != null) runCatching { fileResult.complete(filePrompt.dismiss()) }
        pendingLocationCallback = null
        pendingNotification = null
        pendingGeckoPermission = null
        geckoView?.let { view -> runCatching { view.releaseSession() } }
        geckoView = null
        if (::session.isInitialized) runCatching { session.close() }
        if (::server.isInitialized) server.close()
        super.onDestroy()
    }

    private fun createContent(geckoView: GeckoView): View {
        geckoView.setBackgroundColor(Color.BLACK)
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true
        geckoView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                geckoView.requestFocus()
                if (keypadVisible) setKeypadVisible(false)
                if (event.y <= dp(52)) showToolbarTemporarily()
                else scheduleToolbarAutoHide()
            }
            false
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        appSurface = FrameLayout(this).apply {
            background = rounded(Color.rgb(10, 18, 26), dp(14).toFloat())
            clipToPadding = false
            clipChildren = true
            addView(geckoView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        root.addView(appSurface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        toolbarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.argb(210, 12, 18, 16), dp(10).toFloat())
            addView(TextView(this@RuntimeActivity).apply {
                text = app.name
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(compactButton("Reload") {
                showToolbarTemporarily()
                session.reload()
            })
            addView(compactButton("Close") { finish() })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) showToolbarTemporarily()
                false
            }
        }
        root.addView(toolbarView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
            Gravity.TOP,
        ).apply {
            setMargins(dp(8), dp(8), dp(8), 0)
        })

        console = TextView(this).apply {
            setTextColor(Color.rgb(163, 224, 192))
            setBackgroundColor(Color.rgb(7, 12, 10))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            text = "[RUNTIME] Starting ${app.name}"
        }
        consoleContainer = ScrollView(this).apply {
            visibility = View.GONE
            background = rounded(Color.argb(228, 7, 12, 10), dp(8).toFloat())
            addView(console)
        }
        root.addView(consoleContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150),
            Gravity.BOTTOM,
        ).apply {
            setMargins(dp(8), 0, dp(8), dp(58))
        })

        keypadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(Color.argb(224, 12, 18, 16), dp(12).toFloat())
            addView(TextView(this@RuntimeActivity).apply {
                text = "Virtual keypad"
                setTextColor(Color.rgb(190, 198, 194))
                textSize = 10.5f
                setPadding(dp(4), dp(2), dp(4), dp(4))
            })
            addView(createKeypad(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        root.addView(keypadContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            setMargins(dp(8), 0, dp(8), dp(58))
        })

        toggleKeysButton = dockButton("KEYS") { setKeypadVisible(!keypadVisible) }
        toggleLogButton = dockButton("LOG") { setConsoleVisible(!consoleVisible) }
        val controlsDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(Color.argb(205, 20, 30, 27), dp(18).toFloat())
            addView(toggleLogButton)
            addView(toggleKeysButton)
        }
        root.addView(controlsDock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })

        updateAppViewportLayout()
        toolbarView.post { updateAppViewportLayout() }
        refreshOverlayButtons()
        scheduleToolbarAutoHide()
        return root
    }

    private fun updateAppViewportLayout() {
        if (!::appSurface.isInitialized) return
        val topInset = if (toolbarVisible) {
            toolbarView.bottom.takeIf { it > 0 }?.plus(dp(8)) ?: dp(64)
        } else {
            dp(14)
        }
        val baseBottom = dp(58)
        val keypadInset = if (keypadVisible) {
            val rootHeight = (appSurface.parent as? View)?.height ?: 0
            if (rootHeight > 0) {
                val keypadRegionHeight = rootHeight * 2 / 5
                val keypadParams = keypadContainer.layoutParams as FrameLayout.LayoutParams
                keypadParams.height = max(0, keypadRegionHeight - dp(66))
                keypadContainer.layoutParams = keypadParams
                keypadRegionHeight
            } else {
                dp(310)
            }
        } else {
            0
        }
        val consoleInset = if (consoleVisible) consoleContainer.height + dp(66) else 0
        val bottomInset = max(baseBottom, max(keypadInset, consoleInset))
        val params = (appSurface.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        params.setMargins(dp(14), topInset, dp(14), bottomInset)
        appSurface.layoutParams = params
    }

    private fun createKeypad(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(keyRow(listOf("SoftL" to "SoftLeft", "OK" to "Enter", "SoftR" to "SoftRight")), weightedKeyRow())
        addView(keyRow(listOf("Left" to "ArrowLeft", "Up" to "ArrowUp", "Right" to "ArrowRight")), weightedKeyRow())
        addView(keyRow(listOf("Back" to "Backspace", "Down" to "ArrowDown", "End" to "EndCall")), weightedKeyRow())
        addView(keyRow(listOf("1" to "1", "2" to "2", "3" to "3")), weightedKeyRow())
        addView(keyRow(listOf("4" to "4", "5" to "5", "6" to "6")), weightedKeyRow())
        addView(keyRow(listOf("7" to "7", "8" to "8", "9" to "9")), weightedKeyRow())
        addView(keyRow(listOf("*" to "*", "0" to "0", "#" to "#")), weightedKeyRow())
    }

    private fun weightedKeyRow() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
    )

    private fun keyRow(keys: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        keys.forEach { (label, key) ->
            addView(Button(this@RuntimeActivity).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                background = rounded(Color.rgb(38, 51, 46), dp(7).toFloat())
                setOnTouchListener(repeatingKeyListener(key))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    private fun repeatingKeyListener(key: String) = View.OnTouchListener { view, event ->
        val repeat = object : Runnable {
            override fun run() {
                sendKey(key, "down", true)
                handler.postDelayed(this, KEY_REPEAT_MS)
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                sendKey(key, "down", false)
                view.setTag(R.id.key_repeat_tag, repeat)
                handler.postDelayed(repeat, KEY_LONG_PRESS_MS)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                (view.getTag(R.id.key_repeat_tag) as? Runnable)?.let(handler::removeCallbacks)
                sendKey(key, "up", false)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                true
            }
            else -> true
        }
    }

    private fun dockButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = rounded(Color.argb(225, 38, 51, 46), dp(16).toFloat())
        setOnClickListener { action() }
    }

    private fun setToolbarVisible(visible: Boolean) {
        if (!::toolbarView.isInitialized || toolbarVisible == visible) return
        toolbarVisible = visible
        handler.removeCallbacks(hideToolbarRunnable)
        toolbarView.animate().cancel()
        if (visible) {
            toolbarView.visibility = View.VISIBLE
            toolbarView.alpha = 0f
            toolbarView.translationY = -dp(18).toFloat()
            toolbarView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160L)
                .start()
        } else {
            toolbarView.animate()
                .alpha(0f)
                .translationY(-toolbarView.height.toFloat())
                .setDuration(180L)
                .withEndAction {
                    toolbarView.visibility = View.GONE
                    updateAppViewportLayout()
                }
                .start()
        }
        updateAppViewportLayout()
    }

    private fun showToolbarTemporarily() {
        setToolbarVisible(true)
        scheduleToolbarAutoHide()
    }

    private fun scheduleToolbarAutoHide() {
        handler.removeCallbacks(hideToolbarRunnable)
        if (toolbarVisible && !keypadVisible && !consoleVisible) {
            handler.postDelayed(hideToolbarRunnable, TOOLBAR_AUTO_HIDE_MS)
        }
    }

    private fun setKeypadVisible(visible: Boolean) {
        keypadVisible = visible
        if (::keypadContainer.isInitialized) keypadContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) showToolbarTemporarily() else scheduleToolbarAutoHide()
        updateAppViewportLayout()
        if (visible) keypadContainer.post { updateAppViewportLayout() }
        refreshOverlayButtons()
    }

    private fun setConsoleVisible(visible: Boolean) {
        consoleVisible = visible
        if (::consoleContainer.isInitialized) consoleContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) showToolbarTemporarily() else scheduleToolbarAutoHide()
        updateAppViewportLayout()
        if (visible) consoleContainer.post { updateAppViewportLayout() }
        refreshOverlayButtons()
    }

    private fun refreshOverlayButtons() {
        if (::toggleKeysButton.isInitialized) toggleKeysButton.text = if (keypadVisible) "APP" else "KEYS"
        if (::toggleLogButton.isInitialized) toggleLogButton.text = if (consoleVisible) "HIDE LOG" else "LOG"
    }

    private fun applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        requestMediaAudioFocus()
        if (::toolbarView.isInitialized) showToolbarTemporarily()
    }

    override fun onPause() {
        abandonMediaAudioFocus()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
            scheduleToolbarAutoHide()
        }
    }


    private fun requestMediaAudioFocus() {
        if (!::audioManager.isInitialized) return
        if (Build.VERSION.SDK_INT >= 26) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(true)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonMediaAudioFocus() {
        if (!::audioManager.isInitialized) return
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun installRuntimeExtension() {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept({ extension ->
            if (extension == null) {
                log("[ERROR] Runtime extension was not installed")
                return@accept
            }
            runtimeExtension = extension
            session.webExtensionController.setMessageDelegate(extension, messageDelegate, NATIVE_APP)
            val url = "http://127.0.0.1:${app.port}/${app.launchPath}"
            log("[APP] Opened /${app.launchPath}")
            session.loadUri(url)
        }, { error ->
            log("[ERROR] Runtime injection failed: ${error?.message ?: "unknown error"}")
        })
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(geckoSession: GeckoSession, value: Boolean) {
            canGoBack = value
        }

        override fun onLoadRequest(
            geckoSession: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                geckoSession.loadUri(uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            val scheme = runCatching { Uri.parse(uri).scheme?.lowercase() }.getOrNull()
            if (scheme != null && scheme !in setOf("http", "https", "about", "data", "blob")) {
                val handled = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                    true
                }.getOrDefault(false)
                if (handled) return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return null
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onCloseRequest(geckoSession: GeckoSession) {
            finish()
        }

        @Suppress("DEPRECATION")
        override fun onFullScreen(geckoSession: GeckoSession, fullScreen: Boolean) {
            window.decorView.systemUiVisibility = if (fullScreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        override fun onCrash(geckoSession: GeckoSession) {
            log("[ERROR] Gecko content process crashed")
            Toast.makeText(this@RuntimeActivity, "Web process crashed. Reloading…", Toast.LENGTH_LONG).show()
            runCatching {
                geckoSession.open(runtime)
                geckoSession.loadUri("http://127.0.0.1:${app.port}/${app.launchPath}")
            }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(connectedPort: WebExtension.Port) {
            val sender = connectedPort.sender
            val expectedOrigin = "http://127.0.0.1:${app.port}/"
            val supportedUrl = sender.url.startsWith("http://") || sender.url.startsWith("https://")
            if (connectedPort.name != NATIVE_APP || sender.session !== session || !sender.isTopLevel || !supportedUrl) {
                log("[ERROR] Rejected bridge connection from ${sender.url}")
                connectedPort.disconnect()
                return
            }
            ports.add(connectedPort)
            if (sender.url.startsWith(expectedOrigin)) trustedPorts.add(connectedPort)
            runCatching {
                connectedPort.postMessage(JSONObject()
                    .put("type", "bridge-status")
                    .put("trusted", connectedPort in trustedPorts))
            }
            log("[RUNTIME] Bridge connected: ${if (connectedPort in trustedPorts) "app" else "web"}")
            connectedPort.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, sourcePort: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    when (json.optString("type")) {
                        "api" -> handleApi(json, sourcePort)
                        "console" -> log("[${json.optString("level", "log").uppercase()}] ${json.optString("message")}")
                        "error" -> log("[ERROR] ${json.optString("message")}")
                        "focus" -> log("[FOCUS] ${json.optString("element")}")
                    }
                }

                override fun onDisconnect(disconnectedPort: WebExtension.Port) {
                    ports.remove(disconnectedPort)
                    trustedPorts.remove(disconnectedPort)
                }
            })
        }
    }

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(
            geckoSession: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            pendingFilePrompt?.let { oldPrompt ->
                pendingFileResult?.complete(oldPrompt.dismiss())
            }
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            pendingFilePrompt = prompt
            pendingFileResult = result

            val mimeTypes = prompt.mimeTypes?.filter(String::isNotBlank).orEmpty()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                type = mimeTypes.singleOrNull() ?: "*/*"
                if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                )
            }
            return try {
                startActivityForResult(picker, REQUEST_FILE_PICKER)
                result
            } catch (error: Exception) {
                pendingFilePrompt = null
                pendingFileResult = null
                result.complete(prompt.dismiss())
                log("[ERROR] File picker unavailable: ${error.message}")
                result
            }
        }

        override fun onPopupPrompt(
            geckoSession: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            geckoSession: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val standardWebOrigin = isStandardWebOrigin(permission.uri)
            val allowed = when (permission.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                    standardWebOrigin && hasManifestPermission("geolocation")
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                    standardWebOrigin && hasManifestPermission("desktop-notification", "notifications")
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> true
                else -> false
            }
            log("[PERMISSION] web ${permission.permission}: ${if (allowed) "granted" else "denied"}")
            return GeckoResult.fromValue(
                if (allowed) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }

        override fun onAndroidPermissionsRequest(
            geckoSession: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val requested = permissions?.toList().orEmpty()
            val allowed = requested.isNotEmpty() && requested.all { permission ->
                when (permission) {
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION -> hasManifestPermission("geolocation")
                    Manifest.permission.CAMERA -> hasManifestPermission("camera")
                    Manifest.permission.RECORD_AUDIO -> hasManifestPermission("audio-capture", "microphone")
                    else -> false
                }
            }
            if (!allowed) {
                callback.reject()
                return
            }
            val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                callback.grant()
            } else {
                pendingGeckoPermission?.reject()
                pendingGeckoPermission = callback
                requestPermissions(missing.toTypedArray(), REQUEST_GECKO_PERMISSION)
            }
        }

        override fun onMediaPermissionRequest(
            geckoSession: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val trustedOrigin = isStandardWebOrigin(uri)
            val videoAllowed = video.isNullOrEmpty() || hasManifestPermission("camera")
            val audioAllowed = audio.isNullOrEmpty() || hasManifestPermission("audio-capture", "microphone")
            if (!trustedOrigin || !videoAllowed || !audioAllowed) {
                log("[PERMISSION] media capture: denied")
                callback.reject()
                return
            }
            log("[PERMISSION] media capture: granted")
            callback.grant(video?.firstOrNull(), audio?.firstOrNull())
        }
    }

    private fun handleApi(request: JSONObject, sourcePort: WebExtension.Port) {
        val callbackId = request.optString("callbackId")
        val api = request.optString("api")
        if (callbackId.isBlank() || api.isBlank()) return
        if (sourcePort !in trustedPorts) {
            respond(sourcePort, callbackId, false, error = "Native KaiOS APIs are restricted to the installed app origin")
            return
        }
        log("[API] $api")
        when (api) {
            "battery" -> respond(sourcePort, callbackId, true, JSONObject().put("level", batteryLevel()))
            "network" -> respond(sourcePort, callbackId, true, JSONObject().put("type", networkType()))
            "vibrate" -> {
                if (!requirePermission(sourcePort, callbackId, "vibration")) return
                val duration = request.optJSONObject("args")?.optLong("duration", 200L) ?: 200L
                vibrate(duration.coerceIn(1L, 10_000L))
                respond(sourcePort, callbackId, true, JSONObject().put("vibrated", true))
            }
            "location" -> {
                if (!requirePermission(sourcePort, callbackId, "geolocation")) return
                requestLocation(PendingApiCallback(callbackId, sourcePort))
            }
            "notification" -> {
                if (!requirePermission(sourcePort, callbackId, "desktop-notification", "notifications")) return
                val args = request.optJSONObject("args") ?: JSONObject()
                requestNotification(PendingNotification(
                    callbackId,
                    sourcePort,
                    args.optString("title", app.name),
                    args.optString("body"),
                ))
            }
            else -> respond(sourcePort, callbackId, false, error = "API '$api' is unavailable")
        }
    }

    private fun requirePermission(
        sourcePort: WebExtension.Port,
        callbackId: String,
        vararg names: String,
    ): Boolean {
        if (hasManifestPermission(*names)) {
            log("[PERMISSION] ${names.first()}: granted")
            return true
        }
        log("[PERMISSION] ${names.first()}: denied")
        respond(sourcePort, callbackId, false, error = "Manifest permission '${names.first()}' is required")
        return false
    }

    private fun hasManifestPermission(vararg names: String): Boolean {
        val permissions = runCatching { JSONObject(app.permissionsJson) }.getOrDefault(JSONObject())
        return names.any(permissions::has)
    }

    private fun batteryLevel(): Double {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return (manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)) / 100.0
    }

    @Suppress("DEPRECATION")
    private fun networkType(): String {
        val info = (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        return if (info?.isConnected == true) info.typeName.lowercase() else "offline"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(duration)
    }

    private fun requestLocation(callback: PendingApiCallback) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingLocationCallback = callback
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            respond(callback.port, callback.callbackId, false, error = "No Android location provider is enabled")
            return
        }
        var completed = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return
                completed = true
                manager.removeUpdates(this)
                respond(callback.port, callback.callbackId, true, JSONObject()
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("accuracy", location.accuracy))
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        @Suppress("MissingPermission")
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        handler.postDelayed({
            if (completed) return@postDelayed
            manager.removeUpdates(listener)
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) listener.onLocationChanged(last)
            else {
                completed = true
                respond(callback.port, callback.callbackId, false, error = "Location request timed out")
            }
        }, LOCATION_TIMEOUT_MS)
    }

    private fun requestNotification(notification: PendingNotification) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotification = notification
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        showNotification(notification)
    }

    private fun showNotification(notification: PendingNotification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "KaiOS applications", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val built = builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .build()
        manager.notify(app.id.hashCode(), built)
        respond(notification.port, notification.callbackId, true, JSONObject().put("shown", true))
    }

    private fun sendKey(key: String, phase: String, repeat: Boolean) {
        log("[KEY] $key${if (repeat) " (repeat)" else ""}")
        val message = JSONObject()
            .put("type", "key")
            .put("key", key)
            .put("phase", phase)
            .put("repeat", repeat)
        ports.toList().forEach { connectedPort ->
            runCatching { connectedPort.postMessage(message) }
        }
    }

    private fun respond(
        targetPort: WebExtension.Port,
        callbackId: String,
        success: Boolean,
        data: JSONObject? = null,
        error: String? = null,
    ) {
        if (targetPort !in ports) return
        runCatching {
            targetPort.postMessage(JSONObject()
                .put("type", "response")
                .put("callbackId", callbackId)
                .put("success", success)
                .put("data", data ?: JSONObject.NULL)
                .put("error", error ?: JSONObject.NULL))
        }
    }

    private fun allocateReplacementPort(): Int {
        val used = AppDatabase(this).use { database -> database.getAll().mapTo(mutableSetOf()) { it.port } }
        repeat(64) {
            val candidate = ServerSocket(0).use { socket -> socket.localPort }
            if (candidate !in used) return candidate
        }
        error("Could not allocate a replacement localhost port")
    }

    private fun installedOrigin() = "http://127.0.0.1:${app.port}/"

    private fun isStandardWebOrigin(uri: String): Boolean =
        uri.startsWith(installedOrigin()) || uri.startsWith("https://")

    private fun log(line: String) {
        runOnUiThread {
            consoleLines.addLast(line)
            while (consoleLines.size > 80) consoleLines.removeFirst()
            if (::console.isInitialized) console.text = consoleLines.joinToString("\n")
        }
    }

    private fun hardwareKey(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_0 -> "0"
        KeyEvent.KEYCODE_1 -> "1"
        KeyEvent.KEYCODE_2 -> "2"
        KeyEvent.KEYCODE_3 -> "3"
        KeyEvent.KEYCODE_4 -> "4"
        KeyEvent.KEYCODE_5 -> "5"
        KeyEvent.KEYCODE_6 -> "6"
        KeyEvent.KEYCODE_7 -> "7"
        KeyEvent.KEYCODE_8 -> "8"
        KeyEvent.KEYCODE_9 -> "9"
        KeyEvent.KEYCODE_STAR -> "*"
        KeyEvent.KEYCODE_POUND -> "#"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        else -> null
    }

    private fun compactButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(ACCENT)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = null
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class PendingApiCallback(
        val callbackId: String,
        val port: WebExtension.Port,
    )

    private data class PendingNotification(
        val callbackId: String,
        val port: WebExtension.Port,
        val title: String,
        val body: String,
    )

    companion object {
        const val EXTRA_APP_ID = "app_id"
        private const val EXTENSION_URI = "resource://android/assets/extensions/kai-runtime/"
        private const val EXTENSION_ID = "kai-runtime@example.com"
        private const val NATIVE_APP = "kaiRuntime"
        private const val CHANNEL_ID = "kai_apps"
        private const val KAIOS_USER_AGENT =
            "Mozilla/5.0 (Mobile; KaiOS 3.0; rv:150.0) Gecko/150.0 Firefox/150.0"
        private const val REQUEST_LOCATION = 201
        private const val REQUEST_NOTIFICATIONS = 202
        private const val REQUEST_GECKO_PERMISSION = 203
        private const val REQUEST_FILE_PICKER = 204
        private const val KEY_LONG_PRESS_MS = 450L
        private const val KEY_REPEAT_MS = 90L
        private const val TOOLBAR_AUTO_HIDE_MS = 3_200L
        private const val LOCATION_TIMEOUT_MS = 10_000L
        private val BACKGROUND = Color.rgb(12, 18, 16)
        private val ACCENT = Color.rgb(242, 184, 75)
    }
}
