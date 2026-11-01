package com.botati.domain.policy

import com.botati.domain.security.Sha256
import kotlinx.serialization.Serializable

/** Closed verb dictionary (§11.2/§19) — never free strings from the model. */
@Serializable
enum class ContractVerb(val maxEffect: Effect) {
    READ_ONLY(Effect.E0),
    DRAFT_ONLY(Effect.E1),
    REPLY_AND_SEND(Effect.E3),
}

@Serializable
enum class RecipientLock {
    /** Recipients may only be participants of the target thread(s). */
    THREAD_PARTICIPANTS_ONLY,
    /** Recipients may only be the original sender of the target thread. */
    ORIGINAL_SENDER_ONLY,
    /** No outbound recipients are relevant (read/draft tasks). */
    NONE,
}

/** Closed forbidden-action dictionary (§11.2) — enums, not model strings. */
@Serializable
enum class ForbiddenAction {
    FORWARD,
    NEW_RECIPIENT,
    ATTACHMENTS_OUT,
    DELETE,
    PURCHASE,
}

/** What data the contract authorizes to leave the device (§19). */
@Serializable
enum class DataDisclosureScope {
    NONE,
    PUBLIC_ONLY,
    PRIVATE_TO_LLM,
}

/** Concrete, resolved targets (§19: `auto:first_match` is never final). */
@Serializable
data class ContractTargets(
    val threadIds: List<String> = emptyList(),
    val threadParticipantIds: List<String> = emptyList(),
    val originalSenderId: String? = null,
    val fileUris: List<String> = emptyList(),
    val query: String? = null,
)

/**
 * An approved scope contract (§11/§19). Structured Outputs from the model are
 * a *shape* guarantee only; this type is validated locally after parsing, the
 * user approves it with a tap, and [contractHash] binds that approval — any
 * later change to tools/targets/limits invalidates the approval.
 */
@Serializable
data class ScopeContract(
    val schemaVersion: Int = 1,
    val taskId: String,
    val verb: ContractVerb,
    val allowedTools: List<String>,
    val targets: ContractTargets = ContractTargets(),
    val dataDisclosure: DataDisclosureScope = DataDisclosureScope.PUBLIC_ONLY,
    val recipientsLock: RecipientLock = RecipientLock.NONE,
    val forbidden: List<ForbiddenAction> = DEFAULT_FORBIDDEN,
    val maxSteps: Int = 25,
    val budgetUsd: Double = 0.30,
    val expiresAtMs: Long? = null,
) {
    fun computeHash(): String {
        val canonical = listOf(
            schemaVersion.toString(), taskId, verb.name,
            allowedTools.sorted().joinToString(","),
            targets.threadIds.sorted().joinToString(","),
            targets.threadParticipantIds.sorted().joinToString(","),
            targets.originalSenderId ?: "",
            targets.fileUris.sorted().joinToString(","),
            targets.query ?: "",
            dataDisclosure.name, recipientsLock.name,
            forbidden.map { it.name }.sorted().joinToString(","),
            maxSteps.toString(), budgetUsd.toString(),
            expiresAtMs?.toString() ?: "",
        ).joinToString("|")
        return Sha256.hex(canonical)
    }

    /** Structural validation after model JSON parses (§11.1 step 3). */
    fun validationErrors(): List<String> = buildList {
        if (schemaVersion != 1) add("unsupported schemaVersion $schemaVersion")
        if (taskId.isBlank()) add("taskId blank")
        if (allowedTools.isEmpty()) add("allowedTools empty")
        if (maxSteps !in 1..MAX_STEPS_LIMIT) add("maxSteps out of range 1..$MAX_STEPS_LIMIT")
        if (budgetUsd <= 0.0 || budgetUsd > MAX_BUDGET_USD) add("budgetUsd out of range")
        // §19: a discovery placeholder is never a resolved target. It used to
        // pass this check and then behave as a real thread id for send_reply.
        val unresolved = (targets.threadIds + targets.fileUris).filter { it.startsWith("auto:") }
        if (unresolved.isNotEmpty()) add("unresolved placeholder targets: $unresolved")
        if (verb == ContractVerb.REPLY_AND_SEND && targets.threadIds.isEmpty()) {
            add("reply_and_send requires resolved threadIds (§19: no auto:first_match)")
        }
        if (verb != ContractVerb.READ_ONLY && expiresAtMs == null) {
            add("a writing contract must carry an expiry (§19)")
        }
    }

    companion object {
        /** Single ceiling shared by the proposer, the validator and the settings UI. */
        const val MAX_STEPS_LIMIT = 50
        const val MAX_BUDGET_USD = 5.0

        /** Conservative default applied when the model emits no forbidden list. */
        val DEFAULT_FORBIDDEN: List<ForbiddenAction> = listOf(
            ForbiddenAction.FORWARD,
            ForbiddenAction.NEW_RECIPIENT,
            ForbiddenAction.ATTACHMENTS_OUT,
        )
    }
}
