package app.pocketfader.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * New versions of the app. The phone looks for them on GitHub itself (the releases are public) and downloads the APK
 * from there, checked against the release's SHA256SUMS.txt. When GitHub can't be reached from the phone, the computer
 * running YTM Starcast is asked instead and passes the APK on. The file then goes to Android's installer.
 */
object UpdateManager {
    var info by mutableStateOf<UpdateInfo?>(null)
    var checking by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var dismissed by mutableStateOf(false)
    /** The new APK is signed with a different key than the installed app, so Android would refuse it. */
    var needsReinstall by mutableStateOf(false)

    fun openReleasePage(ctx: Context) {
        val url = info?.url?.takeIf { it.startsWith("https://") } ?: return
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("DEPRECATION")
    private fun certs(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pi.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** True only when both signing keys could be read and share none (so the installer would say "App not installed"). */
    @Suppress("DEPRECATION")
    private fun signedByAnotherKey(ctx: Context, apk: File): Boolean {
        return try {
            val pm = ctx.packageManager
            val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val mine = certs(pm.getPackageInfo(ctx.packageName, flag))
            val theirs = certs(pm.getPackageArchiveInfo(apk.path, flag))
            mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }

    fun available(ctx: Context): Boolean {
        val i = info ?: return false
        return i.error == null && i.apk && isNewer(i.latest, currentVersion(ctx))
    }

    /** When it last asked (automatic checks wait a while after that, so GitHub isn't asked again and again). */
    var lastCheck = 0L
        private set

    private class NotFound : IOException("not found")

    private fun github(url: String, ua: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true          // release files are handed out from GitHub's download servers
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", ua)
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            if (code == 404) throw NotFound()
            throw IOException(if (code == 403 || code == 429) "GitHub is limiting how often it's asked; try again later" else "GitHub answered $code")
        }
        return conn
    }

    private fun githubText(url: String, ua: String, accept: String): String {
        val conn = github(url, ua, accept)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** The newest release, straight from GitHub: the highest version among the tags, then that release (a tag can be
     *  there a few minutes before its release is published, so the next ones down are tried too). */
    private fun fromGithub(ua: String): UpdateInfo {
        val api = "https://api.github.com/repos/$RELEASES_REPO"
        val json = "application/vnd.github+json"
        for (tag in newestTags(JSONArray(githubText("$api/tags?per_page=100", ua, json)))) {
            val r = try {
                JSONObject(githubText("$api/releases/tags/$tag", ua, json))
            } catch (e: NotFound) {
                continue
            }
            return releaseInfo(r) ?: continue
        }
        return UpdateInfo(error = "no releases found")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads the release's APK from GitHub into [dest], checked against its SHA256SUMS.txt when it has one. */
    private fun downloadFromGithub(rel: GithubRelease, dest: File, ua: String) {
        val want = rel.sumsUrl?.let { url ->
            checksumFor(githubText(url, ua, "text/plain, */*"), rel.apkName)
                ?: throw IOException("the release's checksum list doesn't name the app")
        }
        val tmp = File(dest.path + ".part")
        val conn = github(rel.apkUrl, ua, "application/octet-stream")
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (want != null && sha256(tmp) != want) {
            tmp.delete()
            throw IOException("the download didn't match its checksum; try again")
        }
        if (!tmp.renameTo(dest)) throw IOException("couldn't save the download")
    }

    private fun userAgent(ctx: Context) = "ytm-starcast-android/${currentVersion(ctx)}"

    /** For the automatic check: when the app opens, and once more when the computer becomes reachable if that first
     *  look got nowhere (no internet for GitHub then). */
    fun shouldCheckNow(online: Boolean): Boolean =
        info == null && !checking && (lastCheck == 0L || (online && System.currentTimeMillis() - lastCheck > 60_000L))

    suspend fun check(ctx: Context, refresh: Boolean) {
        if (checking) return
        checking = true
        message = null
        lastCheck = System.currentTimeMillis()
        try {
            val ua = userAgent(ctx)
            val r = withContext(Dispatchers.IO) {
                try {
                    fromGithub(ua)
                } catch (e: Exception) {
                    // GitHub can't be reached from here (or limits how often it's asked): the computer may get through
                    try { Remote.updateInfo(refresh) } catch (e2: Exception) { throw e }
                }
            }
            info = r
            if (r.error != null) message = "Couldn't check: ${r.error}"
            else if (!available(ctx)) message = "You're up to date (${currentVersion(ctx)})."
            else dismissed = false
        } catch (e: Exception) {
            message = "Couldn't check: ${e.friendly()}"
        }
        checking = false
    }

    /** Downloads the new APK (from GitHub, or through the computer when that's where it was found) and opens Android's
     *  installer. */
    suspend fun install(ctx: Context) {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            message = "Allow YTM Starcast to install apps, then tap Install again."
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        downloading = true
        message = "Downloading…"
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, "ytm-starcast-update.apk")
            val rel = info?.github
            val ua = userAgent(ctx)
            withContext(Dispatchers.IO) { if (rel != null) downloadFromGithub(rel, file, ua) else Remote.downloadUpdate(file) }
            if (signedByAnotherKey(ctx, file)) {
                needsReinstall = true
                message = "This version is signed with a different key than the one installed (older test builds used a temporary key), " +
                    "so Android won't install it over this one. Uninstall YTM Starcast once, then install the new APK from the download page. " +
                    "Future updates will install normally."
                downloading = false
                return
            }
            needsReinstall = false
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            message = "Ready: confirm the install on the next screen."
        } catch (e: Exception) {
            message = "Download failed: ${e.friendly()}"
        }
        downloading = false
    }
}
