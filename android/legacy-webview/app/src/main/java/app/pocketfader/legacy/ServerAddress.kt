package app.pocketfader.legacy

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the user typed as the server address, split into the parts the app needs.
 * Accepts "host:8777", "http://host:8777", or the full link the server prints when it
 * requires an access token ("http://host:8777/?token=...").
 */
data class ServerAddress(val open: String, val base: String, val token: String?) {
    companion object {
        fun parse(raw: String): ServerAddress {
            var s = raw.trim()
            if (!s.contains("://")) s = "http://$s"
            val u = Uri.parse(s)
            val base = "${u.scheme}://${u.authority}"
            return ServerAddress(open = s, base = base, token = u.getQueryParameter("token"))
        }
    }
}

/** Opens a connection to [url], sending the access token as a Bearer header when there is one. */
fun open(url: String, token: String?, method: String = "GET", timeoutMs: Int = 2000): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
