package com.botati.data.vault

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Base64

private val Context.vaultDataStore by preferencesDataStore(name = "vault")

/**
 * The Vault (spec §3/§12): secrets are encrypted with Tink AEAD (AES-256-GCM);
 * the keyset is itself wrapped by a master key in the Android Keystore, and
 * only Base64 ciphertext lands in the "vault" DataStore. Both artifacts — the
 * ciphertext file and the keyset SharedPreferences — are excluded from backup
 * and from device-to-device transfer by res/xml/data_extraction_rules.xml
 * (API 31+) and res/xml/backup_rules.xml (API <= 30).
 *
 * The biometric prompt on the vault screen is a UI gate only; it is NOT what
 * protects these keys (that distinction is deliberate, §0/§3) — the Keystore
 * master key here is not bound with setUserAuthenticationRequired.
 *
 * Slots: "provider:<provider>", "mcp:<serverId>", "search:apiKey".
 */
class Vault(private val context: Context) {

    private val aead: Aead by lazy {
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "botati_vault_keyset", "botati_vault_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://botati_vault_master")
            .build()
            .keysetHandle
        handle.getPrimitive(Aead::class.java)
    }

    // Every entry point hops to IO: first access builds the
    // AndroidKeysetManager (SharedPreferences read + Keystore unwrap) and
    // every call performs DataStore file I/O — blocking work that must never
    // run on a caller's dispatcher (the vault screen's viewModelScope calls
    // these from Main.immediate).
    suspend fun putSecret(slot: String, value: String) {
        withContext(Dispatchers.IO) {
            val cipher = aead.encrypt(value.toByteArray(Charsets.UTF_8), slot.toByteArray(Charsets.UTF_8))
            val encoded = Base64.getEncoder().encodeToString(cipher)
            context.vaultDataStore.edit { it[stringPreferencesKey(slot)] = encoded }
        }
    }

    suspend fun getSecret(slot: String): String? = withContext(Dispatchers.IO) {
        val encoded = context.vaultDataStore.data.first()[stringPreferencesKey(slot)]
        if (encoded == null) {
            null
        } else {
            try {
                val plain = aead.decrypt(Base64.getDecoder().decode(encoded), slot.toByteArray(Charsets.UTF_8))
                String(plain, Charsets.UTF_8)
            } catch (e: Exception) {
                null // corrupted/rotated keyset: behave as "no secret", never crash
            }
        }
    }

    suspend fun removeSecret(slot: String) {
        withContext(Dispatchers.IO) {
            context.vaultDataStore.edit { it.remove(stringPreferencesKey(slot)) }
        }
    }

    fun observeHas(slot: String): Flow<Boolean> =
        context.vaultDataStore.data.map { it.contains(stringPreferencesKey(slot)) }

    companion object {
        fun providerSlot(provider: String) = "provider:$provider"
        fun mcpSlot(serverId: String) = "mcp:$serverId"
        const val SEARCH_KEY_SLOT = "search:apiKey"
    }
}
