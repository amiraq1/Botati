package com.botati.infrastructure.mcp

import kotlinx.serialization.json.JsonObject

data class McpRemoteTool(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
)

enum class McpEra { LEGACY, STATELESS_2026 }

data class McpServerInfo(
    val name: String = "",
    val version: String = "",
    val protocolVersion: String = "",
    val era: McpEra = McpEra.LEGACY,
)

/**
 * The MCP port (§21). All protocol details — era negotiation, sessions,
 * headers — live behind this interface so the Agent/Policy engines never see
 * them. Two eras exist in the wild and the client must be dual-era:
 *  - LEGACY (≤2025-11-25): `initialize` handshake, `Mcp-Session-Id`, a GET
 *    stream, notifications/cancelled for in-flight requests.
 *  - 2026-07-28: stateless — no handshake, no sessions, no GET stream; every
 *    request carries `_meta` + protocol headers, discovery via
 *    `server/discover`, version errors are -32022.
 * Cancellation on Streamable HTTP is disconnect-first in both eras;
 * `notifications/cancelled` is fire-and-forget best effort (it has no reply).
 */
interface McpClientPort {
    suspend fun connect(): McpServerInfo
    suspend fun listTools(): List<McpRemoteTool>
    suspend fun callTool(name: String, arguments: JsonObject): String
    /** Best effort only (§5.6): never awaited as an acknowledgement. */
    suspend fun notifyCancelled(requestId: String)
    fun close()
}
