package com.botati.domain.agent

/**
 * Task state transitions helper (F-04, F-18).
 * Pure domain logic: resolves final task status and guarantees terminal states
 * are never overwritten by "DONE", and cancelled tasks never remain "RUNNING".
 */
object TaskStateTransitions {
    val TERMINAL_STATES = setOf("DONE", "STOPPED", "STOPPED_LIMIT", "CANCELLED", "FAILED")

    fun resolveTerminalStatus(
        currentStatus: String,
        engineEvent: EngineEvent? = null,
        wasCancelled: Boolean = false,
    ): String {
        // Once a task has reached a final state other than RUNNING/PROPOSED, do not overwrite it
        if (currentStatus in TERMINAL_STATES) {
            return currentStatus
        }

        return when {
            engineEvent is EngineEvent.Failed -> "FAILED"
            engineEvent is EngineEvent.Stopped -> "STOPPED_LIMIT"
            wasCancelled -> "STOPPED"
            else -> "DONE"
        }
    }
}
