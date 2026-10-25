package com.botati.domain.tools

import com.botati.domain.security.Sha256

/**
 * Idempotency keys (spec §24): derived deterministically in the tool-executor
 * layer as sha256(taskId + threadId + bodyHash). The key is NEVER a field in
 * any model-facing tool schema — if the model generated it, a retry could
 * produce a different key and defeat the whole mechanism.
 */
object IdempotencyKeys {
    fun forMailSend(taskId: String, threadId: String, body: String): String {
        val bodyHash = Sha256.hex(body)
        return Sha256.hex("mail-send|$taskId|$threadId|$bodyHash")
    }
}
