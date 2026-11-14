package app.pocketfader.client

import android.content.Context
import android.net.Uri

/** Everything needed to reach one YTM Starcast server. */
data class ServerConfig(
    val url: String = "",
    val token: String = "",
    val cfClientId: String = "",
    val cfClientSecret: String = "",
) {
    val isSet: Boolean get() = url.isNotBlank()
    val hasCloudflareToken: Boolean get() = cfClientId.isNotBlank() && cfClientSecret.isNotBlank()
}

/** Turns whatever the user typed or pasted into a clean base URL and an optional access token. */
object Address {
    data class Parsed(val url: String, val token: String?)

    /**
     * Accepts "host", "host:8777", "https://music.example.com", or the full link the server prints
     * when it needs a token ("http://host:8777/?token=...").
     */
    fun parse(raw: String): Parsed {
        val s = raw.trim()
        if (s.isEmpty()) return Parsed("", null)
        val full = if (s.contains("://")) s else withScheme(s)
        val uri = Uri.parse(full)
        val authority = uri.authority ?: return Parsed("", null)
        val scheme = uri.scheme ?: "http"
        return Parsed("$scheme://$authority", uri.getQueryParameter("token"))
    }

    // No scheme typed: a port or a bare/IP host means the plain-HTTP server (Tailscale, home
    // network); a dotted name with no port is assumed to be an https hostname (Cloudflare).
    private fun withScheme(s: String): String {
        val hostPart = s.substringBefore('/').substringBefore('?')
        val rest = s.substring(hostPart.length)
        val host = hostPart.substringBefore(':')
        val hasPort = hostPart.contains(':')
        val isIp = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return when {
            hasPort -> "http://$s"
            isIp || !host.contains('.') -> "http://$hostPart:8777$rest"
            else -> "https://$s"
        }
    }
}

/** Stored in the app's private storage (not shared with other apps). */
class ConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketfader", Context.MODE_PRIVATE)

    fun load() = ServerConfig(
        url = prefs.getString("url", "").orEmpty(),
        token = prefs.getString("token", "").orEmpty(),
        cfClientId = prefs.getString("cf_id", "").orEmpty(),
        cfClientSecret = prefs.getString("cf_secret", "").orEmpty(),
    )

    fun save(c: ServerConfig) {
        prefs.edit()
            .putString("url", c.url)
            .putString("token", c.token)
            .putString("cf_id", c.cfClientId)
            .putString("cf_secret", c.cfClientSecret)
            .apply()
    }

    /** A random name for this phone, made once and kept: the computer tells phones apart by it. Nothing personal is in it. */
    fun deviceId(): String {
        prefs.getString("device_id", null)?.takeIf(::isDeviceId)?.let { return it }
        val id = newDeviceId()
        prefs.edit().putString("device_id", id).apply()
        return id
    }
}
