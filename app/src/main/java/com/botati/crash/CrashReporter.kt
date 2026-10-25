package com.botati.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dependency-free on-device crash reporter (v0.1.1 diagnostic build).
 *
 * Plain Android framework + java.io ONLY — no Compose, Koin, Room, Ktor or
 * app classes in this path, so it can be installed as the very first thing in
 * [com.botati.BotatiApp.onCreate] and keeps working even if DI, the database
 * or the UI layer are exactly what is broken.
 *
 * Everything goes to ONE diagnostics file, `last_crash.txt`, in
 * getExternalFilesDir (fallback: filesDir):
 *   - breadcrumb stage markers appended as startup progresses
 *     (app_oncreate_start, koin_started, seed_start, db_first_access_ok,
 *     seed_done), and
 *   - report sections: a FATAL section from the uncaught-exception handler,
 *     or a NON-FATAL section for caught startup failures that must not kill
 *     the app.
 *
 * Rotation rule: at install time, if the existing file holds no report
 * section it was just a breadcrumb trail of a healthy run — it is discarded
 * so trails never grow unboundedly. A file WITH a report survives until the
 * user clears it from the crash screen.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val REPORT_MARKER = "=== REPORT"
    private const val FATAL_MARKER = "=== REPORT (FATAL) ==="
    private const val NONFATAL_MARKER = "=== REPORT (NON-FATAL) ==="

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    private val lock = Any()

    fun diagnosticsFile(context: Context): File {
        val dir: File = try {
            context.getExternalFilesDir(null) ?: context.filesDir
        } catch (t: Throwable) {
            context.filesDir
        }
        return File(dir, FILE_NAME)
    }

    /** True only when the diagnostics file contains an actual crash report. */
    fun hasReport(context: Context): Boolean = try {
        val f = diagnosticsFile(context)
        f.exists() && f.length() > 0 && f.readText().contains(REPORT_MARKER)
    } catch (t: Throwable) {
        false
    }

    fun readDiagnostics(context: Context): String = try {
        val f = diagnosticsFile(context)
        if (f.exists()) f.readText() else "(no diagnostics file)"
    } catch (t: Throwable) {
        "(diagnostics file unreadable: ${t.message})"
    }

    fun clear(context: Context) {
        try {
            diagnosticsFile(context).delete()
        } catch (t: Throwable) {
            // Nothing sensible to do — clearing must never crash.
        }
    }

    /**
     * Install the uncaught-exception handler. Call as the first statement of
     * Application.onCreate, BEFORE startKoin / any app wiring.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        appContext = app

        // Rotate away a report-free breadcrumb trail from a previous,
        // healthy run; keep files that contain a report for the crash screen.
        try {
            val f = diagnosticsFile(app)
            if (f.exists() && !f.readText().contains(REPORT_MARKER)) f.delete()
        } catch (t: Throwable) {
            // Rotation is best-effort only.
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                appendReport(app, FATAL_MARKER, "thread='${thread.name}'", error)
            } catch (t: Throwable) {
                // The handler itself must never throw.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        breadcrumb("crash_handler_installed")
    }

    /** Append a startup stage marker to the diagnostics file. Never throws. */
    fun breadcrumb(stage: String) {
        val ctx = appContext ?: return
        appendLine(ctx, "CRUMB ${now()} $stage")
    }

    /**
     * Record a caught, non-fatal failure (e.g. startup seeding) as a report
     * section so it surfaces on the crash screen next launch — without ever
     * propagating as an uncaught crash. Never throws.
     */
    fun reportNonFatal(title: String, error: Throwable) {
        val ctx = appContext ?: return
        try {
            appendReport(ctx, NONFATAL_MARKER, title, error)
        } catch (t: Throwable) {
            // Reporting must never crash the caller.
        }
    }

    private fun appendReport(context: Context, marker: String, title: String, error: Throwable) {
        val sw = StringWriter()
        sw.appendLine()
        sw.appendLine(marker)
        sw.appendLine("time: ${now()}")
        sw.appendLine("title: $title")
        sw.appendLine(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT})",
        )
        sw.appendLine("--- stack trace ---")
        error.printStackTrace(PrintWriter(sw))
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sw.appendLine("--- caused by ---")
            cause.printStackTrace(PrintWriter(sw))
            cause = cause.cause
            depth++
        }
        appendRaw(context, sw.toString())
    }

    private fun appendLine(context: Context, line: String) = appendRaw(context, line + "\n")

    private fun appendRaw(context: Context, text: String) {
        synchronized(lock) {
            try {
                diagnosticsFile(context).appendText(text)
            } catch (t: Throwable) {
                // Diagnostics are best-effort; a full/read-only disk must not
                // take the app down with them.
            }
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
