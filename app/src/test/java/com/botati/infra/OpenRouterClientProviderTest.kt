package com.botati.infra

import com.botati.domain.agent.LlmEvent
import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.LlmRequest
import com.botati.infrastructure.openrouter.OpenRouterClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §18/Q4 (owner decision 2026-10-25, refined): `provider.data_collection`
 * is derived from disclosure, not price. These tests run offline against a
 * Ktor MockEngine: they capture the request body the client actually sends.
 *
 * Gradle-only: OpenRouterClient needs BuildConfig + the Ktor stack, which
 * the standalone kotlinc script does not compile (see run_unit_tests.sh).
 * CI (testDebugUnitTest) is the runner here.
 */
class OpenRouterClientProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class CapturingEngine(
        private val status: HttpStatusCode = HttpStatusCode.OK,
        private val body: String = SSE_OK,
    ) {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = when (val b = request.body) {
                is TextContent -> b.text
                is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                else -> error("unexpected request body type: ${b::class}")
            }
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
    }

    private fun client(capturing: CapturingEngine): OpenRouterClient {
        val c = OpenRouterClient(
            HttpClient(capturing.engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            },
        )
        c.apiKeyProvider = { "test-key" }
        return c
    }

    private fun dataCollection(capturedBody: String?): String {
        val obj = json.parseToJsonElement(capturedBody ?: error("no request captured")) as JsonObject
        val provider = obj["provider"] as? JsonObject ?: error("no provider object in request")
        return (provider["data_collection"] as? JsonPrimitive)?.contentOrNull
            ?: error("no data_collection in provider")
    }

    private fun stream(
        client: OpenRouterClient,
        model: String,
        d2Contaminated: Boolean,
    ): List<LlmEvent> = runBlocking {
        client.stream(
            LlmRequest(
                model = model,
                messages = listOf(LlmMessage("user", "hi")),
                d2Contaminated = d2Contaminated,
            ),
        ).toList()
    }

    @Test fun paidModel_cleanSession_sendsDeny() {
        val capturing = CapturingEngine()
        stream(client(capturing), "openai/gpt-4o-mini", d2Contaminated = false)
        assertEquals("deny", dataCollection(capturing.capturedBody))
    }

    @Test fun freeModel_cleanSession_sendsAllow() {
        val capturing = CapturingEngine()
        stream(client(capturing), "google/gemma-4-31b-it:free", d2Contaminated = false)
        assertEquals("allow", dataCollection(capturing.capturedBody))
    }

    @Test fun freeModel_contaminatedSession_sendsDeny() {
        // The invariant: D2 content never reaches a collecting provider,
        // even on a free model — e.g. a mail tool attached to a free bot.
        val capturing = CapturingEngine()
        stream(client(capturing), "google/gemma-4-31b-it:free", d2Contaminated = true)
        assertEquals("deny", dataCollection(capturing.capturedBody))
    }

    @Test fun paidModel_402_surfacesAsClearError() {
        val capturing = CapturingEngine(
            status = HttpStatusCode.PaymentRequired,
            body = """{"error":{"message":"Insufficient credits","code":402}}""",
        )
        val events = stream(client(capturing), "openai/gpt-4o-mini", d2Contaminated = false)
        val error = events.filterIsInstance<LlmEvent.Error>().singleOrNull()
            ?: error("expected one Error event, got $events")
        assertTrue("402 must be visible, got: ${error.message}", error.message.contains("402"))
        assertTrue(error.message.contains("Insufficient credits"))
    }

    companion object {
        private const val SSE_OK =
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n"
    }
}
