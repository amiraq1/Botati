package com.botati.infrastructure.openrouter

import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.RawUsage
import com.botati.domain.policy.ContractTargets
import com.botati.domain.policy.ContractVerb
import com.botati.domain.policy.DataDisclosureScope
import com.botati.domain.policy.ForbiddenAction
import com.botati.domain.policy.RecipientLock
import com.botati.domain.policy.ScopeContract
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ContractDraft(
    val verb: String = "READ_ONLY",
    val allowedTools: List<String> = emptyList(),
    val threadIds: List<String> = emptyList(),
    val fileUris: List<String> = emptyList(),
    val query: String? = null,
    val dataDisclosure: String = "PUBLIC_ONLY",
    val recipientsLock: String = "NONE",
    val forbidden: List<String> = listOf("FORWARD", "NEW_RECIPIENT", "ATTACHMENTS_OUT"),
    val maxSteps: Int = 25,
    val budgetUsd: Double = 0.30,
)

/**
 * Contract proposal (§11.1/§19): the model proposes a scope contract as JSON
 * (Structured Outputs when the endpoint supports it — a SHAPE guarantee
 * only). The draft is decoded locally, unknown verbs/tools are rejected or
 * dropped, the result is validated, and on failure there is exactly ONE retry
 * carrying the error. A "mostly valid" contract never starts a task.
 *
 * SEC-04: every closed enum is parsed strictly. An invented value is a failed
 * attempt, never a silent coercion — coercing `forbidden` to a shorter list is
 * exactly how ATTACHMENTS_OUT disappeared from a contract, and coercing
 * `dataDisclosure` silently widened or narrowed what the user was shown.
 * Case is normalised (it carries no meaning); an unknown NAME is rejected.
 *
 * Targets still arrive unresolved here if they use placeholders; the UI flow
 * resolves them (discovery) and pins real ids before the approval tap (§19).
 * Until that exists, `approveContract` refuses a contract that still contains
 * an `auto:` placeholder or an unresolved reply target.
 */
class ContractProposer(
    private val client: OpenRouterClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun propose(
        instruction: String,
        model: String,
        taskId: String,
        candidateTools: List<String>,
        /** §26: the proposal is a paid request; the caller records its usage. */
        onUsage: (suspend (RawUsage) -> Unit)? = null,
    ): Result<ScopeContract> {
        val system = """
            You output ONLY a JSON object for a task scope contract. Fields:
            verb: one of READ_ONLY, DRAFT_ONLY, REPLY_AND_SEND (use REPLY_AND_SEND
            only when the instruction clearly asks to actually send/reply, DRAFT_ONLY
            when it asks to prepare/draft, otherwise READ_ONLY);
            allowedTools: subset of ${candidateTools};
            threadIds, fileUris, query (discovery targets, may be empty);
            dataDisclosure: NONE|PUBLIC_ONLY|PRIVATE_TO_LLM (PRIVATE_TO_LLM when the
            task reads email/files);
            recipientsLock: NONE|THREAD_PARTICIPANTS_ONLY|ORIGINAL_SENDER_ONLY;
            forbidden: subset of FORWARD, NEW_RECIPIENT, ATTACHMENTS_OUT, DELETE, PURCHASE;
            maxSteps (<=${ScopeContract.MAX_STEPS_LIMIT}), budgetUsd (<=${ScopeContract.MAX_BUDGET_USD}).
            No prose, no code fences. Use only the enum names above, spelled exactly.
        """.trimIndent()

        var lastError: String? = null
        repeat(2) { attempt ->
            val user = buildString {
                append("Instruction: ").append(instruction)
                if (lastError != null) append("\nPrevious attempt failed validation: $lastError. Fix it.")
            }
            val raw = try {
                client.complete(
                    model = model,
                    messages = listOf(LlmMessage("system", system), LlmMessage("user", user)),
                    temperature = 0.1,
                    responseFormat = if (attempt == 0) OpenRouterClient.contractResponseFormat() else null,
                    onUsage = onUsage,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
            val contract = parseDraft(raw, taskId, candidateTools, nowMs())
            if (contract != null) {
                val errors = contract.validationErrors()
                // Placeholder thread targets are resolved pre-approval (§19), so
                // the threadIds requirement is enforced at approval time, not here.
                val blocking = errors.filterNot { it.contains("threadIds") }
                if (blocking.isEmpty()) return Result.success(contract)
                lastError = blocking.joinToString("; ")
            } else {
                lastError = "unparseable JSON, unknown enum name, or no allowed tool survived"
            }
        }
        return Result.failure(IllegalStateException("contract proposal failed validation: $lastError"))
    }

    private fun parseDraft(
        raw: String,
        taskId: String,
        candidateTools: List<String>,
        nowMs: Long,
    ): ScopeContract? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val draft = try {
            json.decodeFromString<ContractDraft>(cleaned.substring(start, end + 1))
        } catch (e: Exception) { return null }
        // Strict, case-normalised enum parsing: an unknown NAME fails the attempt.
        val verb = enumOf<ContractVerb>(draft.verb) ?: return null
        val dataDisclosure = enumOf<DataDisclosureScope>(draft.dataDisclosure) ?: return null
        val recipientsLock = enumOf<RecipientLock>(draft.recipientsLock) ?: return null
        val forbidden = if (draft.forbidden.isEmpty()) {
            // An empty list must never SHRINK the default set (§11.2).
            ScopeContract.DEFAULT_FORBIDDEN
        } else {
            draft.forbidden.map { enumOf<ForbiddenAction>(it) ?: return null }
        }
        val tools = draft.allowedTools.filter { it in candidateTools }
        if (tools.isEmpty()) return null
        return ScopeContract(
            taskId = taskId,
            verb = verb,
            allowedTools = tools,
            targets = ContractTargets(
                threadIds = draft.threadIds, fileUris = draft.fileUris, query = draft.query,
            ),
            dataDisclosure = dataDisclosure,
            recipientsLock = recipientsLock,
            forbidden = forbidden,
            maxSteps = draft.maxSteps.coerceIn(1, ScopeContract.MAX_STEPS_LIMIT),
            budgetUsd = draft.budgetUsd.coerceIn(0.01, ScopeContract.MAX_BUDGET_USD),
            // §19: a contract with no expiry is a permanent authorization. The
            // model never sets this; the client does.
            expiresAtMs = nowMs + DEFAULT_TTL_MS,
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String): T? {
        val normalized = raw.trim().uppercase()
        return enumValues<T>().firstOrNull { it.name == normalized }
    }

    companion object {
        /** Long enough for a real task, short enough that a forgotten one dies. */
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
