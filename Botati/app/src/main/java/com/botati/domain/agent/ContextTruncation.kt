package com.botati.domain.agent

/** Minimal chat message for context management (engine-level, not Room). */
data class ChatTurn(val role: String, val content: String)

data class TruncationResult(
    val turns: List<ChatTurn>,
    /** True when older turns were cut — surfaced to the user (§27). */
    val wasTruncated: Boolean,
    val droppedCount: Int,
)

/**
 * Context management for the MVP (§27): truncation, NOT summarization.
 * Pinned material (system envelope + task instruction + contract summary) is
 * passed separately and always kept; from the history we keep the last
 * [keepLastTurns]. A truncation note is recorded so nobody believes the bot
 * "remembers" what was cut.
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

    /** Truncates one tool result (§5.4): >~8000 tokens gets cut with a hint. */
    fun truncateToolResult(text: String, maxChars: Int = 32_000): String =
        if (text.length <= maxChars) text
        else text.take(maxChars) + "\n[مقتطع — تابع بـ offset]"
}
