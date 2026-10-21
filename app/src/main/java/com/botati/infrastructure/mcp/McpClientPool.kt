package com.botati.infrastructure.mcp

import com.botati.data.db.McpServerEntity
import com.botati.data.vault.Vault
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One client per server (§4/§21). The pool itself takes no global lock, so
 * calls to different servers proceed in parallel; serialization happens only
 * inside a legacy-era client, around its own session.
 */
class McpClientPool(
    private val http: HttpClient,
    private val vault: Vault,
) {
    private val clients = ConcurrentHashMap<String, MinimalMcpClient>()

    fun clientFor(server: McpServerEntity): MinimalMcpClient =
        clients.getOrPut(server.id) {
            MinimalMcpClient(http, server.url) { vault.getSecret(Vault.mcpSlot(server.id)) }
        }

    suspend fun listTools(server: McpServerEntity): List<McpRemoteTool> =
        clientFor(server).listTools()

    suspend fun callTool(server: McpServerEntity, plainName: String, args: JsonObject): String =
        clientFor(server).callTool(plainName, args)

    fun evict(serverId: String) {
        clients.remove(serverId)?.close()
    }
}
