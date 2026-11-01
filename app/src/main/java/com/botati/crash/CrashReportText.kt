package com.botati.crash

import com.botati.domain.security.SecretRedactor
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The text of a crash report, as a pure function (BOT-17).
 *
 * Split out of [CrashReporter] for one reason: the redaction is the part that
 * must never regress, and a function that takes a `Throwable` can be tested
 * without an Android `Context`. `CrashReporter` keeps the file I/O and the
 * uncaught-exception hook; this keeps the formatting.
 *
 * Everything is passed through [SecretRedactor] before it is returned — stack
 * traces routinely carry URLs, request bodies and tokens inside exception
 * messages, and this text is written to disk.
 */
object CrashReportText {

    /**
     * @param marker  section marker (`=== REPORT (FATAL) ===` etc.)
     * @param title   short context line, e.g. the failing startup step
     * @param thread  thread name, or null for a non-thread-bound report
     * @param device  device/API description, or null when unknown
     * @param error   the throwable to render, with up to 5 causes
     */
    fun format(
        marker: String,
        title: String,
        thread: String?,
        device: String?,
        error: Throwable,
        timestamp: String = now(),
    ): String {
        val sw = StringWriter()
        sw.appendLine()
        sw.appendLine(marker)
        sw.appendLine("time: $timestamp")
        sw.appendLine("title: $title")
        if (thread != null) sw.appendLine("thread: '$thread'")
        if (device != null) sw.appendLine("device: $device")
        sw.appendLine("--- stack trace ---")
        error.printStackTrace(PrintWriter(sw))
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSES) {
            sw.appendLine("--- caused by ---")
            cause.printStackTrace(PrintWriter(sw))
            cause = cause.cause
            depth++
        }
        return SecretRedactor.redact(sw.toString())
    }

    fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private const val MAX_CAUSES = 5
}
