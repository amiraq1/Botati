package com.botati.policy

import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
import com.botati.domain.policy.Counters
import com.botati.domain.policy.DataDisclosureScope
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.ForbiddenAction
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val now = 1_000_000L

    private fun meta(name: String, e: Effect, d: Disclosure) =
        ToolMeta(name, e, d, ToolSource.BUILTIN)

    private fun bot(tools: Set<String>, preApproved: Set<String> = emptySet()) =
        BotPolicyProfile("bot1", tools, preApproved)

    private fun contract(
        verb: ContractVerb = ContractVerb.REPLY_AND_SEND,
        tools: List<String> = listOf("mail__search_emails", "mail__read_email", "mail__send_reply"),
        targets: ContractTargets = ContractTargets(
            threadIds = listOf("t1"),
            threadParticipantIds = listOf("alice@x.com"),
            originalSenderId = "alice@x.com",
        ),
        disclosure: DataDisclosureScope = DataDisclosureScope.PRIVATE_TO_LLM,
        lock: RecipientLock = RecipientLock.THREAD_PARTICIPANTS_ONLY,
        maxSteps: Int = 25,
        budget: Double = 0.30,
        expires: Long? = null,
        forbidden: List<ForbiddenAction> = listOf(
            ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT, ForbiddenAction.ATTACHMENTS_OUT,
        ),
    ) = ScopeContract(
        taskId = "task1", verb = verb, allowedTools = tools, targets = targets,
        dataDisclosure = disclosure, recipientsLock = lock, forbidden = forbidden,
        maxSteps = maxSteps, budgetUsd = budget, expiresAtMs = expires,
    )

    private fun mailCall(
        name: String = "mail__send_reply",
        thread: String? = "t1",
        recipients: List<String> = listOf("alice@x.com"),
        attachments: Int = 0,
    ) = ToolCall(
        name = name,
        arguments = buildJsonObject {
            thread?.let { put("threadId", it) }
            put("body", "hello")
            if (attachments > 0) put("attachmentCount", attachments)
        },
        targetKind = if (thread != null) TargetKind.THREAD else TargetKind.NONE,
        target = thread,
        recipients = recipients,
    )

    // ---------- chat ----------

    @Test fun chat_readPublic_allows() {
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), ToolCall("web_search"),
            meta("web_search", Effect.E0, Disclosure.D1), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Allow(ReasonCode.CHAT_READ), d)
    }

    @Test fun chat_readLocal_allows() {
        val d = PolicyEngine.decide(
            bot(setOf("analyze_file")), ToolCall("analyze_file"),
            meta("analyze_file", Effect.E0, Disclosure.D0), Session.Chat, Counters(), now,
        )
        assertTrue(d is PolicyDecision.Allow)
    }

    @Test fun chat_privateRead_asksDisclosure() {
        val call = ToolCall("read_file", targetKind = TargetKind.FILE, target = "content://docs/a.txt")
        val d = PolicyEngine.decide(
            bot(setOf("read_file")), call,
            meta("read_file", Effect.E0, Disclosure.D2), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Ask(ReasonCode.DISCLOSURE_ASK), d)
    }

    @Test fun chat_privateRead_preApproved_allows() {
        val call = ToolCall("read_file", targetKind = TargetKind.FILE, target = "content://docs/a.txt")
        val d = PolicyEngine.decide(
            bot(setOf("read_file"), preApproved = setOf("disclose:content://docs/a.txt")), call,
            meta("read_file", Effect.E0, Disclosure.D2), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Allow(ReasonCode.PRE_APPROVED), d)
    }

    @Test fun chat_draft_asks() {
        val d = PolicyEngine.decide(
            bot(setOf("draft_reply")), mailCall("draft_reply"),
            meta("draft_reply", Effect.E1, Disclosure.D2), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Ask(ReasonCode.TIER_ASK, requiresFullReview = false), d)
    }

    @Test fun chat_send_asksFullReview() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), mailCall(),
            meta("mail__send_reply", Effect.E3, Disclosure.D2), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Ask(ReasonCode.TIER_ASK, requiresFullReview = true), d)
    }

    @Test fun chat_forbiddenTool_denies_evenIfReadOnly() {
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), ToolCall("shell_exec"),
            meta("shell_exec", Effect.E0, Disclosure.D0), Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Deny(ReasonCode.NOT_ALLOWED), d)
    }

    // F-06: privatePayload in arguments is ignored. Network after D2 contamination elevates to D2 and asks.
    @Test fun chat_networkAfterD2Contamination_elevatesToD2_andAsks() {
        val call = ToolCall(
            "web_search",
            arguments = buildJsonObject { put("query", "x"); put("privatePayload", false) },
        )
        // Even with privatePayload = false in arguments, d2Contaminated = true elevates to D2 and asks
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), call,
            meta("web_search", Effect.E0, Disclosure.D1), Session.Chat,
            Counters(d2Contaminated = true), now,
        )
        assertEquals(PolicyDecision.Ask(ReasonCode.DISCLOSURE_ASK), d)
    }

    // F-06: privatePayload alone without session contamination does NOT elevate to D2
    @Test fun chat_privatePayloadArgumentAlone_isIgnored_doesNotElevateWithoutContamination() {
        val call = ToolCall(
            "web_search",
            arguments = buildJsonObject { put("query", "x"); put("privatePayload", true) },
        )
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), call,
            meta("web_search", Effect.E0, Disclosure.D1), Session.Chat,
            Counters(d2Contaminated = false), now,
        )
        // Without D2 contamination, web_search in chat allows public read; privatePayload argument is ignored
        assertEquals(PolicyDecision.Allow(ReasonCode.CHAT_READ), d)
    }

    // ---------- task ----------

    @Test fun task_inContractSend_allows() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), mailCall(),
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Task(contract()), Counters(stepsUsed = 3, costUsedUsd = 0.05), now,
        )
        assertEquals(PolicyDecision.Allow(ReasonCode.IN_CONTRACT), d)
    }

    @Test fun task_sameToolDifferentTarget_pauses() {
        // §29 regression: the decision is on arguments, not the tool name.
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), mailCall(thread = "t2"),
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Task(contract()), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.TARGET_MISMATCH), d)
    }

    @Test fun task_toolNotInContract_pauses() {
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), ToolCall("web_search"),
            meta("web_search", Effect.E0, Disclosure.D1),
            Session.Task(contract()), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT), d)
    }

    @Test fun task_stepCeiling_stops_andBeatsPause() {
        // Out-of-contract tool AND ceiling reached: Stop wins (§6.2 precedence).
        val d = PolicyEngine.decide(
            bot(setOf("web_search")), ToolCall("web_search"),
            meta("web_search", Effect.E0, Disclosure.D1),
            Session.Task(contract()), Counters(stepsUsed = 25), now,
        )
        assertEquals(PolicyDecision.Stop(ReasonCode.STEP_CEILING), d)
    }

    @Test fun task_budgetCeiling_stops() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__read_email")), mailCall("mail__read_email"),
            meta("mail__read_email", Effect.E0, Disclosure.D2),
            Session.Task(contract()), Counters(costUsedUsd = 0.30), now,
        )
        assertEquals(PolicyDecision.Stop(ReasonCode.BUDGET_CEILING), d)
    }

    @Test fun task_expiredContract_stops() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__read_email")), mailCall("mail__read_email"),
            meta("mail__read_email", Effect.E0, Disclosure.D2),
            Session.Task(contract(expires = 500L)), Counters(), now,
        )
        assertEquals(PolicyDecision.Stop(ReasonCode.CONTRACT_EXPIRED), d)
    }

    @Test fun task_newRecipient_pauses() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), mailCall(recipients = listOf("evil@x.com")),
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Task(contract()), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.NEW_RECIPIENT), d)
    }

    @Test fun task_forwardTool_pausesForbidden() {
        val c = contract(tools = listOf("mail__forward"))
        val d = PolicyEngine.decide(
            bot(setOf("mail__forward")), mailCall("mail__forward"),
            meta("mail__forward", Effect.E3, Disclosure.D2),
            Session.Task(c), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.FORBIDDEN_ACTION), d)
    }

    @Test fun task_attachmentsOut_pausesForbidden() {
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), mailCall(attachments = 1),
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Task(contract()), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.FORBIDDEN_ACTION), d)
    }

    @Test fun task_draftUnderReadOnlyVerb_pauses() {
        val c = contract(
            verb = ContractVerb.READ_ONLY,
            tools = listOf("mail__read_email", "mail__draft_reply"),
        )
        val d = PolicyEngine.decide(
            bot(setOf("mail__draft_reply")), mailCall("mail__draft_reply"),
            meta("mail__draft_reply", Effect.E1, Disclosure.D2),
            Session.Task(c), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB), d)
    }

    @Test fun task_privateDataOutsideDisclosureScope_pauses() {
        val c = contract(disclosure = DataDisclosureScope.PUBLIC_ONLY)
        val d = PolicyEngine.decide(
            bot(setOf("mail__read_email")), mailCall("mail__read_email"),
            meta("mail__read_email", Effect.E0, Disclosure.D2),
            Session.Task(c), Counters(), now,
        )
        assertEquals(PolicyDecision.Pause(ReasonCode.DISCLOSURE_NOT_IN_CONTRACT), d)
    }

    @Test fun task_unknownMcpTool_defaultsHigh_butStillNeedsBotAllowList() {
        val unknown = ToolMeta.unknownMcp("ext__do_thing")
        val d = PolicyEngine.decide(
            bot(emptySet()), ToolCall("ext__do_thing"), unknown,
            Session.Task(contract()), Counters(), now,
        )
        assertEquals(PolicyDecision.Deny(ReasonCode.NOT_ALLOWED), d)
    }

    // F-03: Empty targets list must reject/pause if action is above read
    @Test fun task_replyAndSend_emptyThreadIds_doesNotAllow() {
        val call = ToolCall(
            "mail__send_reply",
            targetKind = TargetKind.THREAD,
            target = "t1",
        )
        val c = contract(targets = ContractTargets(threadIds = emptyList())).copy(
            verb = ContractVerb.REPLY_AND_SEND,
        )
        val d = PolicyEngine.decide(
            bot(setOf("mail__send_reply")), call,
            meta("mail__send_reply", Effect.E3, Disclosure.D2),
            Session.Task(c), Counters(), now,
        )
        assertNotEquals(PolicyDecision.Allow(ReasonCode.IN_CONTRACT), d)
        assertTrue("Empty targets list must pause on REPLY_AND_SEND", d is PolicyDecision.Pause)
        assertEquals(ReasonCode.TARGET_MISMATCH, (d as PolicyDecision.Pause).reasonCode)
    }

    // F-06: web_fetch must not pass silently in chat
    @Test fun chat_webFetch_inChat_asks_neverSilentAllow() {
        val call = ToolCall("web_fetch", targetKind = TargetKind.URL, target = "https://example.com")
        val d = PolicyEngine.decide(
            bot(setOf("web_fetch")), call,
            meta("web_fetch", Effect.E0, Disclosure.D1),
            Session.Chat, Counters(), now,
        )
        assertNotEquals(PolicyDecision.Allow(ReasonCode.CHAT_READ), d)
        assertTrue(d is PolicyDecision.Ask)
    }

    // F-07: http:// URLs must be denied
    @Test fun policy_rejectsHttpUrlTarget() {
        val call = ToolCall("web_fetch", targetKind = TargetKind.URL, target = "http://insecure.example.com")
        val d = PolicyEngine.decide(
            bot(setOf("web_fetch")), call,
            meta("web_fetch", Effect.E0, Disclosure.D1),
            Session.Chat, Counters(), now,
        )
        assertEquals(PolicyDecision.Deny(ReasonCode.NOT_ALLOWED), d)
    }
}
