package com.botati.domain.agent

/**
 * The closed set of task states (§7/§5.7). Room stores the [wire] string, so
 * this enum is the single authority on which strings are legal and which are
 * terminal — the previous design compared raw strings in three places and had
 * a `"STOPPED"` that existed nowhere but the comparator.
 *
 * BOT-16: every value that can be written by the app appears here. A stop is
 * no longer always recorded as STOPPED_LIMIT: a safety stop (free model with
 * D2 content, three unknown tools) is not a ceiling, and an unknown-outcome
 * send is not a limit either — collapsing them hid why a task stopped.
 */
enum class TaskStatus(val wire: String) {
    PROPOSED("PROPOSED"),
    RUNNING("RUNNING"),
    PAUSED_OUT_OF_SCOPE("PAUSED_OUT_OF_SCOPE"),

    /** Ceiling reached: steps, budget, or unknown-cost guard. */
    STOPPED_LIMIT("STOPPED_LIMIT"),

    /** Stopped by policy, not by a budget: D2 on a free model, repeated unknown tools. */
    STOPPED_POLICY("STOPPED_POLICY"),

    /** An irreversible action failed with an unknown outcome — never auto-retried. */
    SEND_UNKNOWN("SEND_UNKNOWN"),

    /** Cancelled by the user. */
    CANCELLED("CANCELLED"),

    /**
     * Legacy: written by builds before this enum existed (the `finally` block
     * used it for a cancellation). Kept so a row from an older install is still
     * recognised as terminal instead of being overwritten with DONE. Nothing
     * writes it any more — cancellation is [CANCELLED].
     */
    @Deprecated("Legacy value; cancellation writes CANCELLED")
    STOPPED("STOPPED"),

    /** The process died mid-run, or the FGS timed out (§5.7/§22). */
    INTERRUPTED("INTERRUPTED"),

    DONE("DONE"),
    FAILED("FAILED"),
    ;

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        /**
         * INTERRUPTED is terminal on purpose (BOT-09): `onTimeout()` writes it
         * while the engine is still unwinding, and the old terminal set did not
         * include it — so a late DONE overwrote the interruption.
         */
        val TERMINAL: Set<TaskStatus> = setOf(
            DONE, FAILED, CANCELLED, INTERRUPTED, STOPPED_LIMIT, STOPPED_POLICY, SEND_UNKNOWN, STOPPED,
        )

        fun parse(raw: String?): TaskStatus? = TaskStatus.entries.firstOrNull { it.wire == raw }

        /** Maps an `EngineEvent.Stopped` reason onto the state it represents. */
        fun forStopReason(reason: String): TaskStatus = when {
            reason.startsWith(AgentEngine.SEND_UNKNOWN_REASON) -> SEND_UNKNOWN
            reason.contains("ceiling", ignoreCase = true) -> STOPPED_LIMIT
            else -> STOPPED_POLICY
        }
    }
}
