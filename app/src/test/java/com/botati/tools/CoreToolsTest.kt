package com.botati.tools

import com.botati.domain.security.SecretRedactor
import com.botati.domain.tools.IdempotencyKeys
import com.botati.domain.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreToolsTest {
    // §24: deterministic idempotency, executor-side.
    @Test fun idempotency_sameInputsSameKey() {
        assertEquals(
            IdempotencyKeys.forMailSend("task1", "t1", "body"),
            IdempotencyKeys.forMailSend("task1", "t1", "body"),
        )
    }

    @Test fun idempotency_differsByBodyThreadTask() {
        val base = IdempotencyKeys.forMailSend("task1", "t1", "body")
        assertTrue(base != IdempotencyKeys.forMailSend("task1", "t1", "other"))
        assertTrue(base != IdempotencyKeys.forMailSend("task1", "t2", "body"))
        assertTrue(base != IdempotencyKeys.forMailSend("task2", "t1", "body"))
        assertEquals(64, base.length)
    }

    // §20/§24: the model-facing send schema exposes neither recipients nor keys.
    @Test fun sendReplySchema_hasNoRecipientsOrIdempotencyKey() {
        val s = ToolRegistry.SEND_REPLY_MODEL_SCHEMA
        assertTrue(s.contains("threadId") && s.contains("body"))
        assertFalse(s.contains("idempotency"))
        assertFalse(s.contains("\"to\"") || s.contains("\"cc\"") || s.contains("\"bcc\""))
    }

    // §0: prefixes sanitized, unique, never containing "__" themselves.
    @Test fun sanitizePrefix_cleansAndDeduplicates() {
        assertEquals("mail_server", ToolRegistry.sanitizePrefix("Mail Server!", emptySet()))
        assertEquals("mail_server_2", ToolRegistry.sanitizePrefix("Mail Server!", setOf("mail_server")))
        assertFalse(ToolRegistry.sanitizePrefix("a__b", emptySet()).contains("__"))
        assertEquals("srv", ToolRegistry.sanitizePrefix("!!!", emptySet()))
    }

    // Vault redaction (§8.6/§12).
    @Test fun redactor_masksKeysBearerAndJwt() {
        val line = "key sk-or-v1-abcdef1234567890 and Bearer abcdefgh12345678 end"
        val out = SecretRedactor.redact(line)
        assertFalse(out.contains("abcdef1234567890"))
        assertFalse(out.contains("abcdefgh12345678"))
        assertTrue(SecretRedactor.containsSecret(line))
        assertFalse(SecretRedactor.containsSecret("nothing secret here"))
    }

    @Test fun maskForDisplay_showsLast4Only() {
        assertEquals("••••…cdef", SecretRedactor.maskForDisplay("sk-abcdef"))
        assertEquals("••••", SecretRedactor.maskForDisplay("ab"))
    }

    // F-10: mailTool() only for trusted mail server. Unknown server remains E3 (null canonical).
    @Test fun mailTool_unknownServer_readEmail_remainsNull() {
        val tool = ToolRegistry.mailTool("read_email", isTrustedServer = false)
        assertNull(tool)
    }

    @Test fun mailTool_trustedServer_readEmail_isE0() {
        val tool = ToolRegistry.mailTool("read_email", isTrustedServer = true)
        assertNotNull(tool)
        assertEquals(com.botati.domain.policy.Effect.E0, tool?.effect)
        assertEquals(com.botati.domain.policy.Disclosure.D2, tool?.disclosure)
    }
}
