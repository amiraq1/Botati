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
 */
@Serializable
enum class DataCollection {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
}

/** True for OpenRouter free-tier model ids (`:free` suffix, case-insensitive). */
fun isFreeModel(model: String): Boolean = model.trim().lowercase().endsWith(":free")

/**
 * Pure routing decision for one request. Only a clean session on a free
 * model is allowed; anything else — paid model, contaminated session, or a
 * blank/unknown model id — is deny. Fails toward deny on doubt.
 */
fun dataCollectionFor(model: String, d2Contaminated: Boolean): DataCollection =
    if (isFreeModel(model) && !d2Contaminated) DataCollection.Allow else DataCollection.Deny
