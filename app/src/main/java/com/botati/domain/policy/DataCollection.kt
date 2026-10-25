package com.botati.domain.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenRouter `provider.data_collection` routing (§18/Q4).
 *
 * The invariant is disclosure, not price: a request that may carry D2
 * (private) content is routed only to providers that declare no storage or
 * training on data. That is the guarantee the parameter actually provides —
 * it does not mean "nothing is stored": providers may still retain data
 * briefly for abuse monitoring.
 *
 * Decision history: owner decision 2026-10-25 (Q4) assigned a paid model to
 * mail (D2) templates; the same day the owner corrected the rule from
 * price-based ("paid -> deny") to disclosure-based — after the first D2
 * tool result, every later request is deny, even on a free model. A D2
 * result only enters the context in the request after the tool call, so
 * the next request is already deny; and a free-model bot with a mail tool
 * attached cannot leak `read_email` results under "allow".
 *
 * 2026-10-26 (free-only decision): the owner dropped the top-up and chose
 * free models. A live sweep of all 17 free models showed most have no
 * deny-matching endpoint (404 "No endpoints found matching your data
 * policy"), but three returned real replies under deny — those three form
 * [DENY_COMPATIBLE_FREE_MODELS] below, and the mail template switched to
 * Flint. The engine's free-model pre-checks consult that set: an
 * allowlisted free model may carry D2 content, any other free model
 * still stops with a clear reason.
 */
@Serializable
enum class DataCollection {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
}

/** True for OpenRouter free-tier model ids (`:free` suffix, case-insensitive). */
fun isFreeModel(model: String): Boolean = model.trim().lowercase().endsWith(":free")

/**
 * Free models with a live-observed successful reply under
 * `provider.data_collection: "deny"` (verified 2026-10-26, owner decision:
 * free-only, mail template = Flint). Only these free models may carry D2
 * content: the engine stops any other free model before sending, instead
 * of failing with "no matching endpoints" or leaking under "allow".
 *
 * Evidence tiers from the 2026-10-26 live sweep of all 17 free models:
 * - confirmed (real reply under deny): Flint, Dots 3 Note, North Mini Code.
 * - routed but unconfirmed (routed under deny, then upstream 429 on the
 *   shared pool): Gemma 4 31B/26B, Xing 4.0 — NOT in this set until a
 *   successful deny reply is observed; they stay fail-closed.
 * - 404 "No endpoints found matching your data policy": all NVIDIA
 *   Nemotron free models, LFM 2.5, Laguna — never allowlisted.
 * Re-verify this set whenever the free catalog changes; the catalog
 * rotates (models come and go).
 */
val DENY_COMPATIBLE_FREE_MODELS: Set<String> = setOf(
    "recursive/flint-v1.0:free",
    "dots-studio/dots-3-note-preview:free",
    "cohere/north-mini-code:free",
)

/** True when a free model is known to match `data_collection: "deny"`. */
fun isDenyCompatibleFreeModel(model: String): Boolean {
    val normalized = model.trim().lowercase()
    return isFreeModel(normalized) && normalized in DENY_COMPATIBLE_FREE_MODELS
}

/**
 * Pure routing decision for one request. Only a clean session on a free
 * model is allowed; anything else — paid model, contaminated session, or a
 * blank/unknown model id — is deny. Fails toward deny on doubt.
 */
fun dataCollectionFor(model: String, d2Contaminated: Boolean): DataCollection =
    if (isFreeModel(model) && !d2Contaminated) DataCollection.Allow else DataCollection.Deny
