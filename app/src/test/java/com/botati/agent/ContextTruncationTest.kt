package com.botati.agent

import com.botati.domain.agent.AssembledToolCall
import com.botati.domain.agent.ChatTurn
import com.botati.domain.agent.ContextTruncation
import com.botati.domain.agent.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTruncationTest {
    @Test fun keepsLastNTurns_andReports() {
        val history = (1..30).map { ChatTurn("user", "msg-$it") }
        val r = ContextTruncation.truncate(history, keepLastTurns = 20)
        assertTrue(r.wasTruncated)
        assertEquals(10, r.droppedCount)
        assertEquals("msg-11", r.turns.first().content)
        assertEquals("msg-30", r.turns.last().content)
    }

    @Test fun charBudget_dropsOldest_butKeepsFinalTurn() {
        val history = listOf(
            ChatTurn("user", "x".repeat(500)),
            ChatTurn("assistant", "y".repeat(500)),
            ChatTurn("user", "final"),
        )
        val r = ContextTruncation.truncate(history, keepLastTurns = 20, charBudget = 600)
        assertEquals("final", r.turns.last().content)
        assertTrue(r.droppedCount >= 1)
    }

    @Test fun toolResultTruncation_markerAppended() {
        val big = "a".repeat(40_000)
        val out = ContextTruncation.truncateToolResult(big, maxChars = 32_000)
        assertTrue(out.length < big.length)
        assertTrue(out.contains("مقتطع"))
        val small = ContextTruncation.truncateToolResult("short", maxChars = 32_000)
        assertEquals("short", small)
    }

    // ---------- BOT-07: trim by whole tool-call groups ----------

    private fun call(id: String) = LlmMessage(
        role = "assistant",
        content = "",
        toolCalls = listOf(
            AssembledToolCall(id, "web_search", "{}", kotlinx.serialization.json.JsonObject(emptyMap())),
        ),
    )

    private fun result(id: String) = LlmMessage("tool", """{"ok":true}""", toolCallId = id, name = "web_search")

    /**
     * The bug: an assistant message carrying `tool_calls` survived the trim
     * while its tool results were dropped by content match. Every
     * OpenAI-compatible provider answers that with 400, and because the rows
     * stay in Room the conversation is bricked permanently.
     */
    @Test fun trimHistory_neverSeparatesAToolCallFromItsResult() {
        val messages = mutableListOf(LlmMessage("system", "sys"))
        for (i in 1..25) {
            messages += LlmMessage("user", "q$i")
            messages += call("c$i")
            messages += result("c$i")
        }
        val trimmed = ContextTruncation.trimHistory(messages, keepLastGroups = 5)
        assertTrue("expected truncation", trimmed.droppedGroups > 0)
        assertEquals("system", trimmed.messages.first().role)

        // Every remaining tool_call id must have exactly one tool message.
        val declared = trimmed.messages.flatMap { it.toolCalls }.mapNotNull { it.id }
        val answered = trimmed.messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }
        assertEquals(declared.toSet(), answered.toSet())
        assertEquals(declared.size, answered.size)
    }

    @Test fun trimHistory_keepsEverything_whenUnderTheLimit() {
        val messages = listOf(
            LlmMessage("system", "sys"),
            LlmMessage("user", "hi"),
            call("c1"),
            result("c1"),
        )
        val trimmed = ContextTruncation.trimHistory(messages, keepLastGroups = 20)
        assertEquals(0, trimmed.droppedGroups)
        assertEquals(messages, trimmed.messages)
    }

    @Test fun trimHistory_keepsTheSystemPromptEvenWhenEverythingElseGoes() {
        val messages = mutableListOf(LlmMessage("system", "sys"))
        for (i in 1..10) messages += LlmMessage("user", "x".repeat(1_000) + i)
        val trimmed = ContextTruncation.trimHistory(messages, keepLastGroups = 10, charBudget = 1_500)
        assertTrue(trimmed.droppedGroups > 0)
        assertEquals(1, trimmed.messages.count { it.role == "system" })
        assertTrue(trimmed.messages.size >= 2) // system + at least the final group
    }
}
