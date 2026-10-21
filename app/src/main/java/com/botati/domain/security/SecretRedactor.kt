package com.botati.domain.security

/**
 * Secret redaction for logs/audit digests (spec §8.6, §12).
 * Pure function so it is unit-tested directly; the Ktor logging interceptor
 * and the audit writer both route through here. Release builds additionally
 * disable body/header logging entirely — redaction is defense in depth, not
 * the only line.
 */
object SecretRedactor {
    private val patterns: List<Regex> = listOf(
        // OpenAI/OpenRouter-style keys: sk-..., sk-or-v1-...
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
        // Bearer tokens
        Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}"""),
        // Generic long hex/base64-ish tokens after key-ish labels
        Regex("""(?i)(api[_-]?key|token|secret|password)["'\s:=]+[A-Za-z0-9._\-]{8,}"""),
        // JWTs
        Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+"""),
    )

    const val MASK = "***"

    fun redact(text: String): String {
        var out = text
        for (p in patterns) out = out.replace(p) { m ->
            // Keep the label for labeled patterns, mask the value.
            val v = m.value
            val sep = v.indexOfAny(charArrayOf('=', ':', ' '))
            if (v.contains("Bearer", ignoreCase = true)) "Bearer $MASK"
            else if (sep in 1..24 && !v.startsWith("sk-") && !v.startsWith("eyJ")) v.substring(0, sep + 1) + MASK
            else MASK
        }
        return out
    }

    fun containsSecret(text: String): Boolean = patterns.any { it.containsMatchIn(text) }

    /** Masked display for the vault screen: first label + last 4, never full. */
    fun maskForDisplay(secret: String): String =
        if (secret.length <= 4) "••••" else "••••…" + secret.takeLast(4)
}
