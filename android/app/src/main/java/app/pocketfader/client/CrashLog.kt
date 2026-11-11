package app.pocketfader.client

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers what went wrong, so it can be read (and copied) afterwards in Settings → About: the error that closed the app,
 * errors in background work that were caught instead of closing it, and, on Android 11 and later, the reason Android itself
 * gives for the last times the app stopped (closed by the system to free memory, a crash, and so on).
 * Nothing leaves the phone.
 */
object CrashLog {
    private var file: File? = null
    private var version = ""
    private const val MAX = 24_000

    /** For background work: an error there is written down instead of closing the app. */
    val quiet = CoroutineExceptionHandler { _, e -> note("background work", e) }

    fun init(c: Context) {
        if (file != null) return
        file = File(c.filesDir, "last-errors.txt")
        version = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("The app closed because of an error", "thread ${t.name}", e)
            if (before != null) before.uncaughtException(t, e)
        }
    }

    /** Something failed but the app carried on. */
    fun note(where: String, e: Throwable) = write("An error that didn't close the app", where, e)

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    @Synchronized private fun write(kind: String, where: String, e: Throwable) {
        val f = file ?: return
        try {
            val entry = buildString {
                append("== ").append(kind).append(" ==\n")
                append(stamp(System.currentTimeMillis())).append(" · Pocketfader ").append(version)
                append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append(where)
                try {
                    append(" · app ").append(if (Remote.isVisible) "on screen" else "in the background")
                    append(" · playing on ").append(if (Remote.local) "this device" else "the computer")
                } catch (_: Throwable) {
                }
                append('\n')
                append(android.util.Log.getStackTraceString(e).take(6000)).append('\n')
            }
            val old = if (f.exists()) f.readText() else ""
            f.writeText((entry + "\n" + old).take(MAX))
        } catch (_: Throwable) {
        }
    }

    /** What was written down, newest first (null: nothing). */
    fun read(): String? = try { file?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }

    fun clear() {
        try { file?.delete() } catch (_: Throwable) { }
    }

    /** Why Android says the app stopped the last few times (Android 11 and later; empty before that). */
    fun exits(c: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(null, 0, 5).map { i ->
                val why = when (i.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed it to free memory"
                    ApplicationExitInfo.REASON_CRASH -> "it crashed"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed (native code)"
                    ApplicationExitInfo.REASON_ANR -> "it stopped responding"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "you closed it (swiped away or force stopped)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "the user it runs as was stopped"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed it for using too much battery or memory"
                    ApplicationExitInfo.REASON_SIGNALED -> "Android or the device's battery saver ended it (signal ${i.status})"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "it exited by itself"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depended on stopped"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "it couldn't start"
                    ApplicationExitInfo.REASON_OTHER -> "the system ended it"
                    else -> "unknown reason (${i.reason})"
                }
                val state = when {
                    i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
                    i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "playing or working in the background"
                    i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "idle in the background (a service)"
                    else -> "idle in the background"
                }
                val more = i.description?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                "${stamp(i.timestamp)}: $why, while $state$more"
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Everything, as text to copy into a report. */
    fun report(c: Context): String = buildString {
        append("Pocketfader ").append(version).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ")
            .append(Build.VERSION.SDK_INT).append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        val ex = exits(c)
        if (ex.isNotEmpty()) {
            append("Why the app stopped, according to Android:\n")
            ex.forEach { append("  ").append(it).append('\n') }
            append('\n')
        }
        append(read() ?: "No errors written down.")
    }
}
