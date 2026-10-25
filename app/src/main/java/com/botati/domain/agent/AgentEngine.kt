package com.botati.domain.agent

import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.Counters
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.PolicyEngine
import com.botati.domain.policy.Session
import com.botati.domain.policy.TargetKind
import com.botati.domain.policy.ToolCall
import com.botati.domain.policy.ToolMeta
import com.botati.domain.security.SecretRedactor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The agent loop (§5): build request (allowed tools only) → stream → assemble
 * tool calls from deltas → per call: PolicyEngine → approval if needed →
 * execute → wrapped/truncated result → repeat until a final answer, a
 * ceiling, a stop, or cancellation.
 *
 * Every policy decision is audited with its reasonCode (§28). Unknown tools
 * return a structured `tool_not_found` to the model and count as steps (§25);
 * three in a row stop the loop instead of spinning.
 */
class AgentEngine(
    private val llm: LlmPort,
    private val executor: ToolExecutorPort,
    private val approvals: ApprovalPort,
    private val audit: AuditPort,
    private val nowMs: () -> Long,
) {
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
        // §11.3: approvals granted mid-run are held here, not written into the
        // contract, so the approved hash keeps describing what the user saw.
        var activeSession = session

        // Context truncation before the first request (§27).
        val system = messages.filter { it.role == "system" }
        val history = messages.filter { it.role != "system" }.map { ChatTurn(it.role, it.content) }
        val trunc = ContextTruncation.truncate(history)
        if (trunc.wasTruncated) {
            emit(EngineEvent.ContextTruncated(trunc.droppedCount))
            val keptContents = trunc.turns.map { it.content }.toSet()
            messages.retainAll { it.role == "system" || it.content in keptContents || it.toolCalls.isNotEmpty() }
            if (messages.none { it.role == "system" }) messages.addAll(0, system)
        }

        val maxSteps = when (val s = activeSession) {
            is Session.Task -> s.contract.maxSteps
            is Session.Chat -> defaultMaxSteps
        }
        val toolSpecs = registry.values
            .filter { it.name in profile.allowedTools }
            .map { LlmToolSpec(it.name, it.description, it.inputSchemaJson) }

        var d2Contaminated = messages.any { it.role == "tool" && registry[it.name]?.disclosure == Disclosure.D2 }

        while (!finished) {
            currentCoroutineContext().ensureActive()
            val assembler = ToolCallDeltaAssembler()
            val text = StringBuilder()
            var streamError: String? = null
            var sawToolCalls = false

            llm.stream(LlmRequest(model, messages.toList(), toolSpecs, temperature)).collect { ev ->
                when (ev) {
                    is LlmEvent.TextDelta -> { text.append(ev.text); emit(EngineEvent.TextDelta(ev.text)) }
                    is LlmEvent.ToolDelta -> { sawToolCalls = true; assembler.feed(ev.delta) }
                    is LlmEvent.Usage -> {
                        val resolved = tracker.record(model, ev.usage)
                        emit(EngineEvent.UsageRecorded(model, ev.usage, resolved))
                    }
                    is LlmEvent.Error -> streamError = ev.message
                    is LlmEvent.Finished -> Unit
                }
            }
            if (streamError != null) { emit(EngineEvent.Failed(streamError!!)); return@flow }

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
                    val err = LlmMessage(
                        "assistant", text.toString(),
                    )
                    messages += err
                    emit(EngineEvent.AssistantCompleted(err))
                    messages += LlmMessage(
                        "user",
                        SystemPromptBuilder.wrapToolResult(
                            """{"error":"invalid_tool_arguments","detail":"${assembled.error}"}""",
                        ),
                    )
                    steps++
                    if (steps >= maxSteps) { emit(EngineEvent.Stopped("step ceiling")); finished = true }
                }
                is AssemblyResult.Complete -> {
                    val assistantMsg = LlmMessage("assistant", text.toString(), toolCalls = assembled.calls)
                    messages += assistantMsg
                    emit(EngineEvent.AssistantCompleted(assistantMsg))

                    for (assembledCall in assembled.calls) {
                        currentCoroutineContext().ensureActive()
                        steps++
                        val call = toToolCall(assembledCall.name, assembledCall.arguments)
                        val meta = registry[assembledCall.name]

                        if (meta == null) {
                            // §25: unknown tool — structured error, counts as a step.
                            consecutiveUnknown++
                            audit.record(auditRecord(botId, taskId, call, null, "tool_not_found", null))
                            val result = """{"error":"tool_not_found","tool":"${assembledCall.name}","hint":"use only the tools provided"}"""
                            messages += toolMessage(assembledCall, result)
                            emit(EngineEvent.ToolFinished(call, result, true))
                            if (consecutiveUnknown >= 3) {
                                emit(EngineEvent.Stopped("three consecutive unknown tools"))
                                finished = true
                                break
                            }
                            if (steps >= maxSteps) { emit(EngineEvent.Stopped("step ceiling")); finished = true; break }
                            continue
                        }
                        consecutiveUnknown = 0

                        val counters = Counters(
                            stepsUsed = steps - 1,
                            costUsedUsd = tracker.totalKnownCostUsd,
                            d2Contaminated = d2Contaminated,
                        )
                        val decision = PolicyEngine.decide(profile, call, meta, activeSession, counters, nowMs())
                        audit.record(auditRecord(botId, taskId, call, meta, decisionName(decision), decision))

                        when (decision) {
                            is PolicyDecision.Deny -> {
                                val result = """{"error":"policy_denied","reason":"${decision.reasonCode}"}"""
                                messages += toolMessage(assembledCall, result)
                                emit(EngineEvent.ToolFinished(call, result, true))
                            }
                            is PolicyDecision.Stop -> {
                                emit(EngineEvent.Stopped("ceiling: ${decision.reasonCode}"))
                                finished = true
                                break
                            }
                            is PolicyDecision.Ask, is PolicyDecision.Pause -> {
                                if (decision is PolicyDecision.Pause) emit(EngineEvent.Paused(call, decision))
                                val approved = approvals.requestApproval(call, decision)
                                if (!approved) {
                                    val result = """{"error":"user_denied"}"""
                                    messages += toolMessage(assembledCall, result)
                                    emit(EngineEvent.ToolFinished(call, result, true))
                                } else {
                                    // §11.3: the tap authorizes this action alone, for this
                                    // task run. Without recording it the identical next call
                                    // would Pause again and the approval would mean nothing.
                                    if (decision is PolicyDecision.Pause) {
                                        val s = activeSession
                                        if (s is Session.Task) {
                                            activeSession = s.withGrant(call.name, call.target)
                                        }
                                    }
                                    executeAndAppend(call, assembledCall, messages, emitEvent)
                                    if (meta.disclosure == Disclosure.D2) d2Contaminated = true
                                }
                            }
                            is PolicyDecision.Allow -> {
                                executeAndAppend(call, assembledCall, messages, emitEvent)
                                if (meta.disclosure == Disclosure.D2) d2Contaminated = true
                            }
                        }
                        if (steps >= maxSteps && !finished) {
                            emit(EngineEvent.Stopped("step ceiling"))
                            finished = true
                            break
                        }
                    }

                    if (activeSession is Session.Task && tracker.shouldStopForUnknownCost()) {
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
        assembledCall: AssembledToolCall,
        messages: MutableList<LlmMessage>,
        emit: suspend (EngineEvent) -> Unit,
    ) {
        emit(EngineEvent.ToolStarted(call))
        val raw = try {
            executor.execute(call)
        } catch (e: Exception) {
            """{"error":"tool_failed","detail":"${e.message?.replace("\"", "'") ?: "unknown"}"}"""
        }
        val truncated = ContextTruncation.truncateToolResult(raw)
        messages += toolMessage(assembledCall, truncated)
        emit(EngineEvent.ToolFinished(call, truncated, truncated.startsWith("{\"error\"")))
    }

    private fun toolMessage(call: AssembledToolCall, rawResult: String): LlmMessage =
        LlmMessage(
            role = "tool",
            content = SystemPromptBuilder.wrapToolResult(rawResult),
            toolCallId = call.id,
            name = call.name,
        )

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
        argsDigest = SecretRedactor.redact(call.arguments.toString()).take(300),
        timestampMs = nowMs(),
    )
}
