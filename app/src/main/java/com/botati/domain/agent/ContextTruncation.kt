package com.botati.domain.agent

/** Minimal chat message for context management (engine-level, not Room). */
data class ChatTurn(val role: String, val content: String)

data class TruncationResult(
    val turns: List<ChatTurn>,
    /** True when older turns were cut — surfaced to the user (§27). */
    val wasTruncated: Boolean,
    val droppedCount: Int,
)

/** Result of [ContextTruncation.trimHistory]: a ready-to-send message list. */
data class TrimResult(
    val messages: List<LlmMessage>,
    val droppedGroups: Int,
)

/**
 * Context management for the MVP (§27): truncation, NOT summarization.
 * Pinned material (system envelope + task instruction + contract summary) is
 * passed separately and always kept; from the history we keep the last
 * [keepLastTurns].
 */
object ContextTruncation {
    const val DEFAULT_KEEP_LAST_TURNS = 20
    const val DEFAULT_TOKEN_BUDGET_CHARS = 60_000 // ~15k tokens at ~4 chars/token

    fun truncate(
        history: List<ChatTurn>,
        keepLastTurns: Int = DEFAULT_KEEP_LAST_TURNS,
        charBudget: Int = DEFAULT_TOKEN_BUDGET_CHARS,
    ): TruncationResult {
        var kept = history
        var dropped = 0
        if (kept.size > keepLastTurns) {
            dropped += kept.size - keepLastTurns
            kept = kept.takeLast(keepLastTurns)
        }
        // Then enforce the rough char budget from the front (oldest first),
        // always keeping at least the final turn.
        while (kept.size > 1 && kept.sumOf { it.content.length } > charBudget) {
            kept = kept.drop(1)
            dropped++
        }
        return TruncationResult(kept, dropped > 0, dropped)
    }

    /**
     * Trims a real message list to the last [keepLastGroups] groups, where a
     * group is one non-tool message plus every tool result that answers it
     * (BOT-07).
     *
     * Why groups and not individual messages: the OpenAI wire format requires
     * exactly one `tool` message per `tool_calls` id, immediately after the
     * assistant message that declared them. The previous implementation kept
     * every assistant message carrying `tool_calls` while dropping tool
     * results by content match, producing a request that every compatible
     * provider rejects with 400 — and because the rows stay in Room, the
     * conversation was bricked permanently rather than for one turn.
     *
     * System messages are always preserved and never counted as groups.
     */
    fun trimHistory(
        messages: List<LlmMessage>,
        keepLastGroups: Int = DEFAULT_KEEP_LAST_TURNS,
        charBudget: Int = DEFAULT_TOKEN_BUDGET_CHARS,
    ): TrimResult {
        val system = messages.filter { it.role == "system" }
        val body = messages.filter { it.role != "system" }
        if (body.isEmpty()) return TrimResult(messages, 0)

        val groups = mutableListOf<MutableList<LlmMessage>>()
        for (m in body) {
            // A tool result belongs to the group it answers; anything else
            // starts a new one. A leading orphan tool message gets its own.
            if (m.role == "tool" && groups.isNotEmpty()) groups.last() += m
            else groups += mutableListOf(m)
        }

        var start = if (groups.size > keepLastGroups) groups.size - keepLastGroups else 0
        fun keptChars(from: Int): Int {
            var total = 0
            for (i in from until groups.size) for (m in groups[i]) total += m.content.length
            return total
        }
        // Enforce the rough char budget from the oldest end, but never drop the
        // final group: an empty history is not a valid request either.
        while (groups.size - start > 1 && keptChars(start) > charBudget) start++
        if (start == 0) return TrimResult(messages, 0)

        val out = ArrayList<LlmMessage>(system.size + groups.size - start)
        out += system
        for (i in start until groups.size) out += groups[i]
        return TrimResult(out, start)
    }

    /** Truncates one tool result (§5.4): >~8000 tokens gets cut with a hint. */
    fun truncateToolResult(text: String, maxChars: Int = 32_000): String =
        if (text.length <= maxChars) text
        else text.take(maxChars) + "\n[مقتطع — تابع بـ offset]"
}
