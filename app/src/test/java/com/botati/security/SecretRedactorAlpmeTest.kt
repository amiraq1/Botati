package com.botati.security

import com.botati.domain.security.SecretRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorAlpmeTest {
    @Test fun alpmeLiveKeyIsDetectedAndRedacted() {
        val sample = "sk_live_abcdefghijk1234567890"
        val redacted = SecretRedactor.redact("key=$sample")
        assertTrue(SecretRedactor.containsSecret(sample))
        assertFalse(redacted.contains(sample))
        assertTrue(redacted.contains("***"))
    }
}
