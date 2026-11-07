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
import java.io.File
import java.security.MessageDigest

/**
 * New versions of the app. The computer running Pocketfader looks for releases on GitHub (so the phone never
 * needs a GitHub login) and passes the new APK on; this class asks it, downloads, and hands the file to
 * Android's installer.
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

    private fun certs(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") pi.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** True only when both signing keys could be read and share none (so the installer would say "App not installed"). */
    private fun signedByAnotherKey(ctx: Context, apk: File): Boolean = try {
        val pm = ctx.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val mine = certs(pm.getPackageInfo(ctx.packageName, flag))
        val theirs = certs(pm.getPackageArchiveInfo(apk.path, flag))
        mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()
    } catch (e: Exception) { false }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }

    fun available(ctx: Context): Boolean {
        val i = info ?: return false
        return i.error == null && i.apk && isNewer(i.latest, currentVersion(ctx))
    }

    suspend fun check(ctx: Context, refresh: Boolean) {
        if (checking) return
        checking = true
        message = null
        try {
            val r = withContext(Dispatchers.IO) { Remote.updateInfo(refresh) }
            info = r
            if (r.error != null) message = "Couldn't check: ${r.error}"
            else if (!available(ctx)) message = "You're up to date (${currentVersion(ctx)})."
            else dismissed = false
        } catch (e: Exception) {
            message = "Couldn't check: ${e.friendly()}"
        }
        checking = false
    }

    /** Downloads the new APK through the computer and opens Android's installer. */
    suspend fun install(ctx: Context) {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            message = "Allow Pocketfader to install apps, then tap Install again."
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
            val file = File(dir, "pocketfader-update.apk")
            withContext(Dispatchers.IO) { Remote.downloadUpdate(file) }
            if (signedByAnotherKey(ctx, file)) {
                needsReinstall = true
                message = "This version is signed with a different key than the one installed (older test builds used a temporary key), " +
                    "so Android won't install it over this one. Uninstall Pocketfader once, then install the new APK from the download page. " +
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
