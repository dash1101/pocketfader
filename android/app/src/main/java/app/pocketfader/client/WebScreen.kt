package app.pocketfader.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The parts of the YouTube Music remote that aren't native yet (Home shelves, your playlists and
 * playlist editing) come from the server's own web app, shown inside the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(cfg: ServerConfig, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val web = remember(cfg) {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = WebViewClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val base = cfg.url.trimEnd('/')
            val url = if (cfg.token.isNotBlank()) "$base/?token=${android.net.Uri.encode(cfg.token)}" else "$base/"
            if (cfg.hasCloudflareToken) {
                // Access answers a valid service token with a session cookie, so only the first
                // request needs the headers.
                loadUrl(
                    url,
                    mapOf("CF-Access-Client-Id" to cfg.cfClientId, "CF-Access-Client-Secret" to cfg.cfClientSecret),
                )
            } else {
                loadUrl(url)
            }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    BackHandler(enabled = web.canGoBack()) { web.goBack() }
    AndroidView(factory = { web }, modifier = modifier)
}
