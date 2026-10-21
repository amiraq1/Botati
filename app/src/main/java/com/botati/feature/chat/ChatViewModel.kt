package com.botati.feature.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.db.AuditEntryEntity
import com.botati.data.db.BotEntity
import com.botati.data.db.MessageEntity
import com.botati.data.db.TaskSessionEntity
import com.botati.data.db.UsageRecordEntity
import com.botati.data.repo.BotRepository
import com.botati.data.repo.McpRepository
import com.botati.data.repo.decodeStringList
import com.botati.data.vault.SettingsStore
import com.botati.data.vault.Vault
import com.botati.domain.agent.AgentEngine
import com.botati.domain.agent.ApprovalPort
import com.botati.domain.agent.AssembledToolCall
import com.botati.domain.agent.AuditPort
import com.botati.domain.agent.AuditRecord
import com.botati.domain.agent.EngineEvent
import com.botati.domain.agent.LlmMessage
import com.botati.domain.agent.SystemPromptBuilder
import com.botati.domain.agent.TaskStateTransitions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.botati.domain.policy.BotPolicyProfile
import com.botati.domain.policy.PolicyDecision
import com.botati.domain.policy.ReasonCode
import com.botati.domain.policy.ScopeContract
import com.botati.domain.policy.Session
import com.botati.domain.policy.ToolCall
import com.botati.domain.policy.ToolMeta
import com.botati.domain.tools.ToolRegistry
import com.botati.infrastructure.mcp.McpClientPool
import com.botati.infrastructure.openrouter.ContractProposer
import com.botati.infrastructure.openrouter.OpenRouterClient
import com.botati.infrastructure.tools.FileTools
import com.botati.infrastructure.tools.ToolRouter
import com.botati.infrastructure.tools.WebTools
import com.botati.service.TaskForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class PendingApproval(
    val call: ToolCall,
    val decisionLabel: String,
    val fullReview: Boolean,
    val argsText: String,
    val completer: CompletableDeferred<Boolean>,
)

data class PendingContract(
    val taskId: String,
    val instruction: String,
    val contract: ScopeContract,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val botId: String,
    conversationId: String?,
    private val app: Application,
    private val bots: BotRepository,
    private val mcp: McpRepository,
    private val vault: Vault,
    private val llm: OpenRouterClient,
    private val pool: McpClientPool,
    private val fileTools: FileTools,
    private val webTools: WebTools,
    private val settings: SettingsStore,
    private val proposer: ContractProposer,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val auditScope = CoroutineScope(Dispatchers.IO)

    val bot: StateFlow<BotEntity?> = bots.observeBot(botId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val conversationIdFlow = MutableStateFlow<String?>(conversationId)
    val conversationId: StateFlow<String?> = conversationIdFlow

    val messages: StateFlow<List<MessageEntity>> = conversationIdFlow
        .flatMapLatest { cid -> if (cid == null) flowOf(emptyList()) else bots.observeMessages(cid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streamingText = MutableStateFlow("")
    val running = MutableStateFlow(false)
    val statusLine = MutableStateFlow<String?>(null)
    val pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingContract = MutableStateFlow<PendingContract?>(null)
    val activeTask = MutableStateFlow<TaskSessionEntity?>(null)

    private var runJob: Job? = null
    private val toolsUsed = mutableListOf<String>()
    private var runCost = 0.0
    private var runSteps = 0

    init {
        viewModelScope.launch {
            if (conversationIdFlow.value == null) {
                // Take the most recent conversation or create one.
                val conv = bots.conversationsList(botId).firstOrNull()
                    ?: bots.newConversation(botId, "محادثة")
                conversationIdFlow.value = conv.id
            }
        }
    }

    // ---- approvals ---------------------------------------------------------

    // F-17: session-only disclosure consent remembered in memory, not permanently in DB
    private val sessionApprovedDisclosures = mutableSetOf<String>()

    private val approvalPort = object : ApprovalPort {
        override suspend fun requestApproval(call: ToolCall, decision: PolicyDecision): Boolean {
            val completer = CompletableDeferred<Boolean>()
            val (label, full) = when (decision) {
                is PolicyDecision.Ask -> "موافقة (دردشة): ${decision.reasonCode}" to decision.requiresFullReview
                is PolicyDecision.Pause -> "خارج نطاق العقد: ${decision.reasonCode}" to true
                else -> decision.toString() to false
            }
            pendingApproval.value = PendingApproval(
                call = call, decisionLabel = label, fullReview = full,
                argsText = call.arguments.toString(), completer = completer,
            )
            val answer = completer.await()
            pendingApproval.value = null
            if (answer && decision is PolicyDecision.Ask &&
                decision.reasonCode == ReasonCode.DISCLOSURE_ASK
            ) {
                // F-17: In-memory session consent only
                sessionApprovedDisclosures.add("disclose:" + (call.target ?: call.name))
            }
            return answer
        }
    }

    fun answerApproval(approved: Boolean) {
        pendingApproval.value?.completer?.complete(approved)
    }

    private val auditPort = object : AuditPort {
        override fun record(entry: AuditRecord) {
            auditScope.launch {
                bots.addAudit(
                    AuditEntryEntity(
                        taskId = entry.taskId, botId = entry.botId, tool = entry.tool,
                        target = entry.target, effect = entry.effect, disclosure = entry.disclosure,
                        decision = entry.decision, reasonCode = entry.reasonCode.name,
                        argsDigest = entry.argsDigest, resultSummary = entry.resultSummary,
                        idempotencyKey = entry.idempotencyKey, timestamp = entry.timestampMs,
                    ),
                )
            }
        }
    }

    // ---- sending -----------------------------------------------------------

    fun send(text: String) {
        if (text.isBlank() || running.value) return
        val b = bot.value ?: return
        val cid = conversationIdFlow.value ?: return
        viewModelScope.launch {
            bots.addMessage(MessageEntity(conversationId = cid, role = "user", content = text, createdAt = now()))
            runEngine(b, cid, session = Session.Chat, taskInstruction = null)
        }
    }

    fun stop() {
        runJob?.cancel()
        running.value = false
        val task = activeTask.value
        if (task != null) {
            viewModelScope.launch {
                val updated = task.copy(
                    status = "CANCELLED",
                    finalReport = buildReport("أوقفها المستخدم."),
                )
                bots.upsertTask(updated)
                activeTask.value = updated
                TaskForegroundService.stop(app)
            }
        }
    }

    private fun runEngine(b: BotEntity, cid: String, session: Session, taskInstruction: String?) {
        running.value = true
        streamingText.value = ""
        statusLine.value = null
        toolsUsed.clear(); runCost = 0.0; runSteps = 0
        val taskId = (session as? Session.Task)?.contract?.taskId

        runJob = viewModelScope.launch {
            try {
                val registry = buildRegistry(b)
                val profile = BotPolicyProfile(
                    botId = b.id,
                    allowedTools = decodeStringList(b.allowedToolsJson).toSet(),
                    preApprovedDisclosures = sessionApprovedDisclosures.toSet(),
                )
                val router = ToolRouter(
                    botId = b.id,
                    taskIdProvider = { activeTask.value?.id },
                    grantsProvider = {
                        bots.grantUris(b.id) + decodeStringList(b.safTreeUrisJson)
                    },
                    serverByPrefix = { prefix -> mcp.serverByPrefix(prefix) },
                    fileTools = fileTools, webTools = webTools, mcpPool = pool,
                )
                val contract = (session as? Session.Task)?.contract
                val history = bots.messages(cid)
                val llmMessages = mutableListOf<LlmMessage>()
                llmMessages += LlmMessage(
                    "system",
                    SystemPromptBuilder.build(b.name, b.systemPrompt, contract, taskInstruction),
                )
                // Pair persisted tool results with the ids of the preceding
                // assistant tool calls positionally (wire format requires it).
                val pendingIds = ArrayDeque<String?>()
                history.forEach { m ->
                    val lm = m.toLlm()
                    if (lm.role == "assistant" && lm.toolCalls.isNotEmpty()) {
                        pendingIds.clear()
                        lm.toolCalls.forEach { pendingIds.addLast(it.id) }
                        llmMessages += lm
                    } else if (lm.role == "tool") {
                        val id = if (pendingIds.isEmpty()) null else pendingIds.removeFirst()
                        val wrapped = SystemPromptBuilder.ensureWrappedToolResult(lm.content)
                        llmMessages += lm.copy(content = wrapped, toolCallId = id)
                    } else {
                        llmMessages += lm
                    }
                }

                val defaultSteps = b.maxStepsOverride ?: settings.defaultMaxStepsValue()
                val engine = AgentEngine(llm, router, approvalPort, auditPort) { System.currentTimeMillis() }
                engine.run(
                    botId = b.id, model = b.model, temperature = b.temperature,
                    profile = profile, registry = registry,
                    initialMessages = llmMessages, session = session,
                    taskId = taskId, defaultMaxSteps = defaultSteps,
                ).collect { ev -> onEngineEvent(cid, ev, taskId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    statusLine.value = "توقّف التنفيذ."
                } else {
                    statusLine.value = "خطأ: ${e.message}"
                }
            } finally {
                // F-04: NonCancellable cleanup ensures status updates and FGS stop execute
                withContext(NonCancellable) {
                    running.value = false
                    streamingText.value = ""
                    finishTaskIfNeeded(wasCancelled = runJob?.isCancelled == true)
                }
            }
        }
    }

    private suspend fun onEngineEvent(cid: String, ev: EngineEvent, taskId: String?) {
        when (ev) {
            is EngineEvent.TextDelta -> streamingText.value += ev.text
            is EngineEvent.AssistantCompleted -> {
                val m = ev.message
                bots.addMessage(
                    MessageEntity(
                        conversationId = cid, role = "assistant", content = m.content,
                        toolCallsJson = if (m.toolCalls.isEmpty()) null else encodeToolCalls(m.toolCalls),
                        createdAt = now(),
                    ),
                )
                streamingText.value = ""
            }
            is EngineEvent.ToolStarted -> { toolsUsed += ev.call.name; runSteps++ }
            is EngineEvent.ToolFinished -> {
                // F-14: Save tool result wrapped in <tool_result>
                bots.addMessage(
                    MessageEntity(
                        conversationId = cid, role = "tool",
                        content = SystemPromptBuilder.ensureWrappedToolResult(ev.result),
                        toolName = ev.call.name, createdAt = now(),
                    ),
                )
            }
            is EngineEvent.UsageRecorded -> {
                runCost += ev.resolved.costUsd ?: 0.0
                bots.addUsage(
                    UsageRecordEntity(
                        botId = botId, taskId = taskId, model = ev.model,
                        promptTokens = ev.usage.promptTokens,
                        completionTokens = ev.usage.completionTokens,
                        costUsd = ev.resolved.costUsd,
                        costSource = ev.resolved.source.name.lowercase(),
                        timestamp = now(),
                    ),
                )
            }
            is EngineEvent.ContextTruncated ->
                statusLine.value = "تم قصّ السياق القديم (${ev.droppedCount} دور) — §27."
            is EngineEvent.Paused ->
                statusLine.value = "توقّف مؤقت: ${ev.decision.reasonCode} — بانتظار قرارك."
            is EngineEvent.Stopped -> {
                statusLine.value = "توقّفت الحلقة: ${ev.reason}"
                activeTask.value?.let { t ->
                    val updated = t.copy(status = "STOPPED_LIMIT", stepsUsed = runSteps, costUsed = runCost)
                    bots.upsertTask(updated); activeTask.value = updated
                }
            }
            is EngineEvent.Failed -> {
                // F-18: Failed writes FAILED and saves error report
                statusLine.value = "فشل: ${ev.message}"
                activeTask.value?.let { t ->
                    val updated = t.copy(
                        status = "FAILED",
                        stepsUsed = runSteps,
                        costUsed = runCost,
                        finalReport = buildReport("فشلت المهمة: ${ev.message}"),
                    )
                    bots.upsertTask(updated)
                    activeTask.value = updated
                }
            }
            is EngineEvent.Done -> Unit
        }
    }

    private suspend fun finishTaskIfNeeded(wasCancelled: Boolean) {
        val task = activeTask.value ?: return
        val finalStatus = TaskStateTransitions.resolveTerminalStatus(
            currentStatus = task.status,
            wasCancelled = wasCancelled,
        )
        // F-18 & F-04: Only update if RUNNING or if terminal status needs to be applied
        if (task.status == "RUNNING" || (wasCancelled && task.status != "STOPPED" && task.status !in TaskStateTransitions.TERMINAL_STATES)) {
            val reportReason = when (finalStatus) {
                "FAILED" -> "فشلت المهمة."
                "STOPPED" -> "توقّفت المهمة (إلغاء)."
                "STOPPED_LIMIT" -> "توقفت عند الحد الأقصى."
                else -> "اكتملت."
            }
            val updated = task.copy(
                status = finalStatus,
                stepsUsed = runSteps,
                costUsed = runCost,
                finalReport = buildReport(reportReason),
            )
            bots.upsertTask(updated)
            activeTask.value = updated
        }
        if (activeTask.value?.status in TaskStateTransitions.TERMINAL_STATES) {
            TaskForegroundService.stop(app)
        }
    }

    private fun buildReport(reason: String): String = buildString {
        appendLine("تقرير المهمة — $reason")
        appendLine("الأدوات المستخدمة: ${toolsUsed.groupingBy { it }.eachCount()}")
        appendLine("الخطوات: $runSteps")
        appendLine("الصرف المعروف: $${"%.4f".format(runCost)}")
    }

    private suspend fun buildRegistry(b: BotEntity): Map<String, ToolMeta> {
        val map = mutableMapOf<String, ToolMeta>()
        ToolRegistry.builtins.forEach { map[it.name] = it }
        val serverIds = decodeStringList(b.mcpServerIdsJson)
        map.putAll(mcp.registryForServers(serverIds))
        return map
    }

    // ---- tasks ---------------------------------------------------------------

    fun proposeTask(instruction: String) {
        val b = bot.value ?: return
        if (instruction.isBlank() || running.value) return
        statusLine.value = "جارٍ اقتراح عقد النطاق…"
        viewModelScope.launch {
            val registry = buildRegistry(b)
            val taskId = UUID.randomUUID().toString()
            val result = proposer.propose(instruction, b.model, taskId, registry.keys.toList())
            result.fold(
                onSuccess = { contract ->
                    val entity = TaskSessionEntity(
                        id = taskId, botId = b.id,
                        conversationId = conversationIdFlow.value ?: "",
                        instruction = instruction,
                        contractJson = json.encodeToString(contract),
                        contractHash = contract.computeHash(),
                        status = "PROPOSED", lastHeartbeatAt = now(), createdAt = now(),
                    )
                    bots.upsertTask(entity)
                    activeTask.value = entity
                    pendingContract.value = PendingContract(taskId, instruction, contract)
                    statusLine.value = null
                },
                onFailure = { e -> statusLine.value = "فشل اقتراح العقد: ${e.message}" },
            )
        }
    }

    fun approveContract() {
        val pending = pendingContract.value ?: return
        val b = bot.value ?: return
        val cid = conversationIdFlow.value ?: return
        pendingContract.value = null
        viewModelScope.launch {
            val entity = bots.task(pending.taskId)?.copy(
                status = "RUNNING", lastHeartbeatAt = now(),
            ) ?: return@launch
            bots.upsertTask(entity)
            activeTask.value = entity
            // The approval tap IS the authorization (§11.1) — recorded with hash.
            bots.grantApproval(b.id, "contract", contractHash = pending.contract.computeHash(), note = pending.taskId)
            TaskForegroundService.start(app, pending.taskId)
            bots.addMessage(
                MessageEntity(
                    conversationId = cid, role = "user",
                    content = "🎯 مهمة: ${pending.instruction}", createdAt = now(),
                ),
            )
            runEngine(b, cid, Session.Task(pending.contract), pending.instruction)
        }
    }

    fun rejectContract() {
        val pending = pendingContract.value ?: return
        pendingContract.value = null
        viewModelScope.launch {
            bots.task(pending.taskId)?.let {
                bots.upsertTask(it.copy(status = "CANCELLED", finalReport = "رفض المستخدم العقد."))
            }
            activeTask.value = null
        }
    }

    // ---- files ---------------------------------------------------------------

    fun attachTree(uri: Uri) {
        viewModelScope.launch {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) { /* permission may already be held */ }
            bots.addGrant(botId, uri.toString())
            statusLine.value = "تم منح مجلد للبوت: $uri"
        }
    }

    fun attachFile(uri: Uri) {
        val cid = conversationIdFlow.value ?: return
        viewModelScope.launch {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) { }
            bots.addGrant(botId, uri.toString())
            bots.addMessage(
                MessageEntity(
                    conversationId = cid, role = "user",
                    content = "📎 ملف متاح لك للقراءة عبر read_file: $uri",
                    createdAt = now(),
                ),
            )
        }
    }

    // ---- mapping ---------------------------------------------------------------

    private fun MessageEntity.toLlm(): LlmMessage {
        val finalContent = if (role == "tool") {
            SystemPromptBuilder.ensureWrappedToolResult(content)
        } else {
            content
        }
        return LlmMessage(
            role = role,
            content = finalContent,
            toolCalls = toolCallsJson?.let { raw ->
                try {
                    json.decodeFromString<List<AssembledToolCall>>(raw)
                } catch (e: Exception) { emptyList() }
            } ?: emptyList(),
            toolCallId = toolCallId,
            name = toolName,
        )
    }

    private fun encodeToolCalls(calls: List<AssembledToolCall>): String =
        json.encodeToString(calls)

    private fun now() = System.currentTimeMillis()
}

private suspend fun SettingsStore.defaultMaxStepsValue(): Int =
    with(this) { defaultMaxSteps.first() }
