package ai.sonario.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal crash diagnostics. On an uncaught exception, writes the full stack
 * trace to a file the user (or you) can retrieve, then lets the default handler
 * run. On the next launch, MainActivity checks for that file and, if present,
 * shows the error on screen instead of the app silently crash-looping.
 *
 * The file lives in the app's EXTERNAL files dir so it's reachable over USB at
 * Android/data/ai.sonario.app/files/last_crash.txt (also shown in-app).
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // never let the reporter itself mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE)
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("Sonario crash report")
            appendLine("Time: $stamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            append(sw.toString())
        }
        crashFile(context).writeText(text)
    }

    /** Returns the last crash text if one was recorded, else null. */
    fun consumeLastCrash(context: Context): String? {
        val f = crashFile(context)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()   // consume: show once, then clear
            text
        } catch (_: Throwable) { null }
    }
}
