package com.botati.feature.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.db.McpServerEntity
import com.botati.data.db.McpToolEntity
import com.botati.data.repo.McpRepository
import com.botati.data.vault.Vault
import com.botati.infrastructure.mcp.McpClientPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class McpViewModel(
    private val repo: McpRepository,
    private val vault: Vault,
    private val pool: McpClientPool,
) : ViewModel() {

    val servers: StateFlow<List<McpServerEntity>> = repo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tools: StateFlow<List<McpToolEntity>> = repo.observeAllTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun addServer(name: String, url: String, token: String) {
        if (name.isBlank() || url.isBlank()) {
            status.value = "الاسم والرابط مطلوبان."
            return
        }
        viewModelScope.launch {
            busy.value = true
            val server = repo.addServer(name.trim(), url.trim())
            if (token.isNotBlank()) vault.putSecret(Vault.mcpSlot(server.id), token)
            status.value = "أُضيف الخادم ${server.name} (وسم ${server.prefix}). جرّب «اختبار»."
            busy.value = false
        }
    }

    fun testAndSync(server: McpServerEntity) {
        viewModelScope.launch {
            busy.value = true
            status.value = "جارٍ الاتصال بـ ${server.name}…"
            try {
                val client = pool.clientFor(server)
                val info = client.connect()
                val remote = client.listTools()
                repo.saveSyncedTools(server, remote)
                status.value = "نجح الاتصال: ${remote.size} أداة (era=${info.era})."
            } catch (e: Exception) {
                repo.setServerStatus(server, "ERROR: ${e.message?.take(60)}")
                status.value = "فشل الاتصال: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun delete(server: McpServerEntity) {
        viewModelScope.launch {
            repo.deleteServer(server)
            vault.removeSecret(Vault.mcpSlot(server.id))
            pool.evict(server.id)
            status.value = "حُذف الخادم ${server.name} وسرّه."
        }
    }

    fun setClassification(tool: McpToolEntity, effect: String, disclosure: String) {
        viewModelScope.launch {
            repo.updateToolClassification(
                tool,
                com.botati.domain.policy.Effect.valueOf(effect),
                com.botati.domain.policy.Disclosure.valueOf(disclosure),
            )
        }
    }
}
