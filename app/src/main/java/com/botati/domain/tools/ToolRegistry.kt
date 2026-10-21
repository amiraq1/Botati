package com.botati.domain.tools

import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.ToolMeta
import com.botati.domain.policy.ToolSource

/**
 * Local tool registry (§9, §18). Classifications live here — never supplied by
 * the model. MCP tools are added at sync time with user-set classifications.
 */
object ToolRegistry {

    const val READ_FILE = "read_file"
    const val ANALYZE_FILE = "analyze_file"
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"

    private fun schema(props: String, required: String) =
        """{"type":"object","properties":{$props},"required":[$required]}"""

    val builtins: List<ToolMeta> = listOf(
        ToolMeta(
            READ_FILE, Effect.E0, Disclosure.D2, ToolSource.BUILTIN,
            "Read a text/md/PDF/docx/CSV/JSON file the user granted via SAF, in chunks.",
            schema(
                """"uri":{"type":"string"},"offset":{"type":"integer"},"length":{"type":"integer"}""",
                """"uri"""",
            ),
        ),
        ToolMeta(
            ANALYZE_FILE, Effect.E0, Disclosure.D2, ToolSource.BUILTIN,
            "Local statistics for a granted file (CSV rows/columns/means, text length/term frequency). Numbers are computed on-device.",
            schema(
                """"uri":{"type":"string"},"kind":{"type":"string","enum":["csv","text","auto"]}""",
                """"uri"""",
            ),
        ),
        ToolMeta(
            WEB_SEARCH, Effect.E0, Disclosure.D1, ToolSource.REMOTE_API,
            "Search the web; returns title, URL and snippet per result.",
            schema(
                """"query":{"type":"string"},"count":{"type":"integer"}""",
                """"query"""",
            ),
        ),
        ToolMeta(
            WEB_FETCH, Effect.E0, Disclosure.D1, ToolSource.REMOTE_API,
            "Fetch a web page and extract its main text (truncated).",
            schema(
                """"url":{"type":"string"}""",
                """"url"""",
            ),
        ),
    )

    /** Canonical mail-tool classifications (§18 corrected values). Only for trusted mail server (F-10). */
    fun mailTool(plainName: String, isTrustedServer: Boolean = false): ToolMeta? {
        if (!isTrustedServer) return null
        return when (plainName) {
            "list_unread", "search_emails", "read_email" ->
                ToolMeta(plainName, Effect.E0, Disclosure.D2, ToolSource.MCP)
            "draft_reply" ->
                ToolMeta(plainName, Effect.E1, Disclosure.D2, ToolSource.MCP)
            "send_reply" ->
                ToolMeta(plainName, Effect.E3, Disclosure.D2, ToolSource.MCP)
            else -> null
        }
    }

    /**
     * Model-facing schema for send_reply (§20/§24): threadId + body ONLY.
     * No to/cc/bcc (the server derives recipients from the thread) and no
     * idempotencyKey (derived deterministically in the executor layer).
     */
    val SEND_REPLY_MODEL_SCHEMA: String = schema(
        """"threadId":{"type":"string"},"body":{"type":"string"}""",
        """"threadId","body"""",
    )

    fun builtin(name: String): ToolMeta? = builtins.firstOrNull { it.name == name }

    /** Sanitizes a server name into a tool prefix (§0: unique, never contains "__"). */
    fun sanitizePrefix(raw: String, taken: Set<String>): String {
        var base = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "srv" }
        if (base in taken) {
            var i = 2
            while ("${base}_$i" in taken) i++
            base = "${base}_$i"
        }
        return base
    }

    fun prefixed(prefix: String, plainName: String): String = "${prefix}__$plainName"
    fun plainName(prefixedName: String): String = prefixedName.substringAfter("__", prefixedName)
}
