package com.example.kairuntime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.kairuntime.database.AppDatabase
import com.example.kairuntime.database.InstalledKaiApp
import com.example.kairuntime.installer.KaiPackageInstaller
import com.example.kairuntime.runtime.RuntimeActivity
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var appList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PACKAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install(uri)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BACKGROUND) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "KAI RUNTIME"
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .16f
        })
        root.addView(TextView(this).apply {
            text = "Your feature-phone lab"
            setTextColor(Color.WHITE)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(20))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("Import ZIP") { choosePackage() }, weightedParams())
        actions.addView(actionButton("Settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }, weightedParams(leftMargin = dp(10)))
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "INSTALLED APPS"
            setTextColor(MUTED)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(28), 0, dp(10))
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appList)
        scroll.addView(root)
        return scroll
    }

    private fun refreshApps() {
        executor.execute {
            val apps = AppDatabase(this).use { it.getAll() }
            runOnUiThread { renderApps(apps) }
        }
    }

    private fun renderApps(apps: List<InstalledKaiApp>) {
        appList.removeAllViews()
        if (apps.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "No applications installed. Import a packaged KaiOS ZIP to begin."
                setTextColor(MUTED)
                textSize = 16f
                setPadding(dp(4), dp(14), dp(4), dp(14))
            })
            return
        }
        apps.forEach { app -> appList.addView(appRow(app), marginParams(bottom = dp(10))) }
    }

    private fun appRow(app: InstalledKaiApp): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(CARD, dp(12).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener {
            startActivity(Intent(this@MainActivity, RuntimeActivity::class.java).putExtra(RuntimeActivity.EXTRA_APP_ID, app.id))
        }
        setOnLongClickListener {
            confirmRemoval(app)
            true
        }

        addView(ImageView(this@MainActivity).apply {
            setBackgroundColor(Color.rgb(33, 50, 44))
            scaleType = ImageView.ScaleType.CENTER_CROP
            app.iconPath?.let { path ->
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) setImageBitmap(bitmap)
            }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = app.name
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = listOfNotNull("Packaged", app.version).joinToString("  •  ")
                setTextColor(MUTED)
                textSize = 13f
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "OPEN"
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun confirmRemoval(app: InstalledKaiApp) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${app.name}?")
            .setMessage("The package and its runtime data will be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                executor.execute {
                    File(app.installationPath).deleteRecursively()
                    AppDatabase(this).use { it.delete(app.id) }
                    runOnUiThread { refreshApps() }
                }
            }
            .show()
    }

    private fun choosePackage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_PACKAGE)
    }

    private fun install(uri: Uri) {
        Toast.makeText(this, "Validating package…", Toast.LENGTH_SHORT).show()
        executor.execute {
            runCatching { KaiPackageInstaller(this).install(uri) }
                .onSuccess { runOnUiThread { Toast.makeText(this, "Installed ${it.name}", Toast.LENGTH_SHORT).show(); refreshApps() } }
                .onFailure { error -> runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Installation failed")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                } }
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(BACKGROUND)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(ACCENT, dp(10).toFloat())
        setOnClickListener { action() }
    }

    private fun weightedParams(leftMargin: Int = 0) = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
        this.leftMargin = leftMargin
    }

    private fun marginParams(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = bottom }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PACKAGE = 100
        private val BACKGROUND = Color.rgb(16, 22, 20)
        private val CARD = Color.rgb(24, 34, 30)
        private val ACCENT = Color.rgb(242, 184, 75)
        private val MUTED = Color.rgb(166, 180, 173)
    }
}
