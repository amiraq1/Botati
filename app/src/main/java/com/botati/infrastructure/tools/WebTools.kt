package com.botati.infrastructure.tools

import com.botati.data.vault.SettingsStore
import com.botati.data.vault.Vault
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
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
 *
 * BOT-06/BOT-04: responses are read with a hard character budget. The old code
 * called `bodyAsText()` on a body of unknown size, so a hostile or merely
 * huge page could exhaust the heap before the tool-result truncation (which
 * runs after the whole body is already in memory) ever applied.
 *
 * Redirects are not followed (`createHttpClient` sets followRedirects = false):
 * the https-only checks here and in PolicyEngine apply to the URL the app
 * chose, and a 302 would otherwise be able to land on http:// or on a
 * link-local / loopback address.
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
        if (!endpoint.startsWith("https://")) {
            return """{"error":"only https search endpoints permitted"}"""
        }
        val payload = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", count)
            put("search_depth", "basic")
        }
        val resp = try {
            http.post(endpoint) {
                timeout { requestTimeoutMillis = TOOL_REQUEST_TIMEOUT_MS }
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return """{"error":"search_failed","detail":"${e.message?.take(200) ?: "network"}"}"""
        }
        if (resp.status.value !in 200..299) {
            return """{"error":"search_failed","status":${resp.status.value}}"""
        }
        val body = readBounded(resp, MAX_RESPONSE_CHARS)
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
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

    /**
     * Fetches a page, following redirects MANUALLY so every hop can be checked.
     *
     * The HTTP client is configured with `followRedirects = false`, because a
     * library-level redirect would silently move the request off the URL the
     * policy engine approved: an `https://` link can 302 to `http://` or to
     * `169.254.169.254` / localhost, which is the whole SSRF shape this app
     * claims to prevent. Refusing all redirects fixes that but breaks the large
     * share of legitimate links that redirect (bare domain → www, shorteners,
     * trailing-slash normalisation), so the hops are re-validated here instead:
     * up to [MAX_REDIRECTS], each one required to resolve to `https://`.
     */
    suspend fun fetch(args: JsonObject): String {
        val start = (args["url"] as? JsonPrimitive)?.contentOrNull
            ?: return """{"error":"missing url"}"""
        if (!start.startsWith("https://")) {
            return """{"error":"only https urls permitted"}"""
        }
        var current = start
        var hop = 0
        while (true) {
            val resp = try {
                http.get(current) {
                    timeout { requestTimeoutMillis = TOOL_REQUEST_TIMEOUT_MS }
                    header(HttpHeaders.UserAgent, "Botati/0.1")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return """{"error":"fetch_failed","detail":"${e.message?.take(200) ?: "network"}"}"""
            }
            val status = resp.status.value
            if (status in REDIRECT_CODES) {
                // Drain a bounded amount so the connection is released.
                readBounded(resp, REDIRECT_BODY_DRAIN_CHARS)
                if (hop >= MAX_REDIRECTS) {
                    return """{"error":"too_many_redirects","hops":$hop}"""
                }
                val location = resp.headers[HttpHeaders.Location]
                    ?: return """{"error":"redirect_without_location","status":$status}"""
                val next = try {
                    java.net.URI(current).resolve(location).toString()
                } catch (e: Exception) {
                    return """{"error":"bad_redirect_target","location":"${location.take(200)}"}"""
                }
                // The check that matters: every hop stays on https.
                if (!next.startsWith("https://")) {
                    return """{"error":"redirect_to_non_https","target":"${next.take(200)}"}"""
                }
                current = next
                hop++
                continue
            }
            if (status !in 200..299) {
                return """{"error":"fetch_failed","status":$status}"""
            }
            return stripHtml(readBounded(resp, MAX_RESPONSE_CHARS))
        }
    }

    /**
     * Reads at most [cap] characters of the body, then stops. Line-based so it
     * uses the same channel API as the SSE reader; a single pathological line
     * is still bounded by the caller's truncation of the final tool result.
     */
    private suspend fun readBounded(resp: HttpResponse, cap: Int): String {
        val channel = resp.bodyAsChannel()
        val sb = StringBuilder()
        while (!channel.isClosedForRead && sb.length < cap) {
            val line = channel.readUTF8Line() ?: break
            sb.append(line).append('\n')
        }
        return if (sb.length > cap) sb.substring(0, cap) else sb.toString()
    }

    companion object {
        /** §5: a tool call that takes longer than this is a failure, not a wait. */
        const val TOOL_REQUEST_TIMEOUT_MS = 30_000L

        /** Hard ceiling on a fetched page / search response (≈200k tokens of HTML). */
        const val MAX_RESPONSE_CHARS = 1_000_000

        /** Redirects are followed by hand so each hop can be re-validated. */
        const val MAX_REDIRECTS = 5

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** A redirect body is normally empty; drain a little and stop. */
        private const val REDIRECT_BODY_DRAIN_CHARS = 4_096

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
