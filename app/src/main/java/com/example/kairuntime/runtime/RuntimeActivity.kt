package com.example.kairuntime.runtime

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File

class RuntimeActivity : Activity() {
    private lateinit var app: InstalledKaiApp
    private lateinit var server: AppHttpServer
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var console: TextView
    private var port: WebExtension.Port? = null
    private var runtimeExtension: WebExtension? = null
    private var pendingLocationCallback: String? = null
    private var pendingNotification: PendingNotification? = null
    private var pendingGeckoPermission: GeckoSession.PermissionDelegate.Callback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val consoleLines = ArrayDeque<String>()

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
        try {
            val runtimeScript = assets.open("extensions/kai-runtime/page-runtime.js").use { it.readBytes() }
            server = AppHttpServer(File(app.installationPath), app.port, runtimeScript)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start app server: ${error.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val geckoView = GeckoView(this)
        setContentView(createContent(geckoView))
        runtime = (application as KaiRuntimeApplication).geckoRuntime
        session = GeckoSession()
        session.permissionDelegate = permissionDelegate
        session.open(runtime)
        geckoView.setSession(session)
        installRuntimeExtension()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            finish()
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
                else respond(it, false, error = "Android location permission denied")
            }
            REQUEST_NOTIFICATIONS -> pendingNotification?.also {
                pendingNotification = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showNotification(it)
                else respond(it.callbackId, false, error = "Android notification permission denied")
            }
            REQUEST_GECKO_PERMISSION -> pendingGeckoPermission?.also {
                pendingGeckoPermission = null
                if (grantResults.isNotEmpty() && grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }) it.grant()
                else it.reject()
            }
        }
    }

    override fun onDestroy() {
        runtimeExtension?.let { session.webExtensionController.setMessageDelegate(it, null, NATIVE_APP) }
        port?.disconnect()
        if (::session.isInitialized) session.close()
        if (::server.isInitialized) server.close()
        super.onDestroy()
    }

    private fun createContent(geckoView: GeckoView): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@RuntimeActivity).apply {
                text = app.name
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(compactButton("Reload") { session.reload() })
            addView(compactButton("Close") { finish() })
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        root.addView(FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(Color.rgb(66, 77, 71), dp(8).toFloat())
            addView(geckoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(dp(246), dp(326)))
        root.addView(createKeypad(), LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        console = TextView(this).apply {
            setTextColor(Color.rgb(163, 224, 192))
            setBackgroundColor(Color.rgb(7, 12, 10))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            text = "[RUNTIME] Starting ${app.name}"
        }
        root.addView(ScrollView(this).apply { addView(console) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ).apply { topMargin = dp(8) })
        return root
    }

    private fun createKeypad(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(keyRow(listOf("SoftL" to "SoftLeft", "OK" to "Enter", "SoftR" to "SoftRight")))
        addView(keyRow(listOf("Left" to "ArrowLeft", "Up" to "ArrowUp", "Right" to "ArrowRight")))
        addView(keyRow(listOf("Back" to "Backspace", "Down" to "ArrowDown", "End" to "EndCall")))
        addView(keyRow(listOf("1" to "1", "2" to "2", "3" to "3")))
        addView(keyRow(listOf("4" to "4", "5" to "5", "6" to "6")))
        addView(keyRow(listOf("7" to "7", "8" to "8", "9" to "9")))
        addView(keyRow(listOf("*" to "*", "0" to "0", "#" to "#")))
    }

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
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
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

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(connectedPort: WebExtension.Port) {
            val sender = connectedPort.sender
            val expectedOrigin = "http://127.0.0.1:${app.port}/"
            if (connectedPort.name != NATIVE_APP || sender.session !== session ||
                !sender.isTopLevel || !sender.url.startsWith(expectedOrigin)) {
                log("[ERROR] Rejected bridge connection from ${sender.url}")
                connectedPort.disconnect()
                return
            }
            port = connectedPort
            log("[RUNTIME] Bridge connected")
            connectedPort.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, sourcePort: WebExtension.Port) {
                    val json = message as? JSONObject ?: return
                    when (json.optString("type")) {
                        "api" -> handleApi(json)
                        "console" -> log("[${json.optString("level", "log").uppercase()}] ${json.optString("message")}")
                        "error" -> log("[ERROR] ${json.optString("message")}")
                        "focus" -> log("[FOCUS] ${json.optString("element")}")
                    }
                }

                override fun onDisconnect(disconnectedPort: WebExtension.Port) {
                    if (port === disconnectedPort) port = null
                }
            })
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            geckoSession: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val expectedOrigin = "http://127.0.0.1:${app.port}/"
            val allowed = permission.uri.startsWith(expectedOrigin) && when (permission.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> hasManifestPermission("geolocation")
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                    hasManifestPermission("desktop-notification", "notifications")
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
            val locationOnly = requested.isNotEmpty() && requested.all {
                it == Manifest.permission.ACCESS_COARSE_LOCATION || it == Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (!locationOnly || !hasManifestPermission("geolocation")) {
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
    }

    private fun handleApi(request: JSONObject) {
        val callbackId = request.optString("callbackId")
        val api = request.optString("api")
        if (callbackId.isBlank() || api.isBlank()) return
        log("[API] $api")
        when (api) {
            "battery" -> respond(callbackId, true, JSONObject().put("level", batteryLevel()))
            "network" -> respond(callbackId, true, JSONObject().put("type", networkType()))
            "vibrate" -> {
                if (!requirePermission(callbackId, "vibration")) return
                val duration = request.optJSONObject("args")?.optLong("duration", 200L) ?: 200L
                vibrate(duration.coerceIn(1L, 10_000L))
                respond(callbackId, true, JSONObject().put("vibrated", true))
            }
            "location" -> {
                if (!requirePermission(callbackId, "geolocation")) return
                requestLocation(callbackId)
            }
            "notification" -> {
                if (!requirePermission(callbackId, "desktop-notification", "notifications")) return
                val args = request.optJSONObject("args") ?: JSONObject()
                requestNotification(PendingNotification(callbackId, args.optString("title", app.name), args.optString("body")))
            }
            else -> respond(callbackId, false, error = "API '$api' is unavailable")
        }
    }

    private fun requirePermission(callbackId: String, vararg names: String): Boolean {
        if (hasManifestPermission(*names)) {
            log("[PERMISSION] ${names.first()}: granted")
            return true
        }
        log("[PERMISSION] ${names.first()}: denied")
        respond(callbackId, false, error = "Manifest permission '${names.first()}' is required")
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

    private fun requestLocation(callbackId: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingLocationCallback = callbackId
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
            respond(callbackId, false, error = "No Android location provider is enabled")
            return
        }
        var completed = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return
                completed = true
                manager.removeUpdates(this)
                respond(callbackId, true, JSONObject()
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
                respond(callbackId, false, error = "Location request timed out")
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
        respond(notification.callbackId, true, JSONObject().put("shown", true))
    }

    private fun sendKey(key: String, phase: String, repeat: Boolean) {
        log("[KEY] $key${if (repeat) " (repeat)" else ""}")
        port?.postMessage(JSONObject().put("type", "key").put("key", key).put("phase", phase).put("repeat", repeat))
    }

    private fun respond(callbackId: String, success: Boolean, data: JSONObject? = null, error: String? = null) {
        port?.postMessage(JSONObject().put("type", "response").put("callbackId", callbackId)
            .put("success", success).put("data", data ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL))
    }

    private fun log(line: String) {
        runOnUiThread {
            consoleLines.addLast(line)
            while (consoleLines.size > 40) consoleLines.removeFirst()
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
        setPadding(dp(8), 0, dp(8), 0)
        background = null
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class PendingNotification(val callbackId: String, val title: String, val body: String)

    companion object {
        const val EXTRA_APP_ID = "app_id"
        private const val EXTENSION_URI = "resource://android/assets/extensions/kai-runtime/"
        private const val EXTENSION_ID = "kai-runtime@example.com"
        private const val NATIVE_APP = "kaiRuntime"
        private const val CHANNEL_ID = "kai_apps"
        private const val REQUEST_LOCATION = 201
        private const val REQUEST_NOTIFICATIONS = 202
        private const val REQUEST_GECKO_PERMISSION = 203
        private const val KEY_LONG_PRESS_MS = 450L
        private const val KEY_REPEAT_MS = 90L
        private const val LOCATION_TIMEOUT_MS = 10_000L
        private val BACKGROUND = Color.rgb(12, 18, 16)
        private val ACCENT = Color.rgb(242, 184, 75)
    }
}
