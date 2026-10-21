package com.botati.domain.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The Policy Engine (spec §6.2 + §18 + §28). A PURE function: no network, no
 * UI, no database. It receives the full call (name + arguments + target +
 * recipients), the tool's locally-registered two-axis classification, the
 * session (chat, or a task with its approved contract) and counters — and
 * decides by itself. An externally computed `inScope` boolean is banned by
 * design: scope is checked here, where it can be unit-tested.
 *
 * Precedence (§6.2): Deny → ceiling Stop → out-of-contract Pause →
 * chat tier Ask/Allow → Allow.
 */
object PolicyEngine {

    fun decide(
        bot: BotPolicyProfile,
        call: ToolCall,
        meta: ToolMeta,
        session: Session,
        counters: Counters,
        nowMs: Long,
    ): PolicyDecision {
        // 1) The tool must be in the bot's allow-list at all.
        if (call.name !in bot.allowedTools) {
            return PolicyDecision.Deny(ReasonCode.NOT_ALLOWED)
        }

        val disclosure = effectiveDisclosure(call, meta)

        return when (session) {
            is Session.Chat -> decideChat(bot, call, meta, disclosure)
            is Session.Task -> decideTask(call, meta, disclosure, session.contract, counters, nowMs)
        }
    }

    // ---- chat ------------------------------------------------------------

    private fun decideChat(
        bot: BotPolicyProfile,
        call: ToolCall,
        meta: ToolMeta,
        disclosure: Disclosure,
    ): PolicyDecision = when {
        // Irreversible actions always ask, with the full payload shown (§6.1 R4).
        meta.effect == Effect.E3 ->
            PolicyDecision.Ask(ReasonCode.TIER_ASK, requiresFullReview = true)
        // Drafts and reversible writes ask once per call.
        meta.effect >= Effect.E1 ->
            PolicyDecision.Ask(ReasonCode.TIER_ASK)
        // Private data leaving the device asks once per source (§18).
        disclosure == Disclosure.D2 -> {
            val key = "disclose:" + (call.target ?: call.name)
            if (key in bot.preApprovedDisclosures) PolicyDecision.Allow(ReasonCode.PRE_APPROVED)
            else PolicyDecision.Ask(ReasonCode.DISCLOSURE_ASK)
        }
        else -> PolicyDecision.Allow(ReasonCode.CHAT_READ)
    }

    // ---- task ------------------------------------------------------------

    private fun decideTask(
        call: ToolCall,
        meta: ToolMeta,
        disclosure: Disclosure,
        contract: ScopeContract,
        counters: Counters,
        nowMs: Long,
    ): PolicyDecision {
        // 2) Ceilings Stop before any Pause question (§6.2: asking for
        //    permission with no budget left is meaningless).
        if (counters.stepsUsed >= contract.maxSteps) {
            return PolicyDecision.Stop(ReasonCode.STEP_CEILING)
        }
        if (counters.costUsedUsd >= contract.budgetUsd) {
            return PolicyDecision.Stop(ReasonCode.BUDGET_CEILING)
        }
        if (contract.expiresAtMs != null && nowMs > contract.expiresAtMs) {
            return PolicyDecision.Stop(ReasonCode.CONTRACT_EXPIRED)
        }

        // 3) Contract scope: tool, verb ceiling, forbidden actions, target,
        //    recipients lock, disclosure scope.
        if (call.name !in contract.allowedTools) {
            return PolicyDecision.Pause(ReasonCode.TOOL_NOT_IN_CONTRACT)
        }
        if (meta.effect > contract.verb.maxEffect) {
            return PolicyDecision.Pause(ReasonCode.ACTION_BEYOND_VERB)
        }
        forbiddenHit(call, contract)?.let { hit ->
            return PolicyDecision.Pause(
                if (hit == ForbiddenAction.NEW_RECIPIENT) ReasonCode.NEW_RECIPIENT
                else ReasonCode.FORBIDDEN_ACTION,
            )
        }
        if (!targetAllowed(call, contract)) {
            return PolicyDecision.Pause(ReasonCode.TARGET_MISMATCH)
        }
        if (!recipientsAllowed(call, contract)) {
            return PolicyDecision.Pause(ReasonCode.NEW_RECIPIENT)
        }
        if (!disclosureAllowed(disclosure, contract)) {
            return PolicyDecision.Pause(ReasonCode.DISCLOSURE_NOT_IN_CONTRACT)
        }

        // 5) Everything checked: execute (the caller writes the audit entry).
        return PolicyDecision.Allow(ReasonCode.IN_CONTRACT)
    }

    // ---- helpers -----------------------------------------------------------

    /** §18: a nominally-public query carrying private payload is D2. */
    fun effectiveDisclosure(call: ToolCall, meta: ToolMeta): Disclosure {
        val flag = (call.arguments["privatePayload"] as? JsonPrimitive)?.booleanOrNull
        return if (flag == true) Disclosure.D2 else meta.disclosure
    }

    private fun forbiddenHit(call: ToolCall, contract: ScopeContract): ForbiddenAction? {
        val name = call.name.lowercase()
        for (action in contract.forbidden) {
            val hit = when (action) {
                ForbiddenAction.FORWARD -> "forward" in name
                ForbiddenAction.DELETE -> "delete" in name || "trash" in name
                ForbiddenAction.PURCHASE -> "purchase" in name || "pay" in name || "buy" in name
                ForbiddenAction.ATTACHMENTS_OUT -> hasAttachments(call)
                // Recipient widening is judged by the lock check below; the
                // forbidden entry additionally fires for any recipient at all
                // under a NONE lock.
                ForbiddenAction.NEW_RECIPIENT ->
                    call.recipients.isNotEmpty() && contract.recipientsLock == RecipientLock.NONE
            }
            if (hit) return action
        }
        return null
    }

    private fun hasAttachments(call: ToolCall): Boolean {
        val arr = call.arguments["attachments"] as? JsonArray
        if (arr != null && arr.isNotEmpty()) return true
        val count = (call.arguments["attachmentCount"] as? JsonPrimitive)?.content?.toIntOrNull()
        return (count ?: 0) > 0
    }

    private fun targetAllowed(call: ToolCall, contract: ScopeContract): Boolean {
        val targets = contract.targets
        return when (call.targetKind) {
            TargetKind.THREAD ->
                targets.threadIds.isEmpty() || (call.target != null && call.target in targets.threadIds)
            TargetKind.FILE ->
                targets.fileUris.isEmpty() || (call.target != null && call.target in targets.fileUris)
            else -> true
        }
    }

    private fun recipientsAllowed(call: ToolCall, contract: ScopeContract): Boolean {
        if (call.recipients.isEmpty()) return true
        val allowed: Set<String> = when (contract.recipientsLock) {
            RecipientLock.THREAD_PARTICIPANTS_ONLY -> contract.targets.threadParticipantIds.toSet()
            RecipientLock.ORIGINAL_SENDER_ONLY ->
                listOfNotNull(contract.targets.originalSenderId).toSet()
            RecipientLock.NONE -> emptySet()
        }
        return call.recipients.all { it in allowed }
    }

    private fun disclosureAllowed(disclosure: Disclosure, contract: ScopeContract): Boolean =
        when (disclosure) {
            Disclosure.D0 -> true
            Disclosure.D1 -> contract.dataDisclosure != DataDisclosureScope.NONE
            Disclosure.D2 -> contract.dataDisclosure == DataDisclosureScope.PRIVATE_TO_LLM
        }
}
