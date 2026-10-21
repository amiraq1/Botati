package com.botati.infrastructure.tools

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil

/**
 * Built-in file tools (§9). They assume the Tool Router has ALREADY enforced
 * the SAF grant (§28) — these functions never see an ungranted URI in the app
 * flow. Text extraction: txt/md/csv/json as UTF-8, docx via ZIP+document.xml
 * (§3: POI is too heavy for Android), PDF via PdfBox-Android.
 * MVP limitation: CSV parsing handles quoted fields on a single line only
 * (no multi-line quoted cells).
 */
class FileTools(private val context: Context) {

    private var pdfReady = false

    fun extractText(uriString: String): String {
        val uri = Uri.parse(uriString)
        val name = displayName(uri).lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot open file")
        require(bytes.size <= 8 * 1024 * 1024) { "file too large for MVP extraction (8MB cap)" }
        return when {
            name.endsWith(".pdf") -> extractPdf(bytes)
            name.endsWith(".docx") -> extractDocx(bytes)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (e: Exception) { /* fall back to path segment */ }
        return name
    }

    private fun extractPdf(bytes: ByteArray): String {
        if (!pdfReady) { PDFBoxResourceLoader.init(context); pdfReady = true }
        ByteArrayInputStream(bytes).use { input ->
            PDDocument.load(input).use { doc ->
                return PDFTextStripper().getText(doc)
            }
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = String(zip.readBytes(), Charsets.UTF_8)
                    return xml
                        .replace("</w:p>", "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&amp;", "&").replace("&quot;", "\"")
                }
                entry = zip.nextEntry
            }
        }
        throw IllegalStateException("docx without word/document.xml")
    }

    /** read_file: chunked by character offset; reports totals (§9). */
    fun readFile(args: JsonObject): String {
        val uri = (args["uri"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"error":"missing uri"}"""
        val text = extractText(uri)
        val length = ((args["length"] as? JsonPrimitive)?.intOrNull ?: 12_000).coerceIn(500, 32_000)
        val offset = ((args["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val totalChunks = maxOf(1, ceil(text.length / length.toDouble()).toInt())
        val slice = if (offset >= text.length) "" else text.substring(offset, minOf(text.length, offset + length))
        return buildJsonObject {
            put("uri", uri)
            put("totalChars", text.length)
            put("totalChunks", totalChunks)
            put("chunkIndex", if (length == 0) 0 else offset / length)
            put("text", slice)
        }.toString()
    }

    /** analyze_file: all numbers computed locally (§9/§13 — model only interprets). */
    fun analyzeFile(args: JsonObject): String {
        val uri = (args["uri"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"error":"missing uri"}"""
        val kind = (args["kind"] as? JsonPrimitive)?.contentOrNull ?: "auto"
        val text = extractText(uri)
        val isCsv = kind == "csv" || (kind == "auto" && displayName(Uri.parse(uri)).lowercase().endsWith(".csv"))
        return if (isCsv) analyzeCsv(text) else analyzeText(text)
    }

    internal fun analyzeCsv(text: String): String {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return """{"rows":0,"columns":0}"""
        val header = parseCsvLine(lines.first())
        val rows = lines.drop(1).map { parseCsvLine(it) }
        val columns = buildJsonArray {
            for (col in header.indices) {
                val values = rows.mapNotNull { it.getOrNull(col)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                val numbers = values.mapNotNull { it.toDoubleOrNull() }
                add(buildJsonObject {
                    put("name", header.getOrNull(col) ?: "col$col")
                    put("nonEmpty", values.size)
                    put("empty", rows.size - values.size)
                    if (numbers.isNotEmpty() && numbers.size >= values.size / 2) {
                        val sorted = numbers.sorted()
                        put("type", "number")
                        put("mean", numbers.average())
                        put("median", sorted[sorted.size / 2])
                        put("min", sorted.first())
                        put("max", sorted.last())
                    } else {
                        put("type", "text")
                    }
                })
            }
        }
        return buildJsonObject {
            put("rows", rows.size)
            put("columns", header.size)
            put("columnStats", columns)
        }.toString()
    }

    internal fun analyzeText(text: String): String {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()
        val freq = words.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(10)
        return buildJsonObject {
            put("chars", text.length)
            put("words", words.size)
            put("lines", text.count { it == '\n' } + 1)
            put("topTerms", buildJsonArray {
                freq.forEach { (w, c) -> add(buildJsonObject { put("term", w); put("count", c) }) }
            })
        }.toString()
    }

    companion object {
        /** Minimal single-line CSV parser with quote awareness. */
        internal fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}
