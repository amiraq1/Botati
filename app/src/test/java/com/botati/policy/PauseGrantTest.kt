package com.botati.policy

import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
import com.botati.domain.policy.Counters
import com.botati.domain.policy.DataDisclosureScope
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.ForbiddenAction
import com.botati.domain.policy.GrantKey
import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.PolicyEngine
import com.botati.domain.policy.RecipientLock
import com.botati.domain.policy.ReasonCode
import com.botati.domain.policy.ScopeContract
import com.botati.domain.policy.Session
import com.botati.domain.policy.TargetKind
import com.botati.domain.policy.ToolCall
import com.botati.domain.policy.ToolMeta
import com.botati.domain.policy.ToolSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.3: approving a `Pause` must add *that action* to the session so the next
 * identical call does not ask again. These tests pin the grant to (tool, target)
 * and prove it cannot widen into a general permission or bypass a ceiling.
 */
class PauseGrantTest {
    private val now = 1_000_000L

    private fun meta(name: String, e: Effect, d: Disclosure) = ToolMeta(name, e, d, ToolSource.BUILTIN)

    private fun bot(tools: Set<String>) = BotPolicyProfile("bot1", tools)

    private fun contract(
        verb: ContractVerb = ContractVerb.REPLY_AND_SEND,
        tools: List<String> = listOf("mail__read_email"),
        maxSteps: Int = 25,
    ) = ScopeContract(
        taskId = "task1",
        verb = verb,
        allowedTools = tools,
        targets = ContractTargets(
            threadIds = listOf("t1"),
            threadParticipantIds = listOf("alice@x.com"),
            originalSenderId = "alice@x.com",
        ),
        dataDisclosure = DataDisclosureScope.PRIVATE_TO_LLM,
        recipientsLock = RecipientLock.THREAD_PARTICIPANTS_ONLY,
        forbidden = listOf(ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT),
        maxSteps = maxSteps,
        budgetUsd = 0.30,
        expiresAtMs = null,
    )

    private fun sendCall(thread: String? = "t1") = ToolCall(
        name = "mail__send_reply",
        arguments = buildJsonObject {
            thread?.let { put("threadId", it) }
            put("body", "hello")
        },
        targetKind = if (thread != null) TargetKind.THREAD else TargetKind.NONE,
        target = thread,
        recipients = listOf("alice@x.com"),
    )

    private fun decide(session: Session, call: ToolCall = sendCall(), counters: Counters = Counters()) =
        PolicyEngine.decide(
            bot(setOf("mail__send_reply", "mail__read_email")),
            call,
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            session,
            counters,
            now,
        )

    @Test fun withoutGrant_outOfContractTool_pauses() {
        val d = decide(Session.Task(contract()))
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun withGrant_sameAction_allowsOnce() {
        val session = Session.Task(contract()).withGrant("mail__send_reply", "t1")
        val d = decide(session)
        assertEquals(PolicyDecision.Allow(ReasonCode.GRANTED_ONCE), d)
    }

    @Test fun grantIsScopedToItsTarget() {
        val session = Session.Task(contract()).withGrant("mail__send_reply", "t1")
        val d = decide(session, call = sendCall(thread = "t2"))
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun grantDoesNotBypassStepCeiling() {
        val session = Session.Task(contract(maxSteps = 1)).withGrant("mail__send_reply", "t1")
        val d = decide(session, counters = Counters(stepsUsed = 1))
        assertEquals(PolicyDecision.Stop(ReasonCode.STEP_CEILING), d)
    }

    @Test fun grantDoesNotBypassBudgetCeiling() {
        val session = Session.Task(contract()).withGrant("mail__send_reply", "t1")
        val d = decide(session, counters = Counters(costUsedUsd = 0.30))
        assertEquals(PolicyDecision.Stop(ReasonCode.BUDGET_CEILING), d)
    }

    @Test fun grantCoversVerbCeilingForThatActionOnly() {
        // Tool is in the contract, but READ_ONLY verb forbids sending.
        val readOnly = contract(verb = ContractVerb.READ_ONLY, tools = listOf("mail__send_reply"))
        assertEquals(
            PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB),
            decide(Session.Task(readOnly)),
        )
        assertEquals(
            PolicyDecision.Allow(ReasonCode.GRANTED_ONCE),
            decide(Session.Task(readOnly).withGrant("mail__send_reply", "t1")),
        )
    }

    @Test fun withGrant_doesNotMutateTheOriginalSession() {
        val original = Session.Task(contract())
        original.withGrant("mail__send_reply", "t1")
        assertTrue(original.grants.isEmpty())
        assertEquals(GrantKey("mail__send_reply", "t1"), original.withGrant("mail__send_reply", "t1").grants.single())
    }

    @Test fun chatSessionIsUnaffectedByGrants() {
        // Chat never carries grants; an irreversible action still asks.
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")),
            sendCall(),
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Chat,
            Counters(),
            now,
        )
        assertTrue(d is PolicyDecision.Ask)
    }
}
