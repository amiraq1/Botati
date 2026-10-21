package com.botati.domain.policy

import kotlinx.serialization.Serializable

/**
 * Two-axis tool classification (spec §18, supersedes the single R axis of §6.1).
 * The two axes are independent: an action can be a harmless read (E0) while
 * still disclosing private data to a model or external service (D2).
 */
@Serializable
enum class Effect {
    /** Read only. */
    E0,
    /** Creates a draft that can be edited/discarded. */
    E1,
    /** Reversible write/modification. */
    E2,
    /** Irreversible send/execution. */
    E3,
}

@Serializable
enum class Disclosure {
    /** Output stays on the device. */
    D0,
    /** Public/general query leaves the device. */
    D1,
    /** Private data leaves the device (to the LLM or an external service). */
    D2,
}

/** Where a tool comes from. Classification NEVER comes from the model. */
@Serializable
enum class ToolSource { BUILTIN, REMOTE_API, MCP }

/**
 * Registry metadata for one tool. [effect]/[disclosure] are assigned locally:
 * built-ins in code, MCP tools by the user at server-sync time (unknown MCP
 * tools default to E3/D2 per §18 until the user classifies them).
 */
@Serializable
data class ToolMeta(
    val name: String,
    val effect: Effect,
    val disclosure: Disclosure,
    val source: ToolSource,
    val description: String = "",
    val inputSchemaJson: String = "{}",
) {
    companion object {
        /** Conservative default for an undiscovered MCP tool (§18). */
        fun unknownMcp(name: String, description: String = ""): ToolMeta =
            ToolMeta(name, Effect.E3, Disclosure.D2, ToolSource.MCP, description)
    }
}

/** The policy-relevant slice of a bot. The engine never sees UI/DB types. */
data class BotPolicyProfile(
    val botId: String,
    val allowedTools: Set<String>,
    /**
     * Disclosure approvals already granted in chat, keyed by
     * "disclose:<target-or-tool>" (§18: D2 in chat needs approval per source,
     * once). Managed by the app layer from the Approvals table.
     */
    val preApprovedDisclosures: Set<String> = emptySet(),
)
