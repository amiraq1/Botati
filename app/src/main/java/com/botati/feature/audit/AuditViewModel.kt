package com.botati.feature.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.db.AuditEntryEntity
import com.botati.data.repo.BotRepository
import com.botati.data.vault.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModel(
    private val repo: BotRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    val filterBotId = MutableStateFlow<String?>(null)
    val entries: StateFlow<List<AuditEntryEntity>> = filterBotId
        .flatMapLatest { id -> if (id == null) repo.observeAudit() else repo.observeAuditForBot(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bots = repo.observeBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val defaultSteps = MutableStateFlow(25)
    val defaultBudget = MutableStateFlow("0.30")
    val exported = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            defaultSteps.value = settings.defaultMaxSteps.first()
            defaultBudget.value = settings.defaultBudgetUsd.first().toString()
        }
    }

    fun saveCeilings() {
        viewModelScope.launch {
            settings.setDefaultMaxSteps(defaultSteps.value.coerceIn(1, 200))
            defaultBudget.value.toDoubleOrNull()?.let { settings.setDefaultBudgetUsd(it.coerceAtLeast(0.0)) }
        }
    }

    fun exportBotsJson() {
        viewModelScope.launch {
            val list = repo.observeBots().first()
            val obj = buildJsonObject {
                list.forEach { b ->
                    put(b.id, buildJsonObject {
                        put("name", b.name)
                        put("icon", b.icon)
                        put("model", b.model)
                        put("systemPrompt", b.systemPrompt)
                        put("allowedTools", b.allowedToolsJson)
                        put("note", "secrets are NOT included (vault only)")
                    })
                }
            }
            exported.value = obj.toString()
        }
    }
}
