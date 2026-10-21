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
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.PolicyDecision
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
}
