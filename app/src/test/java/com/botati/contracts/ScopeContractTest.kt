package com.botati.contracts

import com.botati.domain.contracts.Resolution
import com.botati.domain.contracts.TargetResolver
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
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
