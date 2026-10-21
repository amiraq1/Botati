package com.botati.infrastructure.tools

import com.botati.data.vault.SettingsStore
import com.botati.data.vault.Vault
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Remote web tools (§9). web_search speaks the Tavily-compatible shape
 * (POST {api_key, query, max_results} → {results:[{title,url,content}]}) with
 * the endpoint configurable in Settings (§ task brief) — no key is invented:
 * without a Vault key the tool returns an honest `search_not_configured`.
 */
class WebTools(
    private val http: HttpClient,
    private val vault: Vault,
    private val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(args: JsonObject): String {
        val key = vault.getSecret(Vault.SEARCH_KEY_SLOT)
            ?: return """{"error":"search_not_configured","hint":"add a search API key in the Vault"}"""
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"error":"missing query"}"""
        val count = ((args["count"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 10)
        val endpoint = settings.searchEndpoint.first()
        val payload = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", count)
            put("search_depth", "basic")
        }
        val resp = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (resp.status.value !in 200..299) {
            return """{"error":"search_failed","status":${resp.status.value}}"""
        }
        val root = json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
            ?: return """{"error":"search_bad_response"}"""
        val results = (root["results"] as? JsonArray).orEmpty()
        val out = buildJsonArray {
            for (r in results) {
                val o = r as? JsonObject ?: continue
                add(buildJsonObject {
                    put("title", (o["title"] as? JsonPrimitive)?.contentOrNull ?: "")
                    put("url", (o["url"] as? JsonPrimitive)?.contentOrNull ?: "")
                    put("snippet", ((o["content"] as? JsonPrimitive)?.contentOrNull ?: "").take(500))
                })
            }
        }
        return out.toString()
    }

    suspend fun fetch(args: JsonObject): String {
        val url = (args["url"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"error":"missing url"}"""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return """{"error":"only http/https urls"}"""
        }
        val resp = http.get(url) { header(HttpHeaders.UserAgent, "Botati/0.1") }
        if (resp.status.value !in 200..299) {
            return """{"error":"fetch_failed","status":${resp.status.value}}"""
        }
        return stripHtml(resp.bodyAsText())
    }

    companion object {
        /** Crude main-text extraction: drop script/style, strip tags. */
        fun stripHtml(html: String): String {
            var t = html.replace(Regex("(?is)<script.*?</script>"), " ")
            t = t.replace(Regex("(?is)<style.*?</style>"), " ")
            t = t.replace(Regex("(?is)<[^>]+>"), " ")
            t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            return t.replace(Regex("\\s+"), " ").trim()
        }
    }
}
