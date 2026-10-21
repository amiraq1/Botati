package com.botati.infrastructure.mcp

import com.botati.domain.security.SecretRedactor
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal Streamable-HTTP JSON-RPC MCP client behind [McpClientPort].
 *
 * Why not the official Kotlin SDK? (spec §21 deviation, see BUILD_LOG): the
 * SDK's current release (0.15.0, checked 2026-10-21) targets a much newer
 * Kotlin than this project's pinned stable AGP/Kotlin combo, and the spec
 * explicitly isolates the protocol behind this port so the swap stays a
 * one-file change. This client is dual-era: it tries the LEGACY handshake
 * (what the user's current Worker speaks), and on a version rejection falls
 * back to the stateless 2026-07-28 discovery shape.
 *
 * Session safety (§0/§21): one client per server; all RPCs for a LEGACY
 * (sessionful) server are serialized through a per-client Mutex — a
 * *suspending* lock, never a blocked thread and never the removed
 * singleThreadDispatcher idea. Stateless-era calls are independent requests.
 * Calls to DIFFERENT servers still run in parallel (the pool does not lock).
 */
class MinimalMcpClient(
    private val http: HttpClient,
    private val url: String,
    private val tokenProvider: suspend () -> String?,
) : McpClientPort {

    init {
        validateUrl(url)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var nextId = 1
    private var sessionId: String? = null
    private var info: McpServerInfo? = null
    private var connected = false

    override suspend fun connect(): McpServerInfo {
        if (connected) return info!!
        return mutex.withLock {
            if (connected) return@withLock info!!
            // Attempt 1: LEGACY initialize handshake.
            val initParams = buildJsonObject {
                put("protocolVersion", "2025-03-26")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "Botati"); put("version", "0.1.0") }
            }
            val (resp, sid) = postRpcRaw("initialize", initParams, id = nextId++)
            sessionId = sid
            val result = resp?.get("result") as? JsonObject
            if (result != null) {
                val serverInfo = result["serverInfo"] as? JsonObject
                info = McpServerInfo(
                    name = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull ?: "",
                    version = (serverInfo?.get("version") as? JsonPrimitive)?.contentOrNull ?: "",
                    protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: "",
                    era = McpEra.LEGACY,
                )
                // notifications/initialized completes the legacy handshake.
                postNotification("notifications/initialized", JsonObject(emptyMap()))
                connected = true
                return@withLock info!!
            }
            // Attempt 2: stateless 2026-07-28 discovery.
            sessionId = null
            val discoverParams = buildJsonObject {
                putJsonObject("_meta") {
                    put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                    putJsonObject("io.modelcontextprotocol/clientInfo") {
                        put("name", "Botati"); put("version", "0.1.0")
                    }
                }
            }
            val (resp2, _) = postRpcRaw("server/discover", discoverParams, id = nextId++, stateless = true)
            val result2 = resp2?.get("result") as? JsonObject
            if (result2 != null) {
                info = McpServerInfo(
                    name = (result2["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                    version = (result2["version"] as? JsonPrimitive)?.contentOrNull ?: "",
                    protocolVersion = "2026-07-28",
                    era = McpEra.STATELESS_2026,
                )
                connected = true
                return@withLock info!!
            }
            val errText = (resp?.get("error") ?: resp2?.get("error"))?.toString() ?: "no result"
            throw IllegalStateException("MCP connect failed: ${SecretRedactor.redact(errText).take(200)}")
        }
    }

    override suspend fun listTools(): List<McpRemoteTool> {
        connect()
        val era = info!!.era
        val call: suspend () -> List<McpRemoteTool> = {
            val (resp, _) = postRpcRaw(
                "tools/list", JsonObject(emptyMap()), id = nextId++,
                stateless = era == McpEra.STATELESS_2026,
            )
            val tools = ((resp?.get("result") as? JsonObject)?.get("tools") as? JsonArray)
                ?: throw IllegalStateException("tools/list failed: ${SecretRedactor.redact(resp.toString()).take(200)}")
            tools.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                McpRemoteTool(
                    name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    description = (o["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                    inputSchemaJson = o["inputSchema"]?.toString() ?: "{}",
                )
            }
        }
        return if (era == McpEra.LEGACY) mutex.withLock { call() } else call()
    }

    override suspend fun callTool(name: String, arguments: JsonObject): String {
        connect()
        val era = info!!.era
        val call: suspend () -> String = {
            val params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
            val (resp, _) = postRpcRaw(
                "tools/call", params, id = nextId++,
                stateless = era == McpEra.STATELESS_2026,
            )
            val result = resp?.get("result") as? JsonObject
                ?: throw IllegalStateException(
                    "tools/call failed: ${SecretRedactor.redact(resp.toString()).take(200)}",
                )
            val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
            val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (o["text"] as? JsonPrimitive)?.contentOrNull
                } else {
                    o.toString()
                }
            }
            val text = parts.joinToString("\n").ifBlank { result.toString() }
            if (isError) """{"error":"mcp_tool_error","detail":${JsonPrimitive(text)}}""" else text
        }
        return if (era == McpEra.LEGACY) mutex.withLock { call() } else call()
    }

    override suspend fun notifyCancelled(requestId: String) {
        // Fire-and-forget (§5.6): a notification has no response and the UI
        // never waits for it. Disconnecting is the real cancellation signal.
        try {
            val params = buildJsonObject {
                put("requestId", requestId)
                put("reason", "User requested cancellation")
            }
            postNotification("notifications/cancelled", params)
        } catch (e: Exception) {
            // best effort by design
        }
    }

    override fun close() {
        connected = false
        sessionId = null
        info = null
    }

    // ---- transport ---------------------------------------------------------

    private suspend fun postNotification(method: String, params: JsonObject) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        http.post(url) {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    /** Returns (parsed JSON-RPC object, Mcp-Session-Id header if any). */
    private suspend fun postRpcRaw(
        method: String,
        params: JsonObject,
        id: Int,
        stateless: Boolean = false,
    ): Pair<JsonObject?, String?> {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response: HttpResponse = http.post(url) {
            authHeaders()
            if (stateless) {
                header("MCP-Protocol-Version", "2026-07-28")
                header("Mcp-Method", method)
            }
            sessionId?.let { header("Mcp-Session-Id", it) }
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val sid = response.headers["Mcp-Session-Id"]
        val text = response.bodyAsText()
        val parsed = parseRpcPayload(text, id, json)
        return parsed to sid
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            header("Authorization", "Bearer $it")
        }
    }

    companion object {
        fun validateUrl(url: String) {
            require(url.startsWith("https://")) { "Only https:// URLs are permitted: $url" }
        }

        /**
         * Parses a Streamable-HTTP response body, which may be plain JSON or
         * an SSE stream of `data:` lines. Picks the JSON-RPC object whose id
         * matches [id] (or the last result-bearing object). Pure — unit-tested.
         */
        fun parseRpcPayload(text: String, id: Int, json: Json): JsonObject? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return try { json.parseToJsonElement(trimmed) as? JsonObject } catch (e: Exception) { null }
            }
            var fallback: JsonObject? = null
            for (line in trimmed.lineSequence()) {
                val data = line.trim().removePrefix("data:").trim()
                if (!data.startsWith("{")) continue
                val obj = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }
                    ?: continue
                val objId = (obj["id"] as? JsonPrimitive)?.intOrNull
                if (objId == id) return obj
                if (obj.containsKey("result") || obj.containsKey("error")) fallback = obj
            }
            return fallback
        }
    }
}
