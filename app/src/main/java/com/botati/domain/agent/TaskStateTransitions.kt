package com.botati.domain.agent

/**
 * Task state transitions helper (F-04, F-18).
 * Pure domain logic: resolves the final task status and guarantees terminal
 * states are never overwritten by "DONE", and cancelled tasks never remain
 * "RUNNING".
 *
 * BOT-09/BOT-16: the set of terminal states now comes from [TaskStatus] — one
 * authority instead of a hand-maintained string set that omitted INTERRUPTED
 * and mentioned a "STOPPED" the app never wrote.
 */
object TaskStateTransitions {
    val TERMINAL_STATES: Set<String> = TaskStatus.TERMINAL.map { it.wire }.toSet()

    fun resolveTerminalStatus(
        currentStatus: String,
        engineEvent: EngineEvent? = null,
        wasCancelled: Boolean = false,
    ): String {
        // Once a task has reached a final state, do not overwrite it.
        val current = TaskStatus.parse(currentStatus)
        if (current != null && current.isTerminal) return current.wire

        return when {
            engineEvent is EngineEvent.Failed -> TaskStatus.FAILED.wire
            engineEvent is EngineEvent.Stopped -> TaskStatus.forStopReason(engineEvent.reason).wire
            wasCancelled -> TaskStatus.CANCELLED.wire
            else -> TaskStatus.DONE.wire
        }
    }
}
