package app.pocketfader.client

import android.content.Context
import android.content.Intent
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
