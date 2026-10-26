package com.botati.domain.policy

/** Why a decision was made (§28: a bare "denied" is not enough to audit). */
enum class ReasonCode {
    IN_CONTRACT,
    CHAT_READ,
    PRE_APPROVED,
    TIER_ASK,
    DISCLOSURE_ASK,
    NOT_ALLOWED,
    STEP_CEILING,
    BUDGET_CEILING,
    CONTRACT_EXPIRED,
    TOOL_NOT_IN_CONTRACT,
    TARGET_MISMATCH,
    FORBIDDEN_ACTION,
    NEW_RECIPIENT,
    DISCLOSURE_NOT_IN_CONTRACT,
    ACTION_BEYOND_VERB,

    /**
     * The call is outside the contract but matched a one-shot session grant
     * for this exact (tool, target, arguments, Pause reason). This is distinct
     * from a UI approval, which authorizes only the pending call and is not
     * persisted for a later invocation.
     */
    GRANTED_ONCE,
}

/**
 * The five distinct decisions (§6.2). They are NOT folded into one another:
 * Pause (task, out of contract) asks for approval of the pending call; the
 * engine executes only that call if approved. Ask (chat) is a one-off consent;
 * Stop ends the task on a ceiling.
 */
sealed class PolicyDecision {
    abstract val reasonCode: ReasonCode

    data class Allow(override val reasonCode: ReasonCode) : PolicyDecision()
    data class Ask(
        override val reasonCode: ReasonCode,
        /** E3 actions are shown in full (e.g. the complete email body). */
        val requiresFullReview: Boolean = false,
    ) : PolicyDecision()
    data class Deny(override val reasonCode: ReasonCode) : PolicyDecision()
    data class Pause(override val reasonCode: ReasonCode) : PolicyDecision()
    data class Stop(override val reasonCode: ReasonCode) : PolicyDecision()
}
