package com.botati.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.db.BotEntity
import com.botati.data.db.McpServerEntity
import com.botati.data.db.McpToolEntity
import com.botati.data.repo.BotRepository
import com.botati.data.repo.McpRepository
import com.botati.data.repo.decodeStringList
import com.botati.data.repo.encodeStringList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class BotEditState(
    val id: String? = null,
    val name: String = "",
    val icon: String = "🤖",
    val systemPrompt: String = "",
    val provider: String = "openrouter",
    val model: String = "google/gemma-4-31b-it:free",
    val temperature: Float = 0.7f,
    val autonomyMode: String = "TASK_FREEDOM",
    val allowedTools: Set<String> = emptySet(),
    val mcpServerIds: Set<String> = emptySet(),
    val maxStepsOverride: String = "",
    val budgetOverride: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val loaded: Boolean = false,
)

class BotEditViewModel(
    private val botId: String?,
    private val repo: BotRepository,
    private val mcpRepo: McpRepository,
) : ViewModel() {
    val state = MutableStateFlow(BotEditState(id = botId))
    val servers: StateFlow<List<McpServerEntity>> = mcpRepo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allTools: StateFlow<List<McpToolEntity>> = mcpRepo.observeAllTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (botId != null) {
            viewModelScope.launch {
                repo.bot(botId)?.let { b ->
                    state.value = BotEditState(
                        id = b.id, name = b.name, icon = b.icon, systemPrompt = b.systemPrompt,
                        provider = b.provider, model = b.model, temperature = b.temperature.toFloat(),
                        autonomyMode = b.autonomyMode,
                        allowedTools = decodeStringList(b.allowedToolsJson).toSet(),
                        mcpServerIds = decodeStringList(b.mcpServerIdsJson).toSet(),
                        maxStepsOverride = b.maxStepsOverride?.toString() ?: "",
                        budgetOverride = b.budgetOverride?.toString() ?: "",
                        createdAt = b.createdAt, loaded = true,
                    )
                }
            }
        } else {
            state.value = state.value.copy(
                allowedTools = setOf("web_search", "web_fetch"), loaded = true,
            )
        }
    }

    fun update(transform: (BotEditState) -> BotEditState) {
        state.value = transform(state.value)
    }

    fun toggleTool(name: String) = update { s ->
        s.copy(allowedTools = if (name in s.allowedTools) s.allowedTools - name else s.allowedTools + name)
    }

    fun toggleServer(id: String) = update { s ->
        val nowLinked = id !in s.mcpServerIds
        val toolNames = allTools.value.filter { it.serverId == id }.map { it.prefixedName }
        s.copy(
            mcpServerIds = if (nowLinked) s.mcpServerIds + id else s.mcpServerIds - id,
            // Unlinking a server removes its tools from the allow-list too.
            allowedTools = if (nowLinked) s.allowedTools else s.allowedTools - toolNames.toSet(),
        )
    }

    fun save(onDone: () -> Unit) {
        val s = state.value
        viewModelScope.launch {
            val existing = s.id?.let { repo.bot(it) }
            repo.upsertBot(
                BotEntity(
                    id = s.id ?: UUID.randomUUID().toString(),
                    name = s.name.ifBlank { "بوت" }, icon = s.icon.ifBlank { "🤖" },
                    systemPrompt = s.systemPrompt, provider = s.provider, model = s.model,
                    temperature = s.temperature.toDouble(), autonomyMode = s.autonomyMode,
                    allowedToolsJson = encodeStringList(s.allowedTools.toList()),
                    mcpServerIdsJson = encodeStringList(s.mcpServerIds.toList()),
                    safTreeUrisJson = existing?.safTreeUrisJson ?: "[]",
                    maxStepsOverride = s.maxStepsOverride.toIntOrNull(),
                    budgetOverride = s.budgetOverride.toDoubleOrNull(),
                    createdAt = existing?.createdAt ?: s.createdAt,
                ),
            )
            onDone()
        }
    }
}
