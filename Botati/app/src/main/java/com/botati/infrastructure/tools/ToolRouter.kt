package com.botati.infrastructure.tools

import com.botati.data.db.McpServerEntity
import com.botati.domain.agent.ToolExecutorPort
import com.botati.domain.policy.TargetKind
import com.botati.domain.policy.ToolCall
import com.botati.domain.tools.FileGrantGuard
import com.botati.domain.tools.IdempotencyKeys
import com.botati.domain.tools.ToolRegistry
import com.botati.infrastructure.mcp.McpClientPool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The Tool Router (§4/§28): every approved call lands here and is dispatched
 * by source. Two cross-cutting rules live HERE, not inside individual tools:
 *  1. SAF grants: file calls are checked against the bot's granted trees
 *     before any file tool runs, so future file tools inherit the guard.
 *  2. Idempotency (§24): for mail send_reply the deterministic key is derived
 *     here and injected as an internal `_idempotencyKey` argument — it is
 *     never part of the model-facing schema, and the model cannot forge it
 *     (any model-supplied value is overwritten).
 */
class ToolRouter(
    private val botId: String,
    private val taskIdProvider: () -> String?,
    private val grantsProvider: suspend () -> List<String>,
    private val serverByPrefix: suspend (String) -> McpServerEntity?,
    private val fileTools: FileTools,
    private val webTools: WebTools,
    private val mcpPool: McpClientPool,
) : ToolExecutorPort {

    override suspend fun execute(call: ToolCall): String {
        return try {
            when {
                call.name == ToolRegistry.READ_FILE || call.name == ToolRegistry.ANALYZE_FILE -> {
                    // §28 router-level SAF guard.
                    val target = call.target
                    val grants = grantsProvider()
                    if (call.targetKind != TargetKind.FILE || target == null ||
                        !FileGrantGuard.isGranted(target, grants)
                    ) {
                        return """{"error":"outside_granted_folders","hint":"the user must grant this folder/file to the bot first"}"""
                    }
                    if (call.name == ToolRegistry.READ_FILE) fileTools.readFile(call.arguments)
                    else fileTools.analyzeFile(call.arguments)
                }
                call.name == ToolRegistry.WEB_SEARCH -> webTools.search(call.arguments)
                call.name == ToolRegistry.WEB_FETCH -> webTools.fetch(call.arguments)
                call.name.contains("__") -> executeMcp(call)
                else -> """{"error":"tool_not_found","tool":"${call.name}"}"""
            }
        } catch (e: Exception) {
            """{"error":"tool_failed","detail":"${(e.message ?: "unknown").replace("\"", "'").take(200)}"}"""
        }
    }

    private suspend fun executeMcp(call: ToolCall): String {
        val prefix = call.name.substringBefore("__")
        val plain = ToolRegistry.plainName(call.name)
        val server = serverByPrefix(prefix)
            ?: return """{"error":"mcp_server_not_found","prefix":"$prefix"}"""
        var args: JsonObject = call.arguments
        if (plain == "send_reply") {
            // §24: executor-side deterministic idempotency key.
            val threadId = (args["threadId"] as? JsonPrimitive)?.contentOrNull ?: ""
            val body = (args["body"] as? JsonPrimitive)?.contentOrNull ?: ""
            val key = IdempotencyKeys.forMailSend(taskIdProvider() ?: "chat-$botId", threadId, body)
            args = JsonObject(args + ("_idempotencyKey" to JsonPrimitive(key)))
        }
        return mcpPool.callTool(server, plain, args)
    }
}
