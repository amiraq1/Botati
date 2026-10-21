package com.botati.agent

import com.botati.domain.agent.EngineEvent
import com.botati.domain.agent.SystemPromptBuilder
import com.botati.domain.agent.TaskStateTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLifecycleTest {

    // F-14: Tool results must be wrapped when rebuilding history from Room
    @Test fun ensureWrappedToolResult_wrapsUnwrappedContent() {
        val raw = """{"items":["a","b"]}"""
        val wrapped = SystemPromptBuilder.ensureWrappedToolResult(raw)
        assertTrue("Must start with <tool_result>", wrapped.startsWith("<tool_result>"))
        assertTrue("Must end with </tool_result>", wrapped.endsWith("</tool_result>"))
        assertTrue("Must contain raw content", wrapped.contains(raw))
    }

    @Test fun ensureWrappedToolResult_doesNotDoubleWrapAlreadyWrappedContent() {
        val alreadyWrapped = "<tool_result>\n{\"ok\":true}\n</tool_result>"
        val result = SystemPromptBuilder.ensureWrappedToolResult(alreadyWrapped)
        assertEquals(alreadyWrapped, result)
        // Ensure no double tag
        assertFalse(result.contains("<tool_result_escaped>"))
    }

    // F-18: Failed writes FAILED and finally does NOT write DONE over a terminal state
    @Test fun terminalStatus_failed_isNeverOverwrittenByDone() {
        // If task failed during run, terminal status must be FAILED, never DONE
        val status = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "FAILED",
            engineEvent = null,
            wasCancelled = false,
        )
        assertEquals("FAILED", status)
    }

    @Test fun terminalStatus_engineFailedEvent_becomesFailed() {
        val status = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING",
            engineEvent = EngineEvent.Failed("connection reset"),
            wasCancelled = false,
        )
        assertEquals("FAILED", status)
    }

    // F-04: Cancellation writes STOPPED and never leaves RUNNING
    @Test fun terminalStatus_cancelled_becomesStopped_neverLeavesRunning() {
        val status = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING",
            engineEvent = null,
            wasCancelled = true,
        )
        assertEquals("STOPPED", status)
    }

    @Test fun terminalStatus_existingTerminalStates_arePreserved() {
        for (terminal in listOf("DONE", "STOPPED", "STOPPED_LIMIT", "CANCELLED", "FAILED")) {
            val status = TaskStateTransitions.resolveTerminalStatus(
                currentStatus = terminal,
                engineEvent = null,
                wasCancelled = false,
            )
            assertEquals(terminal, status)
        }
    }
}
