package com.botati.domain.agent

/** Where a cost figure came from (§26) — a number is never shown sourceless. */
enum class CostSource { ACTUAL, ESTIMATED, UNKNOWN }

/** Usage as reported by one provider response. `costUsd` is often absent. */
data class RawUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costUsd: Double? = null,
)

data class ResolvedCost(val costUsd: Double?, val source: CostSource)

/** Local per-model price table fallback (§26): USD per 1M tokens. */
data class ModelPrice(val promptPer1M: Double, val completionPer1M: Double)

object UsageAccounting {
    /** Conservative built-in fallbacks; user-editable prices can extend this. */
    val priceTable: Map<String, ModelPrice> = mapOf(
        "openai/gpt-4o-mini" to ModelPrice(0.15, 0.60),
        "openai/gpt-4o" to ModelPrice(2.50, 10.00),
        "anthropic/claude-sonnet-4" to ModelPrice(3.00, 15.00),
        "google/gemini-2.0-flash-001" to ModelPrice(0.10, 0.40),
    )

    /**
     * §5.5/§26: a missing provider cost is NEVER silently zero.
     * Order: provider cost → price-table estimate → UNKNOWN (null).
     */
    fun resolve(model: String, usage: RawUsage, prices: Map<String, ModelPrice> = priceTable): ResolvedCost {
        usage.costUsd?.let { return ResolvedCost(it, CostSource.ACTUAL) }
        val price = prices[model]
        if (price != null) {
            val est = usage.promptTokens / 1_000_000.0 * price.promptPer1M +
                usage.completionTokens / 1_000_000.0 * price.completionPer1M
            return ResolvedCost(est, CostSource.ESTIMATED)
        }
        return ResolvedCost(null, CostSource.UNKNOWN)
    }

    /** §5.5 chosen rule: stop a task after this many consecutive UNKNOWN-cost steps. */
    const val MAX_CONSECUTIVE_UNKNOWN_COST_STEPS = 3
}

/** Accumulates usage for one task/run and enforces the UNKNOWN-cost rule. */
class UsageTracker {
    var totalPromptTokens: Int = 0
        private set
    var totalCompletionTokens: Int = 0
        private set
    var totalKnownCostUsd: Double = 0.0
        private set
    var consecutiveUnknownCostSteps: Int = 0
        private set

    fun record(model: String, usage: RawUsage): ResolvedCost {
        val resolved = UsageAccounting.resolve(model, usage)
        totalPromptTokens += usage.promptTokens
        totalCompletionTokens += usage.completionTokens
        if (resolved.source == CostSource.UNKNOWN) {
            consecutiveUnknownCostSteps++
        } else {
            consecutiveUnknownCostSteps = 0
            totalKnownCostUsd += resolved.costUsd ?: 0.0
        }
        return resolved
    }

    fun shouldStopForUnknownCost(): Boolean =
        consecutiveUnknownCostSteps >= UsageAccounting.MAX_CONSECUTIVE_UNKNOWN_COST_STEPS
}
