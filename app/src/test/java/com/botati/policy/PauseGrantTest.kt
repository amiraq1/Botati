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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.3/SEC-02: approving a `Pause` grants *that action* — one exact
 * (tool, target, arguments, Pause reason) — for one execution. These tests pin
 * the grant to all four parts and prove it cannot widen into a general
 * permission, cannot cover a different payload or reason, never overrides a
 * forbidden action or a new recipient, never covers an irreversible (E3)
 * action, and is consumed after one use.
 *
 * The main fixture is a reversible (E2) draft action: grants are defined for
 * E0–E2 only. The E3 send action appears only in [grant_neverCovers_E3] and
 * the chat test, where it must never be granted.
 */
class PauseGrantTest {
    private val now = 1_000_000L

    private fun meta(name: String, e: Effect, d: Disclosure) = ToolMeta(name, e, d, ToolSource.BUILTIN)

    private fun bot(tools: Set<String>) = BotPolicyProfile("bot1", tools)

    private fun contract(
        verb: ContractVerb = ContractVerb.REPLY_AND_SEND,
        tools: List<String> = listOf("mail__read_email"),
        maxSteps: Int = 25,
        forbidden: List<ForbiddenAction> = listOf(ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT),
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
        forbidden = forbidden,
        maxSteps = maxSteps,
        budgetUsd = 0.30,
        expiresAtMs = null,
    )

    private fun draftCall(
        thread: String? = "t1",
        body: String = "hello",
        recipients: List<String> = listOf("alice@x.com"),
        attachments: List<String> = emptyList(),
    ) = ToolCall(
        name = "mail__save_draft",
        arguments = buildJsonObject {
            thread?.let { put("threadId", it) }
            put("body", body)
            if (attachments.isNotEmpty()) {
                putJsonArray("attachments") { attachments.forEach { add(JsonPrimitive(it)) } }
            }
        },
        targetKind = if (thread != null) TargetKind.THREAD else TargetKind.NONE,
        target = thread,
        recipients = recipients,
    )

    private fun sendCall() = ToolCall(
        name = "mail__send_reply",
        arguments = buildJsonObject {
            put("threadId", "t1")
            put("body", "hello")
        },
        targetKind = TargetKind.THREAD,
        target = "t1",
        recipients = listOf("alice@x.com"),
    )

    private fun decide(
        session: Session,
        call: ToolCall = draftCall(),
        effect: Effect = Effect.E2,
        counters: Counters = Counters(),
    ) = PolicyEngine.decide(
        bot(setOf("mail__save_draft", "mail__send_reply", "mail__read_email")),
        call,
        meta(call.name, effect, Disclosure.D2),
        session,
        counters,
        now,
    )

    @Test fun withoutGrant_outOfContractTool_pauses() {
        val d = decide(Session.Task(contract()))
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun withGrant_sameAction_allowsOnce() {
        val session = Session.Task(contract()).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        val d = decide(session)
        assertEquals(PolicyDecision.Allow(ReasonCode.GRANTED_ONCE), d)
    }

    @Test fun grant_isConsumedAfterOneExecution() {
        val granted = Session.Task(contract()).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        assertEquals(PolicyDecision.Allow(ReasonCode.GRANTED_ONCE), decide(granted))
        val consumed = granted.withoutGrantFor(draftCall())
        assertTrue(consumed.grants.isEmpty())
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), decide(consumed))
    }

    @Test fun grant_doesNotCover_sameTarget_differentBody() {
        val granted = Session.Task(contract()).withGrant(draftCall(body = "hello"), ReasonCode.TOOL_NOT_IN_CONTRACT)
        val d = decide(granted, call = draftCall(body = "goodbye"))
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun grant_neverCovers_E3() {
        // Owner decision 2026-10-25: irreversible actions are never granted.
        // A matching grant still leaves an E3 call Paused.
        val granted = Session.Task(contract()).withGrant(sendCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        assertEquals(
            PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT),
            decide(granted, call = sendCall(), effect = Effect.E3),
        )
        // ...and a grant cannot override a verb-ceiling Pause for an E3 call either.
        val readOnly = contract(verb = ContractVerb.READ_ONLY, tools = listOf("mail__send_reply"))
        val granted2 = Session.Task(readOnly).withGrant(sendCall(), ReasonCode.ACTION_BEYOND_VERB)
        assertEquals(
            PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB),
            decide(granted2, call = sendCall(), effect = Effect.E3),
        )
    }

    @Test fun grant_neverOverrides_NEW_RECIPIENT() {
        val locked = contract(tools = listOf("mail__save_draft"))
        val call = draftCall(recipients = listOf("mallory@x.com"))
        assertEquals(PolicyDecision.Pause(ReasonCode.NEW_RECIPIENT), decide(Session.Task(locked), call = call))
        val granted = Session.Task(locked).withGrant(call, ReasonCode.NEW_RECIPIENT)
        assertEquals(PolicyDecision.Pause(ReasonCode.NEW_RECIPIENT), decide(granted, call = call))
    }

    @Test fun grant_neverOverrides_ATTACHMENTS_OUT() {
        val noAttach = contract(
            tools = listOf("mail__save_draft"),
            forbidden = listOf(ForbiddenAction.ATTACHMENTS_OUT),
        )
        val call = draftCall(attachments = listOf("content://com.example/x"))
        assertEquals(PolicyDecision.Pause(ReasonCode.FORBIDDEN_ACTION), decide(Session.Task(noAttach), call = call))
        val granted = Session.Task(noAttach).withGrant(call, ReasonCode.FORBIDDEN_ACTION)
        assertEquals(PolicyDecision.Pause(ReasonCode.FORBIDDEN_ACTION), decide(granted, call = call))
    }

    @Test fun grant_withNullTarget_doesNotCoverOtherCalls() {
        val granted = Session.Task(contract()).withGrant(draftCall(thread = null), ReasonCode.TOOL_NOT_IN_CONTRACT)
        // The grant works for its own (target-less) call...
        assertEquals(
            PolicyDecision.Allow(ReasonCode.GRANTED_ONCE),
            decide(granted, call = draftCall(thread = null)),
        )
        // ...but covers nothing else.
        assertEquals(
            PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT),
            decide(granted, call = draftCall(thread = "t1")),
        )
    }

    @Test fun grant_doesNotCover_differentReason() {
        // Granted for "tool not in contract"; under a contract that allows
        // the tool but forbids sending, the same call pauses for another
        // reason — and the grant must not apply.
        val readOnly = contract(verb = ContractVerb.READ_ONLY, tools = listOf("mail__save_draft"))
        val granted = Session.Task(readOnly).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        assertEquals(PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB), decide(granted))
    }

    @Test fun grantIsScopedToItsTarget() {
        val session = Session.Task(contract()).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        val d = decide(session, call = draftCall(thread = "t2"))
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun grantDoesNotBypassStepCeiling() {
        val session = Session.Task(contract(maxSteps = 1)).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        val d = decide(session, counters = Counters(stepsUsed = 1))
        assertEquals(PolicyDecision.Stop(ReasonCode.STEP_CEILING), d)
    }

    @Test fun grantDoesNotBypassBudgetCeiling() {
        val session = Session.Task(contract()).withGrant(draftCall(), ReasonCode.TOOL_NOT_IN_CONTRACT)
        val d = decide(session, counters = Counters(costUsedUsd = 0.30))
        assertEquals(PolicyDecision.Stop(ReasonCode.BUDGET_CEILING), d)
    }

    @Test fun grantCoversVerbCeilingForThatActionOnly() {
        // Tool is in the contract, but READ_ONLY verb forbids drafting.
        val readOnly = contract(verb = ContractVerb.READ_ONLY, tools = listOf("mail__save_draft"))
        assertEquals(
            PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB),
            decide(Session.Task(readOnly)),
        )
        assertEquals(
            PolicyDecision.Allow(ReasonCode.GRANTED_ONCE),
            decide(Session.Task(readOnly).withGrant(draftCall(), ReasonCode.ACTION_BEYOND_VERB)),
        )
    }

    @Test fun withGrant_doesNotMutateTheOriginalSession() {
        val original = Session.Task(contract())
        val call = draftCall()
        original.withGrant(call, ReasonCode.TOOL_NOT_IN_CONTRACT)
        assertTrue(original.grants.isEmpty())
        assertEquals(
            GrantKey.forCall(call, ReasonCode.TOOL_NOT_IN_CONTRACT),
            original.withGrant(call, ReasonCode.TOOL_NOT_IN_CONTRACT).grants.single(),
        )
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
