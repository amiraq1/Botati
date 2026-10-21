package com.botati.agent

import com.botati.domain.agent.AssemblyResult
import com.botati.domain.agent.ToolCallDelta
import com.botati.domain.agent.ToolCallDeltaAssembler
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-style tests (§14 phase 1): fragmented args, parallel calls, cut stream. */
class DeltaAssemblerTest {

    @Test fun fragmentedArguments_assembleOnlyWhenComplete() {
        val a = ToolCallDeltaAssembler()
        a.feed(ToolCallDelta(0, id = "call_1", name = "read", argumentsFragment = "{\"ur"))
        // Some providers also fragment the NAME across deltas — must append.
        a.feed(ToolCallDelta(0, name = "_file", argumentsFragment = "i\":\"content://a"))
        a.feed(ToolCallDelta(0, argumentsFragment = ".txt\",\"offset\":2}"))
        val r = a.finish()
        assertTrue(r is AssemblyResult.Complete)
        val calls = (r as AssemblyResult.Complete).calls
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("content://a.txt", (calls[0].arguments["uri"] as JsonPrimitive).content)
        assertEquals("2", (calls[0].arguments["offset"] as JsonPrimitive).content)
    }

    @Test fun parallelCalls_interleavedDeltas_staySeparate() {
        val a = ToolCallDeltaAssembler()
        a.feed(ToolCallDelta(0, id = "c0", name = "web_search", argumentsFragment = "{\"query\":\"a\"}"))
        a.feed(ToolCallDelta(1, id = "c1", name = "web_fetch", argumentsFragment = "{\"url\":"))
        a.feed(ToolCallDelta(1, argumentsFragment = "\"https://x\"}"))
        val r = a.finish() as AssemblyResult.Complete
        assertEquals(2, r.calls.size)
        assertEquals("web_search", r.calls[0].name)
        assertEquals("web_fetch", r.calls[1].name)
        assertEquals("https://x", (r.calls[1].arguments["url"] as JsonPrimitive).content)
    }

    @Test fun midStreamCut_invalid_neverExecutable() {
        val a = ToolCallDeltaAssembler()
        a.feed(ToolCallDelta(0, id = "c0", name = "read_file", argumentsFragment = "{\"uri\":\"abc"))
        val r = a.finish()
        assertTrue("cut stream must be Invalid, was $r", r is AssemblyResult.Invalid)
    }

    @Test fun blankArguments_becomeEmptyObject() {
        val a = ToolCallDeltaAssembler()
        a.feed(ToolCallDelta(0, id = "c0", name = "list_unread", argumentsFragment = ""))
        val r = a.finish() as AssemblyResult.Complete
        assertTrue(r.calls[0].arguments.isEmpty())
    }

    @Test fun missingName_invalid() {
        val a = ToolCallDeltaAssembler()
        a.feed(ToolCallDelta(0, id = "c0", argumentsFragment = "{}"))
        assertTrue(a.finish() is AssemblyResult.Invalid)
    }
}
