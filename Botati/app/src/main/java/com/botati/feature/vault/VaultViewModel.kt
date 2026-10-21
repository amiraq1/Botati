package com.botati.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.botati.data.vault.SettingsStore
import com.botati.data.vault.Vault
import com.botati.domain.security.SecretRedactor
import com.botati.infrastructure.openrouter.OpenRouterClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VaultViewModel(
    private val vault: Vault,
    private val settings: SettingsStore,
    private val llm: OpenRouterClient,
) : ViewModel() {

    val openRouterMasked = MutableStateFlow<String?>(null)
    val searchMasked = MutableStateFlow<String?>(null)
    val searchEndpoint = MutableStateFlow("")
    val status = MutableStateFlow<String?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            openRouterMasked.value = vault.getSecret(Vault.providerSlot("openrouter"))?.let { SecretRedactor.maskForDisplay(it) }
            searchMasked.value = vault.getSecret(Vault.SEARCH_KEY_SLOT)?.let { SecretRedactor.maskForDisplay(it) }
            searchEndpoint.value = settings.searchEndpoint.first()
        }
    }

    fun saveOpenRouterKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            vault.putSecret(Vault.providerSlot("openrouter"), key.trim())
            refresh()
            status.value = "حُفظ مفتاح OpenRouter بالخزنة."
        }
    }

    fun testOpenRouterKey() {
        viewModelScope.launch {
            status.value = "جارٍ الاختبار…"
            val key = vault.getSecret(Vault.providerSlot("openrouter"))
            val ok = key != null && runCatching { llm.testKey(key) }.getOrElse { false }
            status.value = if (ok) "المفتاح صالح ✓ (GET /models نجح)." else "فشل الاختبار — تحقق من المفتاح أو الشبكة."
        }
    }

    fun saveSearch(key: String, endpoint: String) {
        viewModelScope.launch {
            if (key.isNotBlank()) vault.putSecret(Vault.SEARCH_KEY_SLOT, key.trim())
            if (endpoint.isNotBlank()) settings.setSearchEndpoint(endpoint.trim())
            refresh()
            status.value = "حُفظت إعدادات البحث."
        }
    }
}
