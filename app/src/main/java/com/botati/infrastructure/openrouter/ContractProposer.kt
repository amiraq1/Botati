package com.botati.infrastructure.openrouter

import com.botati.domain.agent.LlmMessage
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
 * Targets still arrive unresolved here if they use placeholders; the UI flow
 * resolves them (discovery) and pins real ids before the approval tap (§19).
 */
class ContractProposer(private val client: OpenRouterClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun propose(
        instruction: String,
        model: String,
        taskId: String,
        candidateTools: List<String>,
        provider: String = "openrouter",
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
            maxSteps (<=50), budgetUsd (<=5). No prose, no code fences.
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
                    responseFormat = if (attempt == 0 && provider == "openrouter") {
                        OpenRouterClient.contractResponseFormat()
                    } else null,
                    provider = provider,
                )
            } catch (e: Exception) {
                return Result.failure(e)
            }
            val contract = parseDraft(raw, taskId, candidateTools)
            if (contract != null) {
                val errors = contract.validationErrors()
                // Placeholder thread targets are resolved pre-approval (§19), so
                // the threadIds requirement is enforced at approval time, not here.
                val blocking = errors.filterNot { it.contains("threadIds") }
                if (blocking.isEmpty()) return Result.success(contract)
                lastError = blocking.joinToString("; ")
            } else {
                lastError = "unparseable JSON"
            }
        }
        return Result.failure(IllegalStateException("contract proposal failed validation: $lastError"))
    }

    private fun parseDraft(raw: String, taskId: String, candidateTools: List<String>): ScopeContract? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val draft = try {
            json.decodeFromString<ContractDraft>(cleaned.substring(start, end + 1))
        } catch (e: Exception) { return null }
        val verb = runCatching { ContractVerb.valueOf(draft.verb) }.getOrNull() ?: return null
        val tools = draft.allowedTools.filter { it in candidateTools }
        if (tools.isEmpty()) return null
        return ScopeContract(
            taskId = taskId,
            verb = verb,
            allowedTools = tools,
            targets = ContractTargets(
                threadIds = draft.threadIds, fileUris = draft.fileUris, query = draft.query,
            ),
            dataDisclosure = runCatching { DataDisclosureScope.valueOf(draft.dataDisclosure) }
                .getOrDefault(DataDisclosureScope.PUBLIC_ONLY),
            recipientsLock = runCatching { RecipientLock.valueOf(draft.recipientsLock) }
                .getOrDefault(RecipientLock.NONE),
            forbidden = draft.forbidden.mapNotNull { runCatching { ForbiddenAction.valueOf(it) }.getOrNull() }
                .ifEmpty { listOf(ForbiddenAction.FORWARD, ForbiddenAction.NEW_RECIPIENT) },
            maxSteps = draft.maxSteps.coerceIn(1, 50),
            budgetUsd = draft.budgetUsd.coerceIn(0.01, 5.0),
        )
    }
}
