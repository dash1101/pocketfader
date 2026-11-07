package app.pocketfader.legacy

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Hosts the Pocketfader web app in a WebView and adds the two native features a browser can't:
 *  - hardware volume keys drive the host computer volume (see onKeyDown)
 *  - a foreground media notification (PlaybackService) mirrors what's playing on the PC
 */
class MainActivity : AppCompatActivity() {

    companion object { const val DEFAULT_SERVER = "http://your-computer:8777" }

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("vmr", MODE_PRIVATE) }
    private val addr get() = ServerAddress.parse(prefs.getString("server", DEFAULT_SERVER)!!)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        // WebView + a small, faint config button in the corner (long-press-free settings).
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val cfg = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundColor(0x00000000); alpha = 0.30f
            setOnClickListener { promptServer() }
        }
        root.addView(cfg, FrameLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(4); marginEnd = dp(4)
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        if (prefs.contains("server")) startEverything() else promptServer()
    }

    private fun startEverything() {
        // Loading the full link lets the server set its access-token cookie for the WebView.
        web.loadUrl(addr.open)
        startService(Intent(this, PlaybackService::class.java)
            .putExtra("server", addr.base).putExtra("token", addr.token))
    }

    private fun promptServer() {
        val input = EditText(this).apply { setText(prefs.getString("server", DEFAULT_SERVER)) }
        AlertDialog.Builder(this)
            .setTitle("Server address")
            .setMessage("The Pocketfader server address (host:port), or the full link it prints if it asks for a token.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("server", input.text.toString().trim()).apply()
                startEverything()
            }
            .setCancelable(prefs.contains("server"))
            .show()
    }

    // --- volume keys → host computers (only while this app is focused) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { bumpVolume(+5); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { bumpVolume(-5); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true
        else super.onKeyUp(keyCode, event)

    private fun bumpVolume(delta: Int) = thread {
        try {
            val a = addr
            open("${a.base}/api/volume", a.token, "POST", 1500).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("""{"delta":$delta}""".toByteArray()) }
                responseCode; disconnect()
            }
        } catch (_: Exception) {}
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
