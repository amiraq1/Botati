package com.botati.infrastructure.tools

import com.botati.data.db.McpServerEntity
import com.botati.domain.agent.ToolExecutorPort
import com.botati.domain.policy.TargetKind
import com.botati.domain.policy.ToolCall
import com.botati.domain.tools.FileGrantGuard
import com.botati.domain.tools.IdempotencyKeys
import com.botati.domain.tools.ToolRegistry
import com.botati.infrastructure.mcp.McpClientPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Tool Router (§4/§28): every approved call lands here and is dispatched
 * by source. Three cross-cutting rules live HERE, not inside individual tools:
 *
 *  1. SAF grants: file calls are checked against the bot's granted trees
 *     before any file tool runs, so future file tools inherit the guard.
 *
 *  2. Idempotency (§24): for mail `send_reply` the deterministic key is derived
 *     here and injected as an internal `_idempotencyKey` argument — it is
 *     never part of the model-facing schema, and the model cannot forge it.
 *     SEC-02: every field the model is not allowed to choose is STRIPPED
 *     before injection, so a server schema that happens to expose
 *     `idempotencyKey` (or `to`/`cc`/`bcc`) cannot turn a model-written value
 *     into the one that decides whether a mail is sent twice.
 *
 *  3. Threading (§9): file tools do blocking I/O (content resolver, ZIP
 *     inflation, PdfBox). They run on [io], never on the caller's dispatcher,
 *     which for the chat engine is Main.
 */
class ToolRouter(
    private val botId: String,
    private val taskIdProvider: () -> String?,
    private val grantsProvider: suspend () -> List<String>,
    private val serverByPrefix: suspend (String) -> McpServerEntity?,
    private val fileTools: FileTools,
    private val webTools: WebTools,
    private val mcpPool: McpClientPool,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
                    // §9: blocking extraction off the caller's dispatcher.
                    withContext(io) {
                        if (call.name == ToolRegistry.READ_FILE) fileTools.readFile(call.arguments)
                        else fileTools.analyzeFile(call.arguments)
                    }
                }
                call.name == ToolRegistry.WEB_SEARCH -> webTools.search(call.arguments)
                call.name == ToolRegistry.WEB_FETCH -> webTools.fetch(call.arguments)
                call.name.contains("__") -> executeMcp(call)
                else -> """{"error":"tool_not_found","tool":"${call.name}"}"""
            }
        } catch (e: CancellationException) {
            // §5.6: cancellation is not a tool failure — never turn it into a
            // `tool_failed` row that the model reads as "try again".
            throw e
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
        if (plain == ToolRegistry.SEND_REPLY) {
            // SEC-02: drop every model-supplied field that must be derived
            // executor-side. A server schema is third-party data; if it exposes
            // `idempotencyKey`, the model can otherwise choose the value that
            // makes two different sends look like one (or one look like two).
            args = JsonObject(
                args.filterKeys { key -> key.lowercase().replace("_", "") !in SEND_REPLY_BANNED_ARGS },
            )
            // §24: executor-side deterministic idempotency key.
            val threadId = (args["threadId"] as? JsonPrimitive)?.contentOrNull ?: ""
            val body = (args["body"] as? JsonPrimitive)?.contentOrNull ?: ""
            val key = IdempotencyKeys.forMailSend(taskIdProvider() ?: "chat-$botId", threadId, body)
            args = JsonObject(args + ("_idempotencyKey" to JsonPrimitive(key)))
        }
        return mcpPool.callTool(server, plain, args)
    }

    companion object {
        /**
         * Fields that must never reach a mail server from the model: recipients
         * (the thread derives them) and any idempotency field (derived here).
         * Compared lowercased with underscores removed, so `idempotency_key`,
         * `IdempotencyKey` and `idempotencyKey` all match.
         */
        internal val SEND_REPLY_BANNED_ARGS = setOf(
            "idempotencykey", "to", "cc", "bcc", "recipient", "recipients", "threadparticipants",
        )
    }
}
