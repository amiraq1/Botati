package com.botati.infrastructure.openrouter

import com.botati.BuildConfig
import com.botati.domain.agent.LlmEvent
import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.LlmPort
import com.botati.domain.agent.LlmRequest
import com.botati.domain.agent.RawUsage
import com.botati.domain.agent.ToolCallDelta
import com.botati.domain.security.SecretRedactor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
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
    val provider: ProviderDto? = null,
)

/** OpenRouter routing controls (`provider` object in the request body). */
@Serializable
data class ProviderDto(
    @SerialName("data_collection") val dataCollection: String,
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
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val chunk = try {
                        json.decodeFromString<ChatChunkDto>(data)
                    } catch (e: Exception) {
                        continue
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
                    choice.finishReason?.let { emit(LlmEvent.Finished(it)) }
                }
            }
        } catch (e: Exception) {
            emit(LlmEvent.Error(e.message ?: "stream failed"))
        }
    }

    /** Non-streaming completion used for contract proposals and key tests. */
    suspend fun complete(
        model: String,
        messages: List<LlmMessage>,
        temperature: Double = 0.2,
        responseFormat: JsonObject? = null,
    ): String {
        val key = apiKeyProvider() ?: throw IllegalStateException("OpenRouter API key is not set")
        val dto = LlmRequest(model, messages, emptyList(), temperature).toDto(stream = false)
            .copy(responseFormat = responseFormat)
        val resp: ChatResponseDto = http.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }.body()
        resp.error?.let { throw IllegalStateException("OpenRouter error: ${SecretRedactor.redact(it.toString())}") }
        return resp.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("empty completion")
    }

    /** Cheap key check: the models endpoint requires a valid key. */
    suspend fun testKey(key: String): Boolean = try {
        val resp = http.get("$baseUrl/models") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        resp.status.value in 200..299
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
        // §18/Q4 (owner decision 2026-10-25, refined): the invariant is
        // disclosure, not price. A request is deny-routed when it may carry
        // D2 content: either the model is paid (mail work is paid by design)
        // or the session is already D2-contaminated — after the first D2
        // tool result, every later request is deny, even on a free model.
        // A free-model bot with a mail tool attached therefore cannot leak
        // read_email results under "allow": the D2 result only enters the
        // context in the next request, which is already deny.
        provider = ProviderDto(if (!model.endsWith(":free") || d2Contaminated) "deny" else "allow"),
    )

    companion object {
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
