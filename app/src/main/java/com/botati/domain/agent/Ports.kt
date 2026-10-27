package com.botati.domain.agent

import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.ReasonCode
import com.botati.domain.policy.ToolCall
import kotlinx.coroutines.flow.Flow

/** Engine-level LLM message (maps 1:1 to the OpenAI-compatible wire format). */
data class LlmMessage(
    val role: String, // system | user | assistant | tool
    val content: String = "",
    val toolCalls: List<AssembledToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

data class LlmToolSpec(val name: String, val description: String, val parametersJson: String)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolSpec> = emptyList(),
    val temperature: Double = 0.7,
    /**
     * True once a D2 (private) tool result has entered this session's
     * context (§18/Q4). The provider must route such a request with
     * `data_collection: "deny"` — D2 content never reaches a provider
     * that may collect it, whatever model is in use.
     */
    val d2Contaminated: Boolean = false,
    /** Selected OpenAI-compatible provider, used to resolve its Vault key and endpoint. */
    val provider: String = "openrouter",
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ToolDelta(val delta: ToolCallDelta) : LlmEvent
    data class Usage(val usage: RawUsage) : LlmEvent
    data class Finished(val finishReason: String?) : LlmEvent
    data class Error(val message: String) : LlmEvent
}

/** Port: a streaming LLM provider (OpenRouter implementation in infrastructure). */
interface LlmPort {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

/** Port: executes one approved tool call; returns the raw result text. */
interface ToolExecutorPort {
    suspend fun execute(call: ToolCall): String
}

/** Port: the UI (or a test fake) answering Ask/Pause decisions. */
interface ApprovalPort {
    suspend fun requestApproval(call: ToolCall, decision: PolicyDecision): Boolean
}

/** One audit record (§7/§28) — written for EVERY policy decision. */
data class AuditRecord(
    val botId: String,
    val taskId: String?,
    val tool: String,
    val target: String?,
    val effect: String,
    val disclosure: String,
    val decision: String,
    val reasonCode: ReasonCode,
    val argsDigest: String,
    val resultSummary: String? = null,
    val idempotencyKey: String? = null,
    val timestampMs: Long,
)

interface AuditPort {
    fun record(entry: AuditRecord)
}

/** Events emitted by [AgentEngine] for the UI/persistence layer. */
sealed interface EngineEvent {
    data class TextDelta(val text: String) : EngineEvent
    data class AssistantCompleted(val message: LlmMessage) : EngineEvent
    data class ToolStarted(val call: ToolCall) : EngineEvent
    data class ToolFinished(val call: ToolCall, val result: String, val isError: Boolean) : EngineEvent
    data class UsageRecorded(val model: String, val usage: RawUsage, val resolved: ResolvedCost) : EngineEvent
    data class ContextTruncated(val droppedCount: Int) : EngineEvent
    data class Paused(val call: ToolCall, val decision: PolicyDecision) : EngineEvent
    data class Stopped(val reason: String) : EngineEvent
    data class Failed(val message: String) : EngineEvent
    data object Done : EngineEvent
}
