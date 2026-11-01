package com.botati.domain.tools

import com.botati.domain.security.Sha256

/**
 * Idempotency keys (spec §24): derived deterministically in the tool-executor
 * layer from the run, the thread and the body. The key is NEVER a field in
 * any model-facing tool schema — if the model generated it, a retry could
 * produce a different key and defeat the whole mechanism.
 *
 * BOT-19: fields are LENGTH-PREFIXED before hashing. A plain `|` join is
 * ambiguous — `("a|b", "c")` and `("a", "b|c")` produce the same string, so
 * two genuinely different sends could share a key and one would be dropped
 * as a duplicate.
 */
object IdempotencyKeys {
    fun forMailSend(runId: String, threadId: String, body: String): String {
        val bodyHash = Sha256.hex(body)
        val canonical = buildString {
            append("mail-send")
            append('|').append(runId.length).append(':').append(runId)
            append('|').append(threadId.length).append(':').append(threadId)
            append('|').append(bodyHash)
        }
        return Sha256.hex(canonical)
    }
}
