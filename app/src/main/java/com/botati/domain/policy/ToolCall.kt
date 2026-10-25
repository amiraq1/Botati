package com.botati.domain.policy

import kotlinx.serialization.json.JsonObject

enum class TargetKind { NONE, THREAD, FILE, URL }

/**
 * One tool invocation as the Policy Engine sees it (§6.2/§28): not just a tool
 * name — the arguments, the resolved target and the recipients all feed the
 * decision. Two calls to the same tool with different targets can and must
 * decide differently.
 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val targetKind: TargetKind = TargetKind.NONE,
    /** threadId / file URI / URL, depending on [targetKind]. */
    val target: String? = null,
    /** Outbound recipients the call would reach (mail tools). */
    val recipients: List<String> = emptyList(),
)

/**
 * One action the user approved while the task was running (§11.3: "the approval
 * adds that action alone to the contract"). Held in memory for the current task
 * run only — it is never persisted and never folded into [ScopeContract], so the
 * approved contract hash keeps describing exactly what was approved up front.
 */
data class GrantKey(val tool: String, val target: String?)

/** Execution session for a decision: plain chat, or a task under a contract. */
sealed interface Session {
    data object Chat : Session
    data class Task(
        val contract: ScopeContract,
        val grants: Set<GrantKey> = emptySet(),
    ) : Session {
        fun withGrant(tool: String, target: String?): Task = copy(grants = grants + GrantKey(tool, target))
    }
}

/** Monotonic counters for the current task (or conversation turn). */
data class Counters(
    val stepsUsed: Int = 0,
    val costUsedUsd: Double = 0.0,
    val d2Contaminated: Boolean = false,
)
