package app.pocketfader.client

import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** [body]: the server's JSON answer when it sent one with the error (some carry a `message` written for people). */
class ApiException(val code: Int, message: String, val body: JSONObject? = null) : Exception(message)

/**
 * Minimal client for the server's HTTP API (docs/API.md). Plain HttpURLConnection so the app
 * needs no networking library and can send Cloudflare Access service-token headers.
 */
class PfApi(private val cfg: ServerConfig) {

    fun get(path: String, timeoutMs: Int = 4000): JSONObject = call("GET", path, null, timeoutMs)

    /** The full address of a path on this computer, and the headers a request to it needs (token, Cloudflare Access). */
    fun urlFor(path: String): String = cfg.url.trimEnd('/') + path

    fun authHeaders(): Map<String, String> {
        val h = HashMap<String, String>()
        if (cfg.token.isNotBlank()) h["Authorization"] = "Bearer ${cfg.token}"
        if (cfg.hasCloudflareToken) {
            h["CF-Access-Client-Id"] = cfg.cfClientId
            h["CF-Access-Client-Secret"] = cfg.cfClientSecret
        }
        return h
    }

    fun post(path: String, body: JSONObject, timeoutMs: Int = 15000): JSONObject =
        call("POST", path, body, timeoutMs)

    /** Streams a file from the server into [dest] (used for app updates). */
    fun download(path: String, dest: java.io.File, timeoutMs: Int = 120000) {
        val conn = URL(cfg.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            if (cfg.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            if (cfg.hasCloudflareToken) {
                conn.setRequestProperty("CF-Access-Client-Id", cfg.cfClientId)
                conn.setRequestProperty("CF-Access-Client-Secret", cfg.cfClientSecret)
            }
            val code = conn.responseCode
            if (code != 200) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ApiException(code, runCatching { JSONObject(msg).optString("error") }.getOrNull()?.ifBlank { null } ?: "Download failed ($code)")
            }
            dest.parentFile?.mkdirs()
            val tmp = java.io.File(dest.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject {
        val conn = URL(cfg.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            // A redirect here almost always means a Cloudflare Access login page, not our API.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            // with its version: the server tells apps that send just "ytm-starcast-android" (older ones) about new
            // releases in the version names they can read
            conn.setRequestProperty("User-Agent", "ytm-starcast-android/${AppVersion.name}")
            if (cfg.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            if (cfg.hasCloudflareToken) {
                conn.setRequestProperty("CF-Access-Client-Id", cfg.cfClientId)
                conn.setRequestProperty("CF-Access-Client-Secret", cfg.cfClientSecret)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                throw ApiException(code, "Sign-in required. Add a Cloudflare Access service token in the connection settings.")
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) throw ApiException(code, "This server needs an access token.")
            if (code == 403 && text.trimStart().startsWith("<")) {
                throw ApiException(code, "Cloudflare Access rejected the service token.")
            }
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: JSONException) {
                throw ApiException(code, "That address didn't answer like a YTM Starcast server.")
            }
            // replies that carry readable words (e.g. the Discord status while the old YTM Beacon app runs) show them;
            // the others carry only a code, which callers turn into words themselves
            if (code >= 400) throw ApiException(code, json.optString("message").ifBlank { json.optString("error") }.ifBlank { "Server error ($code)" }, json)
            return json
        } finally {
            conn.disconnect()
        }
    }
}

/** Human-readable text for whatever went wrong. */
fun Throwable.friendly(): String = when (this) {
    is ApiException -> if (message == "part_off") "That part of YTM Starcast is switched off. Turn it on in Settings → Parts." else message.orEmpty()
    is java.net.UnknownHostException -> "Can't find that computer. Check the address (and that Tailscale is on, if you use it)."
    is java.net.SocketTimeoutException -> "The computer didn't answer in time."
    is java.net.ConnectException -> "Couldn't connect. Is YTM Starcast running and reachable from this phone?"
    is javax.net.ssl.SSLException -> "Secure connection failed (${message.orEmpty().take(80)})."
    else -> message ?: javaClass.simpleName
}
