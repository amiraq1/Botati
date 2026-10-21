package com.botati.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One streamed fragment of a tool call, as delivered by SSE deltas. */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String = "",
)

@kotlinx.serialization.Serializable
data class AssembledToolCall(
    val id: String?,
    val name: String,
    /** The fully concatenated argument string — validated as JSON. */
    val argumentsJson: String,
    val arguments: JsonObject,
)

sealed interface AssemblyResult {
    data class Complete(val calls: List<AssembledToolCall>) : AssemblyResult
    /** Stream ended with arguments that do not parse — never execute these. */
    data class Invalid(val index: Int, val name: String?, val rawArguments: String, val error: String) : AssemblyResult
}

/**
 * Tool-call delta assembler (spec §5.2). OpenAI-compatible streaming splits a
 * call's arguments across many deltas keyed by `index`; executing on the
 * first fragment is the classic agent-engine bug. This assembler accumulates
 * per index and only releases calls at [finish], after the concatenated
 * arguments parse as a JSON object.
 */
class ToolCallDeltaAssembler(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private class Partial {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    private val partials = sortedMapOf<Int, Partial>()

    fun feed(delta: ToolCallDelta) {
        val p = partials.getOrPut(delta.index) { Partial() }
        if (delta.id != null) p.id = delta.id
        if (delta.name != null) p.name = (p.name ?: "") + delta.name
        p.args.append(delta.argumentsFragment)
    }

    fun hasPending(): Boolean = partials.isNotEmpty()

    fun finish(): AssemblyResult {
        val calls = mutableListOf<AssembledToolCall>()
        for ((index, p) in partials) {
            val raw = p.args.toString().ifBlank { "{}" }
            val parsed = try {
                json.parseToJsonElement(raw) as? JsonObject
                    ?: return AssemblyResult.Invalid(index, p.name, raw, "arguments are not a JSON object")
            } catch (e: Exception) {
                return AssemblyResult.Invalid(index, p.name, raw, e.message ?: "unparseable arguments")
            }
            val name = p.name
            if (name.isNullOrBlank()) {
                return AssemblyResult.Invalid(index, null, raw, "tool call finished without a name")
            }
            calls += AssembledToolCall(p.id, name, raw, parsed)
        }
        partials.clear()
        return AssemblyResult.Complete(calls)
    }

    fun reset() = partials.clear()
}
