package com.botati.agent

import com.botati.domain.agent.ChatTurn
import com.botati.domain.agent.ContextTruncation
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
}
