package com.botati.infra

import com.botati.infrastructure.mcp.MinimalMcpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two era-negotiation behaviours the ADR (docs/adr/0001) states, so
 * the document cannot drift from the code:
 *
 *  - constraint 1: a server that does not define `initialize` at all answers
 *    `-32601`, and the fallback is driven by the ABSENCE of a result, not by a
 *    specific error code;
 *  - constraint 3: every stateless request describes itself (`_meta`,
 *    `Mcp-Method`, `Mcp-Name`), not just `server/discover`;
 *  - constraint 4's one exception: a legacy session that expired server-side
 *    (HTTP 404) is re-handshaked once and retried, instead of failing forever
 *    with a stale `Mcp-Session-Id`.
 *
 * Gradle-only (Ktor MockEngine); excluded from run_unit_tests.sh.
 */
class McpSessionRecoveryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class Recorded(val method: String?, val headers: Headers, val body: String)

    private fun bodyText(request: HttpRequestData): String = when (val b = request.body) {
        is TextContent -> b.text
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        else -> ""
    }

    private fun methodOf(body: String): String? = try {
        (json.parseToJsonElement(body) as JsonObject)["method"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private fun jsonHeaders(vararg extra: Pair<String, String>) = headersOf(
        *(
            arrayOf<Pair<String, List<String>>>(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
            ) + extra.map { (name, value) -> name to listOf(value) }
            ),
    )

    private fun client(engine: MockEngine) =
        MinimalMcpClient(HttpClient(engine), "https://mcp.example/rpc") { "test-token" }

    @Test
    fun strictStatelessServer_answersMethodNotFound_fallsBackToDiscover_andSelfDescribes() = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val engine = MockEngine { request ->
            val body = bodyText(request)
            val method = methodOf(body)
            recorded += Recorded(method, request.headers, body)
            when (method) {
                // A strict 2026-07-28 server has no `initialize` at all.
                "initialize" -> respond(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "server/discover" -> respond(
                    """{"jsonrpc":"2.0","id":2,"result":{"name":"srv","version":"1","protocolVersion":"2026-07-28"}}""",
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "tools/call" -> respond(
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"pong"}]}}""",
                    HttpStatusCode.OK, jsonHeaders(),
                )
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

        val out = client(engine).callTool("echo", JsonObject(emptyMap()))

        assertEquals("pong", out)
        assertTrue(
            "-32601 must trigger the stateless probe",
            recorded.any { it.method == "server/discover" },
        )
        val call = recorded.last { it.method == "tools/call" }
        assertEquals("tools/call", call.headers["Mcp-Method"])
        assertEquals("echo", call.headers["Mcp-Name"])
        assertTrue(
            "every stateless request must carry _meta",
            call.body.contains("io.modelcontextprotocol/protocolVersion"),
        )
        assertTrue(call.body.contains("_meta"))
    }

    @Test
    fun legacySessionExpiry_404_rehandshakesOnceAndRetriesTheSameCall() = runBlocking {
        var inits = 0
        var calls = 0
        val engine = MockEngine { request ->
            when (methodOf(bodyText(request))) {
                "initialize" -> {
                    inits++
                    respond(
                        """{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"srv","version":"1"},"protocolVersion":"2025-03-26"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders("Mcp-Session-Id" to "sess-$inits"),
                    )
                }
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted, jsonHeaders())
                "tools/call" -> {
                    calls++
                    if (calls == 1) {
                        // The session expired server-side; this is the signal.
                        respond("session expired", HttpStatusCode.NotFound, jsonHeaders())
                    } else {
                        respond(
                            """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok"}]}}""",
                            HttpStatusCode.OK, jsonHeaders(),
                        )
                    }
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

        val out = client(engine).callTool("echo", JsonObject(emptyMap()))

        assertEquals("ok", out)
        assertEquals("exactly one re-handshake", 2, inits)
        assertEquals("the same call is retried once", 2, calls)
    }

    @Test
    fun secondConsecutive404_propagates_insteadOfLooping() = runBlocking {
        var inits = 0
        var calls = 0
        val engine = MockEngine { request ->
            when (methodOf(bodyText(request))) {
                "initialize" -> {
                    inits++
                    respond(
                        """{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"srv","version":"1"},"protocolVersion":"2025-03-26"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders("Mcp-Session-Id" to "sess-$inits"),
                    )
                }
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted, jsonHeaders())
                "tools/call" -> {
                    calls++
                    respond("nope", HttpStatusCode.NotFound, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

        val failure = try {
            client(engine).callTool("echo", JsonObject(emptyMap()))
            null
        } catch (e: Exception) {
            e
        }

        assertTrue("a persistent 404 must surface", failure is com.botati.infrastructure.mcp.McpHttpException)
        assertEquals(404, (failure as com.botati.infrastructure.mcp.McpHttpException).status)
        assertEquals("one re-handshake only", 2, inits)
        assertEquals("one retry only", 2, calls)
    }
}
