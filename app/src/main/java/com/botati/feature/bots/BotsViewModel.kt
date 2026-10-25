package com.botati.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.db.BotEntity
import com.botati.data.repo.BotRepository
import com.botati.data.repo.BotTemplates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class BotsViewModel(private val repo: BotRepository, private val mcpRepo: com.botati.data.repo.McpRepository) : ViewModel() {
    val bots: StateFlow<List<BotEntity>> = repo.observeBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun monthCost(botId: String): Flow<Double> = repo.monthCost(botId)

    fun createFromTemplate(index: Int) {
        val t = BotTemplates.all.getOrNull(index) ?: return
        viewModelScope.launch {
            repo.upsertBot(
                BotEntity(
                    id = UUID.randomUUID().toString(),
                    name = t.name, icon = t.icon, systemPrompt = t.prompt, model = t.model,
                    allowedToolsJson = com.botati.data.repo.encodeStringList(t.tools),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun createBlank() {
        viewModelScope.launch {
            repo.upsertBot(
                BotEntity(
                    id = UUID.randomUUID().toString(),
                    name = "بوت جديد", icon = "🤖", systemPrompt = "",
                    model = "google/gemma-4-31b-it:free",
                    allowedToolsJson = com.botati.data.repo.encodeStringList(listOf("web_search", "web_fetch")),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun delete(bot: BotEntity) = viewModelScope.launch { repo.deleteBot(bot) }
}
