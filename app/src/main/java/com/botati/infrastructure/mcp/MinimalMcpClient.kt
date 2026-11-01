package com.botati.infrastructure.mcp

import com.botati.domain.security.SecretRedactor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A non-2xx response from an MCP endpoint (BOT-15). Before this existed every
 * failure surfaced as `"tools/call failed: null"`, which told the user nothing
 * about whether the server rejected the request, was unreachable, or returned
 * HTML instead of JSON-RPC.
 */
class McpHttpException(
    val status: Int,
    bodySnippet: String,
) : Exception("MCP HTTP $status: $bodySnippet")

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
 * *suspending* lock, never a blocked thread. Stateless-era calls are
 * independent requests. Calls to DIFFERENT servers still run in parallel
 * (the pool does not lock).
 *
 * BOT-15 fixes:
 *  - the stateless era requires EVERY request to describe itself through
 *    `_meta`, not just `server/discover`, and requires `Mcp-Method` (plus
 *    `Mcp-Name` for `tools/call`). Both are now sent on every stateless call.
 *  - HTTP status codes are checked instead of parsed as JSON-RPC.
 *  - the session lock is waited on with a bound instead of indefinitely.
 *  - request ids and connection state are safe under concurrent callers.
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
    private val nextId = AtomicInteger(1)

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var info: McpServerInfo? = null

    @Volatile
    private var connected = false

    /** The JSON-RPC id of the call currently awaiting a response, if any. */
    @Volatile
    private var inFlightId: Int? = null

    override suspend fun connect(): McpServerInfo {
        // Double-checked locking: the fast path is lock-free, and the probe
        // below runs exactly once even when several calls start together.
        // Without the inner re-check two callers would both see connected=false
        // and perform a second handshake, which a sessionful server answers by
        // opening a second session (and invalidating the first).
        if (connected) return info!!
        return locked {
            if (connected) return@locked info!!
            // Attempt 1: LEGACY initialize handshake.
            val initParams = buildJsonObject {
                put("protocolVersion", LEGACY_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", CLIENT_NAME); put("version", CLIENT_VERSION)
                }
            }
            val (resp, sid) = postRpcRaw("initialize", initParams, id = nextId.getAndIncrement())
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
                return@locked info!!
            }
            // No `result` means "this server does not speak the legacy era".
            // The reasons differ and all of them must land here:
            //   -32022  version rejected (a 2025-xx server that refuses 2025-03-26)
            //   -32601  method not found: a strict 2026-07-28 server does not
            //           define `initialize` at all and answers Method not found
            //   -32602  invalid params (some servers validate capabilities strictly)
            //   anything else, including a non-JSON body
            // The fallback is therefore driven by the ABSENCE of a result, not
            // by a specific code — a server that rejects `initialize` for a
            // reason we did not anticipate still gets the stateless probe
            // instead of an opaque failure.
            val legacyErrorCode = errorCodeOf(resp)

            // Attempt 2: stateless 2026-07-28 discovery. No session, and the
            // request describes itself via _meta (added by postRpcRaw).
            sessionId = null
            val discoverParams = buildJsonObject {
                putJsonObject("_meta") {
                    put(META_PROTOCOL_VERSION, STATELESS_PROTOCOL_VERSION)
                    putJsonObject(META_CLIENT_INFO) {
                        put("name", CLIENT_NAME); put("version", CLIENT_VERSION)
                    }
                }
            }
            val (resp2, _) = postRpcRaw(
                "server/discover", discoverParams, id = nextId.getAndIncrement(), stateless = true,
            )
            val result2 = resp2?.get("result") as? JsonObject
            if (result2 != null) {
                info = McpServerInfo(
                    name = (result2["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                    version = (result2["version"] as? JsonPrimitive)?.contentOrNull ?: "",
                    protocolVersion = STATELESS_PROTOCOL_VERSION,
                    era = McpEra.STATELESS_2026,
                )
                connected = true
                return@locked info!!
            }
            val errText = (resp?.get("error") ?: resp2?.get("error"))?.toString() ?: "no result"
            throw IllegalStateException(
                "MCP connect failed (legacy error ${legacyErrorCode ?: "none"}, " +
                    "stateless error ${errorCodeOf(resp2) ?: "none"}): " +
                    SecretRedactor.redact(errText).take(200),
            )
        }
    }

    /** JSON-RPC error code, when the payload carries one. */
    private fun errorCodeOf(resp: JsonObject?): Int? =
        ((resp?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull

    /**
     * Runs one call against a LEGACY session, re-handshaking once if the server
     * says the session is gone (BOT-15).
     *
     * A legacy session lives server-side and expires; the documented signal is
     * HTTP 404 on the next request. The alternative — surfacing
     * `McpHttpException(404)` to the user — makes an expired session look like a
     * broken server forever, because the stale `Mcp-Session-Id` is never
     * dropped. One retry, then the failure is real and propagates.
     */
    private suspend fun <T> withLegacySession(block: suspend () -> T): T = try {
        locked { block() }
    } catch (e: McpHttpException) {
        if (e.status != HTTP_NOT_FOUND) throw e
        locked { forgetSession() }
        connect()
        locked { block() }
    }

    /** Drops the session so the next [connect] performs a fresh handshake. */
    private fun forgetSession() {
        connected = false
        sessionId = null
        info = null
    }

    override suspend fun listTools(): List<McpRemoteTool> {
        connect()
        val era = info!!.era
        val call: suspend () -> List<McpRemoteTool> = {
            val (resp, _) = postRpcRaw(
                "tools/list", JsonObject(emptyMap()), id = nextId.getAndIncrement(),
                stateless = era == McpEra.STATELESS_2026,
                throwOnHttpError = true,
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
        return if (era == McpEra.LEGACY) withLegacySession { call() } else call()
    }

    override suspend fun callTool(name: String, arguments: JsonObject): String {
        connect()
        val era = info!!.era
        val call: suspend () -> String = {
            val params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
            val id = nextId.getAndIncrement()
            inFlightId = id
            try {
                val (resp, _) = postRpcRaw(
                    "tools/call", params, id = id,
                    stateless = era == McpEra.STATELESS_2026,
                    toolName = name,
                    throwOnHttpError = true,
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
            } finally {
                inFlightId = null
            }
        }
        return if (era == McpEra.LEGACY) withLegacySession { call() } else call()
    }

    override suspend fun notifyCancelled(requestId: String) {
        // Fire-and-forget (§5.6): a notification has no response and the UI
        // never waits for it. Disconnecting is the real cancellation signal.
        try {
            // JSON-RPC ids are integers in this client; keep a non-numeric id
            // as a string rather than dropping the notification.
            val idElement: JsonElement = requestId.toIntOrNull()
                ?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(requestId)
            val params = buildJsonObject {
                put("requestId", idElement)
                put("reason", "User requested cancellation")
            }
            postNotification("notifications/cancelled", params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort by design
        }
    }

    /**
     * Cancels whatever this client currently has in flight (BOT-10). The agent
     * engine cancels its coroutine, but the server keeps working on the call
     * unless it is told — and for a send, "keeps working" is the difference
     * between one mail and two.
     */
    suspend fun cancelInFlight() {
        val id = inFlightId ?: return
        notifyCancelled(id.toString())
    }

    override fun close() {
        connected = false
        sessionId = null
        info = null
        inFlightId = null
    }

    // ---- transport ---------------------------------------------------------

    /** Bounds the wait for the session lock without bounding the call itself. */
    private suspend fun <T> locked(block: suspend () -> T): T {
        withTimeout(LOCK_WAIT_TIMEOUT_MS) { mutex.lock() }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun postNotification(method: String, params: JsonObject) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        http.post(url) {
            timeout { requestTimeoutMillis = MCP_REQUEST_TIMEOUT_MS }
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
        toolName: String? = null,
        throwOnHttpError: Boolean = false,
    ): Pair<JsonObject?, String?> {
        // The stateless era requires every request to describe itself.
        val effectiveParams = if (stateless) withMeta(params) else params
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", effectiveParams)
        }
        val response: HttpResponse = http.post(url) {
            timeout { requestTimeoutMillis = MCP_REQUEST_TIMEOUT_MS }
            authHeaders()
            if (stateless) {
                header("MCP-Protocol-Version", STATELESS_PROTOCOL_VERSION)
                header("Mcp-Method", method)
                if (toolName != null) header("Mcp-Name", toolName)
            }
            sessionId?.let { header("Mcp-Session-Id", it) }
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val sid = response.headers["Mcp-Session-Id"]
        val text = response.bodyAsText()
        if (throwOnHttpError && !response.status.isSuccess()) {
            throw McpHttpException(
                response.status.value,
                SecretRedactor.redact(text).take(200),
            )
        }
        val parsed = parseRpcPayload(text, id, json)
        return parsed to sid
    }

    /** Adds the mandatory `_meta` block to a stateless request's params. */
    private fun withMeta(params: JsonObject): JsonObject = JsonObject(
        params + (
            "_meta" to buildJsonObject {
                put(META_PROTOCOL_VERSION, STATELESS_PROTOCOL_VERSION)
                putJsonObject(META_CLIENT_INFO) {
                    put("name", CLIENT_NAME); put("version", CLIENT_VERSION)
                }
            }
            ),
    )

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            header("Authorization", "Bearer $it")
        }
    }

    companion object {
        const val CLIENT_NAME = "Botati"

        /** Kept in step with `versionName` in app/build.gradle.kts. */
        const val CLIENT_VERSION = "0.1.5"

        const val LEGACY_PROTOCOL_VERSION = "2025-03-26"
        const val STATELESS_PROTOCOL_VERSION = "2026-07-28"

        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"

        /** §5: an MCP call that takes longer than this is a failure, not a wait. */
        const val MCP_REQUEST_TIMEOUT_MS = 30_000L

        /** Waiting for another call on the same session must not hang forever. */
        const val LOCK_WAIT_TIMEOUT_MS = 35_000L

        /** A legacy session that expired server-side answers the next call with this. */
        const val HTTP_NOT_FOUND = 404

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
