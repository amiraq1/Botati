package com.botati.infrastructure.openrouter

import com.botati.BuildConfig
import com.botati.domain.agent.LlmEvent
import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.LlmPort
import com.botati.domain.agent.LlmRequest
import com.botati.domain.agent.RawUsage
import com.botati.domain.agent.ToolCallDelta
import com.botati.domain.policy.DataCollection
import com.botati.domain.policy.dataCollectionFor
import com.botati.domain.security.SecretRedactor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Logger that redacts secrets before anything reaches Logcat (§8.6/§12). */
object RedactingKtorLogger : Logger {
    override fun log(message: String) {
        android.util.Log.d("botati-http", SecretRedactor.redact(message))
    }
}

fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 120_000
    }
    // BOT-06/§9: never follow a redirect. The https-only check in WebTools and
    // PolicyEngine looks at the URL the app chose; a 302 would let an https URL
    // land on http:// or on 169.254.169.254 / localhost, which is exactly the
    // SSRF shape we claim to prevent. Per-request timeouts override the
    // defaults above (60 s for the model, 30 s for a tool call).
    followRedirects = false
    // Release builds: no HTTP logging at all (§0).
    // F-19: Never log HTTP headers (avoids logging Authorization tokens).
    if (BuildConfig.DEBUG) {
        install(Logging) { logger = RedactingKtorLogger; level = LogLevel.INFO }
    }
    expectSuccess = false
}

// ---- wire DTOs (OpenAI-compatible) ----

@Serializable
data class WireMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class WireToolCall(
    val id: String? = null,
    val type: String = "function",
    val function: WireFunctionCall,
)

@Serializable
data class WireFunctionCall(val name: String = "", val arguments: String = "")

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireToolFunction,
)

@Serializable
data class WireToolFunction(
    val name: String,
    val description: String = "",
    val parameters: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
    val usage: JsonObject? = null,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    /**
     * Required, no default (§18/Q4): every request must decide its routing
     * explicitly, so no construction site can silently send unconstrained
     * (implicit "allow"). The value comes from [dataCollectionFor].
     */
    val provider: ProviderDto,
)

/** OpenRouter routing controls (`provider` object in the request body). */
@Serializable
data class ProviderDto(
    @SerialName("data_collection") val dataCollection: DataCollection,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    val cost: Double? = null,
)

@Serializable
data class DeltaDto(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDelta>? = null,
)

@Serializable
data class WireToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class DeltaFunction(val name: String? = null, val arguments: String? = null)

@Serializable
data class ChoiceDto(
    val delta: DeltaDto? = null,
    val message: WireMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatChunkDto(
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    /**
     * BOT-04: providers may report a failure IN-BAND, as a normal `data:` line
     * with an `error` object and `finish_reason: "error"`. Without this field
     * the chunk failed to deserialize, the failure was skipped by `continue`,
     * and the partial text was stored as a completed answer.
     */
    val error: JsonObject? = null,
)

@Serializable
data class ChatResponseDto(
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: JsonObject? = null,
)

/**
 * OpenRouter chat client (spec §3): OpenAI-compatible, SSE streaming, usage
 * requested explicitly (`usage.include`) so cost can be ACTUAL when the
 * provider returns it (§26). The API key is supplied per call from the Vault.
 */
class OpenRouterClient(private val http: HttpClient) : LlmPort {

    private val json = Json { ignoreUnknownKeys = true }

    var apiKeyProvider: suspend () -> String? = { null }
    var baseUrl: String = "https://openrouter.ai/api/v1"

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            emit(LlmEvent.Error("OpenRouter API key is not set — add it in the Vault."))
            return@flow
        }
        val dto = request.toDto(stream = true)
        try {
            // Manual SSE parsing on purpose — do NOT go back to Ktor's SSE
            // plugin session here: when the provider answers with a plain
            // HTTP error (401/402/404…), the session body conversion falls
            // through to ContentNegotiation's JSON converter and the user
            // sees a misleading "Serializer for class 'ClientSSESession' is
            // not found" instead of the provider's real error (hit live
            // 2026-10-21: the account's 402 hid behind exactly that mask).
            http.preparePost("$baseUrl/chat/completions") {
                timeout { requestTimeoutMillis = MODEL_REQUEST_TIMEOUT_MS }
                header(HttpHeaders.Authorization, "Bearer $key")
                header("HTTP-Referer", "https://localhost/botati")
                header("X-Title", "Botati")
                contentType(ContentType.Application.Json)
                setBody(dto)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errBody = runCatching { response.bodyAsText() }.getOrElse { "" }
                    emit(
                        LlmEvent.Error(
                            "OpenRouter HTTP ${response.status.value}: " +
                                SecretRedactor.redact(errBody).take(400),
                        ),
                    )
                    return@execute
                }
                val channel = response.bodyAsChannel()
                var sawFinish = false
                var sawAnyChunk = false
                var inBandError: String? = null
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val chunk = try {
                        json.decodeFromString<ChatChunkDto>(data)
                    } catch (e: Exception) {
                        // An unparseable chunk is a protocol failure, not a
                        // no-op: silence here is how a broken stream becomes a
                        // "successful" empty answer.
                        inBandError = inBandError ?: "unparseable stream chunk"
                        continue
                    }
                    sawAnyChunk = true
                    // BOT-04: an error object inside a normal chunk ends the stream.
                    val inBand = chunk.error
                    if (inBand != null) {
                        inBandError = SecretRedactor.redact(inBand.toString()).take(400)
                        break
                    }
                    chunk.usage?.let {
                        emit(LlmEvent.Usage(RawUsage(it.promptTokens, it.completionTokens, it.cost)))
                    }
                    val choice = chunk.choices.firstOrNull() ?: continue
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmEvent.TextDelta(it)) }
                    choice.delta?.toolCalls?.forEach { tc ->
                        emit(
                            LlmEvent.ToolDelta(
                                ToolCallDelta(
                                    index = tc.index,
                                    id = tc.id,
                                    name = tc.function?.name,
                                    argumentsFragment = tc.function?.arguments ?: "",
                                ),
                            ),
                        )
                    }
                    choice.finishReason?.let { reason ->
                        if (reason == "error") {
                            inBandError = inBandError ?: "provider reported finish_reason=error"
                        } else {
                            sawFinish = true
                        }
                        emit(LlmEvent.Finished(reason))
                    }
                }
                if (inBandError != null) {
                    emit(LlmEvent.Error(inBandError!!))
                } else if (!sawFinish) {
                    // BOT-04: the connection closed with no finish_reason. The
                    // text collected so far is a fragment; never a final answer.
                    emit(
                        LlmEvent.Error(
                            if (sawAnyChunk) "stream truncated: no finish_reason"
                            else "stream empty: no chunks received",
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LlmEvent.Error(e.message ?: "stream failed"))
        }
    }

    /**
     * Non-streaming completion used for contract proposals and key tests.
     *
     * [privateContent] defaults to TRUE: the instruction the user typed is
     * unclassified free text (people paste mail into it), so it is routed as
     * D2 — `data_collection: deny` on every model. Before this default the
     * proposal always went out as `allow` on a free model, which is the one
     * path that bypassed the whole §18 discipline.
     */
    suspend fun complete(
        model: String,
        messages: List<LlmMessage>,
        temperature: Double = 0.2,
        responseFormat: JsonObject? = null,
        privateContent: Boolean = true,
        onUsage: (suspend (RawUsage) -> Unit)? = null,
    ): String {
        val key = apiKeyProvider() ?: throw IllegalStateException("OpenRouter API key is not set")
        val dto = LlmRequest(
            model = model,
            messages = messages,
            tools = emptyList(),
            temperature = temperature,
            d2Contaminated = privateContent,
        ).toDto(stream = false).copy(responseFormat = responseFormat)
        val resp: ChatResponseDto = http.post("$baseUrl/chat/completions") {
            timeout { requestTimeoutMillis = MODEL_REQUEST_TIMEOUT_MS }
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }.body()
        resp.error?.let { throw IllegalStateException("OpenRouter error: ${SecretRedactor.redact(it.toString())}") }
        // §26: a completion that costs money must be accounted for. The engine
        // reports streaming usage; this path used to report nothing at all.
        resp.usage?.let { u ->
            onUsage?.invoke(RawUsage(u.promptTokens, u.completionTokens, u.cost))
        }
        return resp.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("empty completion")
    }

    /** Cheap key check: the models endpoint requires a valid key. */
    suspend fun testKey(key: String): Boolean = try {
        val resp = http.get("$baseUrl/models") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        resp.status.value in 200..299
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { false }

    private fun LlmRequest.toDto(stream: Boolean): ChatRequestDto = ChatRequestDto(
        model = model,
        messages = messages.map { m ->
            WireMessage(
                role = m.role,
                content = m.content.ifEmpty { null },
                toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
                    WireToolCall(tc.id, "function", WireFunctionCall(tc.name, tc.argumentsJson))
                },
                toolCallId = m.toolCallId,
                name = m.name,
            )
        },
        tools = tools.takeIf { it.isNotEmpty() }?.map { t ->
            WireTool(
                function = WireToolFunction(
                    t.name, t.description,
                    try { json.parseToJsonElement(t.parametersJson) } catch (e: Exception) { JsonObject(emptyMap()) },
                ),
            )
        },
        temperature = temperature,
        stream = stream,
        usage = if (stream) buildJsonObject { put("include", true) } else null,
        // §18/Q4: routing is decided by the pure dataCollectionFor — the
        // decision history lives in its KDoc, not here.
        provider = ProviderDto(dataCollectionFor(model, d2Contaminated)),
    )

    companion object {
        /** §5: a model turn that takes longer than this is a failure, not a wait. */
        const val MODEL_REQUEST_TIMEOUT_MS = 60_000L

        /** Structured-outputs response_format for the contract (§11.1). */
        fun contractResponseFormat(): JsonObject = buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "scope_contract")
                put("strict", true)
                putJsonObject("schema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("verb") {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("READ_ONLY"))
                                    add(kotlinx.serialization.json.JsonPrimitive("DRAFT_ONLY"))
                                    add(kotlinx.serialization.json.JsonPrimitive("REPLY_AND_SEND"))
                                },
                            )
                        }
                        putJsonObject("allowedTools") { put("type", "array") }
                        putJsonObject("maxSteps") { put("type", "integer") }
                        putJsonObject("budgetUsd") { put("type", "number") }
                    }
                }
            }
        }
    }
}
