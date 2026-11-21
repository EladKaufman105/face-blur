package com.elad.faceblur

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be shown (and sent) on the next launch: a Kotlin/Java crash is
 * written by the default handler as it happens; a crash in native code (OpenCV, whisper, ...)
 * is read back from Android's record of how the process last ended.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "crash_seen"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(header(app) + "thread: ${thread.name}\n\n" + sw.toString().take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report of a crash since the last launch, or null. */
    fun pending(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        return nativeCrash(context)
    }

    /** The report was seen (or sent): don't show it again. */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        val sp = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        lastExit(context)?.let { sp.edit().putLong(SEEN, it.timestamp).apply() }
    }

    private fun lastExit(context: Context): android.app.ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
    }

    private fun nativeCrash(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val info = lastExit(context) ?: return null
        if (info.reason != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        val sp = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        if (sp.getLong(SEEN, 0L) >= info.timestamp) return null
        // The tombstone is binary (protobuf); its readable strings hold the backtrace's libraries
        // and functions.
        val strings = runCatching {
            info.traceInputStream?.use { s ->
                val bytes = s.readBytes()
                Regex("[\\x20-\\x7E]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.take(120).joinToString("\n")
            }
        }.getOrNull().orEmpty()
        return header(context) + "native crash at ${stamp(info.timestamp)}: ${info.description}\n\n" + strings.take(12_000)
    }

    private fun header(context: Context): String {
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "EDITIT $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${stamp(System.currentTimeMillis())}\n"
    }

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}
