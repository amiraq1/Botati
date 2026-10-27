package com.botati.di

import androidx.room.Room
import com.botati.data.db.BotatiDatabase
import com.botati.data.repo.BotRepository
import com.botati.data.repo.McpRepository
import com.botati.data.vault.SettingsStore
import com.botati.data.vault.Vault
import com.botati.feature.audit.AuditViewModel
import com.botati.feature.bots.BotEditViewModel
import com.botati.feature.bots.BotsViewModel
import com.botati.feature.chat.ChatViewModel
import com.botati.feature.mcp.McpViewModel
import com.botati.feature.vault.VaultViewModel
import com.botati.infrastructure.mcp.McpClientPool
import com.botati.infrastructure.openrouter.ContractProposer
import com.botati.infrastructure.openrouter.OpenRouterClient
import com.botati.infrastructure.openrouter.createHttpClient
import com.botati.infrastructure.tools.FileTools
import com.botati.infrastructure.tools.WebTools
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidApplication(), BotatiDatabase::class.java, "botati.db").build()
    }
    single { Vault(androidApplication()) }
    single { SettingsStore(androidApplication()) }
    single { createHttpClient() }
    single {
        OpenRouterClient(get()).apply {
            apiKeyProvider = { get<Vault>().getSecret(Vault.providerSlot("openrouter")) }
            alpmeApiKeyProvider = { get<Vault>().getSecret(Vault.providerSlot("alpme")) }
        }
    }
    single { ContractProposer(get()) }
    single { McpClientPool(get(), get()) }
    single { FileTools(androidApplication()) }
    single { WebTools(get(), get(), get()) }
    single { BotRepository(get()) }
    single { McpRepository(get()) }

    viewModel { BotsViewModel(get(), get()) }
    viewModel { (botId: String?) -> BotEditViewModel(botId, get(), get()) }
    viewModel { (botId: String, conversationId: String?) ->
        ChatViewModel(
            botId = botId, conversationId = conversationId,
            app = androidApplication(),
            bots = get(), mcp = get(), vault = get(), llm = get(),
            pool = get(), fileTools = get(), webTools = get(),
            settings = get(), proposer = get(),
        )
    }
    viewModel { McpViewModel(get(), get(), get()) }
    viewModel { VaultViewModel(get(), get(), get()) }
    viewModel { AuditViewModel(get(), get()) }
}
