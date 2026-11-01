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

    // F-04: cancellation writes CANCELLED and never leaves RUNNING.
    // BOT-16: it used to write a "STOPPED" that existed only in the comparator,
    // while stop() wrote CANCELLED — two writers, two values, one race.
    @Test fun terminalStatus_cancelled_becomesCancelled_neverLeavesRunning() {
        val status = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING",
            engineEvent = null,
            wasCancelled = true,
        )
        assertEquals("CANCELLED", status)
    }

    @Test fun terminalStatus_existingTerminalStates_arePreserved() {
        for (terminal in listOf(
            "DONE", "STOPPED", "STOPPED_LIMIT", "STOPPED_POLICY", "SEND_UNKNOWN",
            "CANCELLED", "FAILED", "INTERRUPTED",
        )) {
            val status = TaskStateTransitions.resolveTerminalStatus(
                currentStatus = terminal,
                engineEvent = null,
                wasCancelled = false,
            )
            assertEquals(terminal, status)
        }
    }

    // BOT-09: onTimeout() writes INTERRUPTED while the engine is still
    // unwinding; a late DONE must not overwrite it.
    @Test fun terminalStatus_interrupted_isNotOverwrittenByDone() {
        val status = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "INTERRUPTED",
            engineEvent = null,
            wasCancelled = false,
        )
        assertEquals("INTERRUPTED", status)
    }

    // BOT-16: a stop is not always a ceiling.
    @Test fun stoppedReason_mapsToTheStateItRepresents() {
        assertEquals("STOPPED_LIMIT", TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING", engineEvent = EngineEvent.Stopped("step ceiling"),
        ))
        assertEquals("STOPPED_LIMIT", TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING", engineEvent = EngineEvent.Stopped("ceiling: STEP_CEILING"),
        ))
        assertEquals("STOPPED_POLICY", TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING", engineEvent = EngineEvent.Stopped("three consecutive unknown tools"),
        ))
        assertEquals("SEND_UNKNOWN", TaskStateTransitions.resolveTerminalStatus(
            currentStatus = "RUNNING", engineEvent = EngineEvent.Stopped("SEND_UNKNOWN"),
        ))
    }
}
