package com.botati.domain.contracts

/** Outcome of resolving a target expression against discovery results (§11.2/§19). */
sealed interface Resolution {
    /** Zero matches: the task ends DONE with a "not found" report (§29). */
    data object None : Resolution
    /** Exactly one: pinned into the contract before approval (§19). */
    data class Single(val id: String) : Resolution
    /** More than one: the user picks at approval time; never auto-chosen. */
    data class Multiple(val ids: List<String>) : Resolution
}

object TargetResolver {
    fun resolve(candidateIds: List<String>): Resolution = when {
        candidateIds.isEmpty() -> Resolution.None
        candidateIds.size == 1 -> Resolution.Single(candidateIds.first())
        else -> Resolution.Multiple(candidateIds)
    }
}
