package com.botati.data.vault

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Non-secret app settings (defaults for ceilings, search endpoint shape). */
class SettingsStore(private val context: Context) {
    private val keySearchEndpoint = stringPreferencesKey("search_endpoint")
    private val keyMaxSteps = intPreferencesKey("default_max_steps")
    private val keyBudget = doublePreferencesKey("default_budget_usd")

    val searchEndpoint: Flow<String> = context.settingsDataStore.data
        .map { it[keySearchEndpoint] ?: DEFAULT_SEARCH_ENDPOINT }
    val defaultMaxSteps: Flow<Int> = context.settingsDataStore.data
        .map { it[keyMaxSteps] ?: 25 }
    val defaultBudgetUsd: Flow<Double> = context.settingsDataStore.data
        .map { it[keyBudget] ?: 0.30 }

    suspend fun setSearchEndpoint(url: String) =
        context.settingsDataStore.edit { it[keySearchEndpoint] = url }
    suspend fun setDefaultMaxSteps(v: Int) =
        context.settingsDataStore.edit { it[keyMaxSteps] = v }
    suspend fun setDefaultBudgetUsd(v: Double) =
        context.settingsDataStore.edit { it[keyBudget] = v }

    companion object {
        /** Tavily-compatible shape (§ task brief); endpoint is configurable. */
        const val DEFAULT_SEARCH_ENDPOINT = "https://api.tavily.com/search"
    }
}
