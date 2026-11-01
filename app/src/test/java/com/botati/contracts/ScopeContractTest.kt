package com.botati.contracts

import com.botati.domain.contracts.Resolution
import com.botati.domain.contracts.TargetResolver
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
import com.botati.domain.policy.ForbiddenAction
import com.botati.domain.policy.ScopeContract
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeContractTest {
    private fun contract(threadIds: List<String> = listOf("t1")) = ScopeContract(
        taskId = "task1",
        verb = ContractVerb.REPLY_AND_SEND,
        allowedTools = listOf("mail__send_reply"),
        targets = ContractTargets(threadIds = threadIds),
        // §19: a writing contract must carry an expiry — the validator rejects
        // one without it, so every fixture that expects to be valid has one.
        expiresAtMs = 1_000L,
    )

    @Test fun hash_isStable_forSameContract() {
        assertEquals(contract().computeHash(), contract().computeHash())
        assertEquals(64, contract().computeHash().length)
    }

    @Test fun hash_changes_whenTargetsChange_invalidatingApproval() {
        assertNotEquals(contract(listOf("t1")).computeHash(), contract(listOf("t2")).computeHash())
    }

    @Test fun hash_changes_whenLimitsChange() {
        val a = contract()
        val b = a.copy(maxSteps = 26)
        assertNotEquals(a.computeHash(), b.computeHash())
    }

    @Test fun replyAndSend_withoutResolvedThreads_failsValidation() {
        val errors = contract(threadIds = emptyList()).validationErrors()
        assertTrue(errors.any { it.contains("threadIds") })
    }

    // BOT-12: a discovery placeholder is never a resolved target.
    @Test fun unresolvedAutoPlaceholder_failsValidation() {
        val errors = contract(threadIds = listOf("auto:first_match")).validationErrors()
        assertTrue("expected a placeholder error, got $errors", errors.any { it.contains("auto:") })
    }

    // BOT-12: a contract with no expiry is a permanent authorization.
    @Test fun writingContract_withoutExpiry_failsValidation() {
        val errors = contract().copy(expiresAtMs = null).validationErrors()
        assertTrue("expected an expiry error, got $errors", errors.any { it.contains("expiry") })
    }

    // BOT-21: one step ceiling for the proposer, the validator and the settings.
    @Test fun maxSteps_aboveTheSharedLimit_failsValidation() {
        val errors = contract().copy(maxSteps = ScopeContract.MAX_STEPS_LIMIT + 1).validationErrors()
        assertTrue(errors.any { it.contains("maxSteps") })
        assertTrue(contract().copy(maxSteps = ScopeContract.MAX_STEPS_LIMIT).validationErrors().isEmpty())
    }

    // §11.2: an empty forbidden list must never SHRINK the default set.
    @Test fun defaultForbidden_keepsAttachmentsOut() {
        assertTrue(ScopeContract(taskId = "t", verb = ContractVerb.READ_ONLY, allowedTools = listOf("x"))
            .forbidden.contains(ForbiddenAction.ATTACHMENTS_OUT))
    }

    @Test fun validContract_hasNoErrors_andRoundTripsJson() {
        val c = contract()
        assertTrue(c.validationErrors().isEmpty())
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ScopeContract>(json.encodeToString(c))
        assertEquals(c, decoded)
    }

    @Test fun resolver_noneSingleMultiple() {
        assertEquals(Resolution.None, TargetResolver.resolve(emptyList()))
        assertEquals(Resolution.Single("a"), TargetResolver.resolve(listOf("a")))
        assertEquals(Resolution.Multiple(listOf("a", "b")), TargetResolver.resolve(listOf("a", "b")))
    }
}
