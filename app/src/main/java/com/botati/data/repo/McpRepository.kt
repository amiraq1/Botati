package com.botati.data.repo

import com.botati.data.db.BotatiDatabase
import com.botati.data.db.McpServerEntity
import com.botati.data.db.McpToolEntity
import com.botati.domain.policy.Disclosure
import com.botati.domain.policy.Effect
import com.botati.domain.policy.ToolMeta
import com.botati.domain.policy.ToolSource
import com.botati.domain.tools.ToolRegistry
import com.botati.infrastructure.mcp.McpRemoteTool
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class McpRepository(private val db: BotatiDatabase) {
    fun observeServers(): Flow<List<McpServerEntity>> = db.mcpDao().observeServers()
    fun observeAllTools(): Flow<List<McpToolEntity>> = db.mcpDao().observeAllTools()
    fun observeTools(serverId: String): Flow<List<McpToolEntity>> = db.mcpDao().observeTools(serverId)
    suspend fun serverById(id: String): McpServerEntity? = db.mcpDao().serverById(id)
    suspend fun serverByPrefix(prefix: String): McpServerEntity? = db.mcpDao().serverByPrefix(prefix)
    suspend fun allServers(): List<McpServerEntity> = db.mcpDao().allServers()

    suspend fun addServer(name: String, url: String): McpServerEntity {
        val taken = db.mcpDao().allServers().map { it.prefix }.toSet()
        val server = McpServerEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            prefix = ToolRegistry.sanitizePrefix(name, taken),
            url = url.trim(),
        )
        db.mcpDao().upsertServer(server)
        return server
    }

    suspend fun deleteServer(server: McpServerEntity) {
        db.mcpDao().deleteToolsForServer(server.id)
        db.mcpDao().deleteServer(server.id)
    }

    suspend fun setServerStatus(server: McpServerEntity, status: String) {
        db.mcpDao().upsertServer(server.copy(status = status, lastSyncAt = System.currentTimeMillis()))
    }

    /** Persists a tools/list sync with classifications (§18): canonical mail
     *  values by plain name only for the trusted mail server (F-10);
     *  anything else starts at the E3/D2 default. */
    suspend fun saveSyncedTools(server: McpServerEntity, tools: List<McpRemoteTool>, trustedMailServerId: String? = null) {
        db.mcpDao().deleteToolsForServer(server.id)
        val isTrusted = trustedMailServerId != null && server.id == trustedMailServerId
        for (t in tools) {
            val canonical = ToolRegistry.mailTool(t.name, isTrusted)
            db.mcpDao().insertTool(
                McpToolEntity(
                    serverId = server.id,
                    plainName = t.name,
                    prefixedName = ToolRegistry.prefixed(server.prefix, t.name),
                    description = t.description,
                    schemaJson = t.inputSchemaJson,
                    effect = (canonical?.effect ?: Effect.E3).name,
                    disclosure = (canonical?.disclosure ?: Disclosure.D2).name,
                ),
            )
        }
        setServerStatus(server, "CONNECTED")
    }

    suspend fun updateToolClassification(tool: McpToolEntity, effect: Effect, disclosure: Disclosure) {
        db.mcpDao().updateTool(tool.copy(effect = effect.name, disclosure = disclosure.name))
    }

    /** Builds the engine registry (prefixed MCP metas) for a bot's servers. */
    suspend fun registryForServers(serverIds: List<String>): Map<String, ToolMeta> {
        if (serverIds.isEmpty()) return emptyMap()
        return db.mcpDao().toolsForServers(serverIds).associate { t ->
            t.prefixedName to ToolMeta(
                name = t.prefixedName,
                effect = runCatching { Effect.valueOf(t.effect) }.getOrDefault(Effect.E3),
                disclosure = runCatching { Disclosure.valueOf(t.disclosure) }.getOrDefault(Disclosure.D2),
                source = ToolSource.MCP,
                description = t.description,
                inputSchemaJson = t.schemaJson,
            )
        }
    }
}
