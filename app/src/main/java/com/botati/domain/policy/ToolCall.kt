package com.botati.domain.policy

import com.botati.domain.security.Sha256
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
 * adds that action alone to the contract"). One-shot (SEC-02): the grant is
 * bound to the exact (tool, target, arguments digest, Pause reason) the user
 * was shown, lives in memory for the current task run only, and is consumed
 * after the first execution that runs under it — a repeat of the same call
 * Pauses again. It is never persisted and never folded into [ScopeContract],
 * so the approved contract hash keeps describing exactly what was approved
 * up front. NEW_RECIPIENT and FORBIDDEN_ACTION are never grantable.
 */
data class GrantKey(
    val tool: String,
    val target: String?,
    /** SHA-256 of the call's raw arguments JSON — a different body is a different action. */
    val argsDigest: String,
    /** The exact Pause reason the user approved. */
    val reason: ReasonCode,
) {
    /** True only for the exact call this grant was issued for. */
    fun matches(call: ToolCall): Boolean =
        tool == call.name && target == call.target && argsDigest == Sha256.hex(call.arguments.toString())

    companion object {
        fun forCall(call: ToolCall, reason: ReasonCode): GrantKey =
            GrantKey(call.name, call.target, Sha256.hex(call.arguments.toString()), reason)
    }
}

/** Execution session for a decision: plain chat, or a task under a contract. */
sealed interface Session {
    data object Chat : Session
    data class Task(
        val contract: ScopeContract,
        val grants: Set<GrantKey> = emptySet(),
    ) : Session {
        fun withGrant(key: GrantKey): Task = copy(grants = grants + key)
        fun withGrant(call: ToolCall, reason: ReasonCode): Task = withGrant(GrantKey.forCall(call, reason))

        /**
         * Consumes every grant that would authorize this exact call. At most
         * one can match: the scope reason for identical input is deterministic.
         */
        fun withoutGrantFor(call: ToolCall): Task =
            copy(grants = grants.filterNot { it.matches(call) }.toSet())
    }
}

/** Monotonic counters for the current task (or conversation turn). */
data class Counters(
    val stepsUsed: Int = 0,
    val costUsedUsd: Double = 0.0,
    val d2Contaminated: Boolean = false,
)
