package com.botati.domain.agent

import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.Counters
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.PolicyEngine
import com.botati.domain.policy.Session
import com.botati.domain.policy.TargetKind
import com.botati.domain.policy.ToolCall
import com.botati.domain.policy.ToolMeta
import com.botati.domain.policy.isDenyCompatibleFreeModel
import com.botati.domain.policy.isFreeModel
import com.botati.domain.security.SecretRedactor
import com.botati.domain.security.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The agent loop (§5): build request (allowed tools only) → stream → assemble
 * tool calls from deltas → per call: PolicyEngine → approval if needed →
 * execute → wrapped/truncated result → repeat until a final answer, a
 * ceiling, a stop, or cancellation.
 *
 * Every policy decision is audited with its reasonCode (§28). Unknown tools
 * return a structured `tool_not_found` to the model and count as steps (§25);
 * three in a row stop the loop instead of spinning.
 *
 * Invariants this class owns:
 *  - §24 SEND_UNKNOWN: a failed irreversible (E3) call never returns an error
 *    the model could "retry with a tweak" — that is how a duplicate mail
 *    happens. The run stops instead.
 *  - §5.2: a truncated or mid-stream-error response is a failure, never a
 *    final answer.
 *  - §5.6: cancellation propagates; it is never converted into a tool result.
 *  - §27: history is trimmed by whole tool-call groups, and every emitted
 *    tool_call id gets a result, so the persisted wire history stays valid.
 */
class AgentEngine(
    private val llm: LlmPort,
    private val executor: ToolExecutorPort,
    private val approvals: ApprovalPort,
    private val audit: AuditPort,
    private val nowMs: () -> Long,
) {
    /** Whether the run may continue after one executed call. */
    private enum class ExecOutcome { OK, SEND_UNKNOWN }

    fun run(
        botId: String,
        model: String,
        temperature: Double,
        profile: BotPolicyProfile,
        registry: Map<String, ToolMeta>,
        initialMessages: List<LlmMessage>,
        session: Session,
        taskId: String? = null,
        defaultMaxSteps: Int = 25,
    ): Flow<EngineEvent> = flow {
        val emitEvent: suspend (EngineEvent) -> Unit = { e -> emit(e) }
        val messages = initialMessages.toMutableList()
        val tracker = UsageTracker()
        var steps = 0
        var consecutiveUnknown = 0
        var finished = false

        // §18/Q4: contamination is derived from the FULL saved history, before
        // truncation — never from the truncated working copy and never reset.
        // A D2 tool result dropped by truncation must not silently clear the
        // flag: later turns may still quote it, and a resumed conversation
        // must stay deny-routed.
        var d2Contaminated = initialMessages.any { it.isD2ToolResult(registry) }

        // §27/BOT-07: trim by whole groups (an assistant message plus the tool
        // results answering its tool_calls). Cutting inside a group produces a
        // history every OpenAI-compatible provider rejects with 400, which used
        // to brick the conversation permanently because the bad rows are in Room.
        val trim = ContextTruncation.trimHistory(messages)
        if (trim.droppedGroups > 0) {
            emit(EngineEvent.ContextTruncated(trim.droppedGroups))
            messages.clear()
            messages.addAll(trim.messages)
        }

        val maxSteps = when (val s = session) {
            is Session.Task -> s.contract.maxSteps
            is Session.Chat -> defaultMaxSteps
        }
        val toolSpecs = registry.values
            .filter { it.name in profile.allowedTools }
            .map { LlmToolSpec(it.name, it.description, it.inputSchemaJson) }

        while (!finished) {
            currentCoroutineContext().ensureActive()
            // §18/Q4: a free model cannot continue a D2-contaminated session
            // unless it is allowlisted as deny-compatible (verified live
            // 2026-10-26: most free models have no deny-matching endpoint
            // and fail with "no matching endpoints"). Stop with a clear
            // reason instead of a generic failure; the user switches the
            // model and retries.
            if (d2Contaminated && isFreeModel(model) && !isDenyCompatibleFreeModel(model)) {
                emit(EngineEvent.Stopped(FREE_MODEL_D2_REASON))
                finished = true
                break
            }
            val assembler = ToolCallDeltaAssembler()
            val text = StringBuilder()
            var streamError: String? = null
            var sawFinish = false
            var sawToolCalls = false

            llm.stream(
                LlmRequest(model, messages.toList(), toolSpecs, temperature, d2Contaminated),
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.TextDelta -> { text.append(ev.text); emit(EngineEvent.TextDelta(ev.text)) }
                    is LlmEvent.ToolDelta -> { sawToolCalls = true; assembler.feed(ev.delta) }
                    is LlmEvent.Usage -> {
                        val resolved = tracker.record(model, ev.usage)
                        emit(EngineEvent.UsageRecorded(model, ev.usage, resolved))
                    }
                    is LlmEvent.Error -> streamError = ev.message
                    is LlmEvent.Finished -> {
                        // BOT-04: `finish_reason: "error"` is a failure, not a
                        // completed turn — the text so far is a fragment.
                        if (ev.finishReason == "error") {
                            streamError = "provider reported a stream error"
                        } else {
                            sawFinish = true
                        }
                    }
                }
            }
            if (streamError != null) { emit(EngineEvent.Failed(streamError!!)); return@flow }
            // BOT-04: a stream that ends without a finish_reason was truncated.
            // Treating the partial text as a final answer silently loses output
            // and marks the task DONE.
            if (!sawFinish) {
                emit(EngineEvent.Failed("stream truncated: the provider closed the response without finish_reason"))
                return@flow
            }

            if (!sawToolCalls) {
                val msg = LlmMessage("assistant", text.toString())
                messages += msg
                emit(EngineEvent.AssistantCompleted(msg))
                finished = true
                break
            }

            when (val assembled = assembler.finish()) {
                is AssemblyResult.Invalid -> {
                    // Never execute unparseable arguments (§5.2): tell the model.
                    // The error goes back wrapped as tool data — it is
                    // model-visible text and must not outrank the system prompt.
                    val assistantText = LlmMessage("assistant", text.toString())
                    messages += assistantText
                    emit(EngineEvent.AssistantCompleted(assistantText))
                    val payload = buildJsonObject {
                        put("error", "invalid_tool_arguments")
                        put("detail", assembled.error)
                    }.toString()
                    messages += LlmMessage("user", SystemPromptBuilder.wrapToolResult(payload))
                    steps++
                    if (steps >= maxSteps) { emit(EngineEvent.Stopped("step ceiling")); finished = true }
                }
                is AssemblyResult.Complete -> {
                    val assistantMsg = LlmMessage("assistant", text.toString(), toolCalls = assembled.calls)
                    messages += assistantMsg
                    emit(EngineEvent.AssistantCompleted(assistantMsg))

                    for (assembledCall in assembled.calls) {
                        val call = toToolCall(assembledCall.name, assembledCall.arguments)
                        // §27/BOT-07: once the run is stopping, still give every
                        // remaining call id a result, so the persisted history
                        // keeps exactly one tool message per tool_call id.
                        if (finished) {
                            val skipped = buildJsonObject {
                                put("error", "not_executed")
                                put("hint", "the run stopped before this call")
                            }.toString()
                            messages += toolMessage(assembledCall, skipped)
                            emit(EngineEvent.ToolFinished(call, skipped, true))
                            continue
                        }
                        currentCoroutineContext().ensureActive()
                        steps++
                        val meta = registry[assembledCall.name]

                        if (meta == null) {
                            // §25: unknown tool — structured error, counts as a step.
                            consecutiveUnknown++
                            audit.record(auditRecord(botId, taskId, call, null, "tool_not_found", null))
                            val result = buildJsonObject {
                                put("error", "tool_not_found")
                                put("tool", assembledCall.name)
                                put("hint", "use only the tools provided")
                            }.toString()
                            messages += toolMessage(assembledCall, result)
                            emit(EngineEvent.ToolFinished(call, result, true))
                            if (consecutiveUnknown >= 3) {
                                emit(EngineEvent.Stopped("three consecutive unknown tools"))
                                finished = true
                            } else if (steps >= maxSteps) {
                                emit(EngineEvent.Stopped("step ceiling"))
                                finished = true
                            }
                            continue
                        }
                        consecutiveUnknown = 0

                        val counters = Counters(
                            stepsUsed = steps - 1,
                            costUsedUsd = tracker.totalKnownCostUsd,
                            d2Contaminated = d2Contaminated,
                        )
                        val decision = PolicyEngine.decide(profile, call, meta, session, counters, nowMs())
                        audit.record(auditRecord(botId, taskId, call, meta, decisionName(decision), decision))

                        // §18/Q4: a D2 tool must never execute on a free model
                        // unless it is allowlisted as deny-compatible — the
                        // result would contaminate the session and the next
                        // request could not be sent. Refuse before asking or
                        // executing, with a clear reason; the user switches
                        // the model. (Deny/Stop decisions execute nothing, so
                        // they need no guard.)
                        if (meta.disclosure == Disclosure.D2 && isFreeModel(model) &&
                            !isDenyCompatibleFreeModel(model) &&
                            decision !is PolicyDecision.Deny && decision !is PolicyDecision.Stop
                        ) {
                            emit(EngineEvent.Stopped(FREE_MODEL_D2_REASON))
                            finished = true
                            continue
                        }

                        when (decision) {
                            is PolicyDecision.Deny -> {
                                val result = buildJsonObject {
                                    put("error", "policy_denied")
                                    put("reason", decision.reasonCode.name)
                                }.toString()
                                messages += toolMessage(assembledCall, result)
                                emit(EngineEvent.ToolFinished(call, result, true))
                            }
                            is PolicyDecision.Stop -> {
                                emit(EngineEvent.Stopped("ceiling: ${decision.reasonCode}"))
                                finished = true
                                continue
                            }
                            is PolicyDecision.Ask, is PolicyDecision.Pause -> {
                                if (decision is PolicyDecision.Pause) emit(EngineEvent.Paused(call, decision))
                                val approved = approvals.requestApproval(call, decision)
                                if (!approved) {
                                    val result = buildJsonObject { put("error", "user_denied") }.toString()
                                    messages += toolMessage(assembledCall, result)
                                    emit(EngineEvent.ToolFinished(call, result, true))
                                } else {
                                    // Approval applies only to this pending call. Do not
                                    // persist a grant that could authorize a repeat.
                                    val outcome = executeAndAppend(call, meta, assembledCall, messages, emitEvent)
                                    if (meta.disclosure == Disclosure.D2) d2Contaminated = true
                                    if (outcome == ExecOutcome.SEND_UNKNOWN) {
                                        emit(EngineEvent.Stopped(SEND_UNKNOWN_REASON))
                                        finished = true
                                        continue
                                    }
                                }
                            }
                            is PolicyDecision.Allow -> {
                                val outcome = executeAndAppend(call, meta, assembledCall, messages, emitEvent)
                                if (meta.disclosure == Disclosure.D2) d2Contaminated = true
                                if (outcome == ExecOutcome.SEND_UNKNOWN) {
                                    emit(EngineEvent.Stopped(SEND_UNKNOWN_REASON))
                                    finished = true
                                    continue
                                }
                            }
                        }
                        if (steps >= maxSteps) {
                            emit(EngineEvent.Stopped("step ceiling"))
                            finished = true
                        }
                    }

                    if (session is Session.Task && tracker.shouldStopForUnknownCost()) {
                        emit(EngineEvent.Stopped("unknown-cost steps ceiling (§5.5)"))
                        finished = true
                    }
                }
            }
        }
        emit(EngineEvent.Done)
    }

    private suspend fun executeAndAppend(
        call: ToolCall,
        meta: ToolMeta,
        assembledCall: AssembledToolCall,
        messages: MutableList<LlmMessage>,
        emit: suspend (EngineEvent) -> Unit,
    ): ExecOutcome {
        emit(EngineEvent.ToolStarted(call))
        val raw = try {
            executor.execute(call)
        } catch (e: CancellationException) {
            // §5.6: cancellation is control flow, not a tool result.
            throw e
        } catch (e: Exception) {
            // §24/BOT-03: an irreversible action that failed after dispatch has
            // an UNKNOWN outcome — the server may well have performed it.
            // Handing an error back to the model invites "retry with a tweak",
            // which is precisely how a duplicate send happens. Stop the run and
            // make the uncertainty visible instead.
            if (meta.effect == Effect.E3) {
                val unknown = buildJsonObject {
                    put("error", "send_unknown")
                    put("hint", "the action may have been performed; do not retry")
                }.toString()
                messages += toolMessage(assembledCall, unknown)
                emit(EngineEvent.ToolFinished(call, unknown, true))
                return ExecOutcome.SEND_UNKNOWN
            }
            buildJsonObject {
                put("error", "tool_failed")
                put("detail", e.message?.take(200) ?: "unknown")
            }.toString()
        }
        val truncated = ContextTruncation.truncateToolResult(raw)
        messages += toolMessage(assembledCall, truncated)
        emit(EngineEvent.ToolFinished(call, truncated, truncated.startsWith("{\"error\"")))
        return ExecOutcome.OK
    }

    /**
     * A tool result counts as D2 when the registry classifies its tool D2
     * (§18: read_file, analyze_file, mail tools, MCP tools). Unknown tools
     * fail toward deny: a result whose tool is no longer registered (or was
     * never named) may still carry private content. D1 remote results
     * (web_search/web_fetch) need no special case: they are D2-elevated only
     * after contamination, which implies an earlier D2 result in the same
     * history — and the derivation reads the full history.
     */
    private fun LlmMessage.isD2ToolResult(registry: Map<String, ToolMeta>): Boolean {
        if (role != "tool") return false
        val toolName = name ?: return true
        return registry[toolName]?.disclosure == Disclosure.D2 || toolName !in registry
    }

    private fun toolMessage(call: AssembledToolCall, rawResult: String): LlmMessage =
        LlmMessage(
            role = "tool",
            content = SystemPromptBuilder.wrapToolResult(rawResult),
            toolCallId = call.id,
            name = call.name,
        )

    companion object {
        /**
         * Shown when a free-model bot cannot continue private (D2) work.
         * Arabic on purpose: this reason is displayed verbatim in the UI.
         */
        const val FREE_MODEL_D2_REASON =
            "هذا البوت على موديل مجاني ولا يمكنه التعامل مع محتوى خاص (D2). بدّل الموديل إلى موديل مدفوع ثم أعد المحاولة."

        /** §24: the run stops; the user must check the target before retrying. */
        const val SEND_UNKNOWN_REASON = "SEND_UNKNOWN"
    }

    /** Derives the policy-facing target/recipients from raw arguments. */
    fun toToolCall(name: String, args: JsonObject): ToolCall {
        fun str(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull
        val recipients = mutableListOf<String>()
        // Defense in depth (§20): if a model hallucinates recipient fields the
        // policy engine — not the schema — is what catches them.
        for (field in listOf("to", "cc", "bcc", "recipient", "recipients")) {
            when (val v = args[field]) {
                is JsonPrimitive -> v.contentOrNull?.let { recipients += it }
                is JsonArray -> v.forEach { (it as? JsonPrimitive)?.contentOrNull?.let { r -> recipients += r } }
                else -> Unit
            }
        }
        val threadId = str("threadId")
        val uri = str("uri")
        val url = str("url")
        return when {
            threadId != null -> ToolCall(name, args, TargetKind.THREAD, threadId, recipients)
            uri != null -> ToolCall(name, args, TargetKind.FILE, uri, recipients)
            url != null -> ToolCall(name, args, TargetKind.URL, url, recipients)
            else -> ToolCall(name, args, TargetKind.NONE, null, recipients)
        }
    }

    private fun decisionName(d: PolicyDecision?): String = when (d) {
        is PolicyDecision.Allow -> "ALLOW"
        is PolicyDecision.Ask -> "ASK"
        is PolicyDecision.Deny -> "DENY"
        is PolicyDecision.Pause -> "PAUSE"
        is PolicyDecision.Stop -> "STOP"
        null -> "NONE"
    }

    private fun auditRecord(
        botId: String,
        taskId: String?,
        call: ToolCall,
        meta: ToolMeta?,
        decision: String,
        policyDecision: PolicyDecision?,
    ): AuditRecord = AuditRecord(
        botId = botId,
        taskId = taskId,
        tool = call.name,
        target = call.target,
        effect = meta?.effect?.name ?: "?",
        disclosure = meta?.disclosure?.name ?: "?",
        decision = decision,
        reasonCode = policyDecision?.reasonCode
            ?: com.botati.domain.policy.ReasonCode.NOT_ALLOWED,
        // BOT-13: a DIGEST, not the first 300 characters of the payload. The
        // old value wrote up to 300 characters of an email body into an
        // unencrypted Room table and named the column `argsDigest`.
        argsDigest = Sha256.hex(SecretRedactor.redact(call.arguments.toString())),
        timestampMs = nowMs(),
    )
}
