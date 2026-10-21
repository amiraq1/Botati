package com.botati.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entities (§7 + §23). INVARIANT: no API keys or MCP tokens are ever
 * stored here — secrets live only in the Vault (Tink + DataStore), keyed by
 * provider/server id. Tool/contract collections are JSON strings to keep the
 * schema flat and reviewable.
 */

@Entity(tableName = "bots")
data class BotEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val systemPrompt: String,
    val provider: String = "openrouter",
    val model: String,
    val temperature: Double = 0.7,
    /** SUPERVISED | TASK_FREEDOM */
    val autonomyMode: String = "TASK_FREEDOM",
    val allowedToolsJson: String = "[]",
    val mcpServerIdsJson: String = "[]",
    val safTreeUrisJson: String = "[]",
    val maxStepsOverride: Int? = null,
    val budgetOverride: Double? = null,
    val createdAt: Long,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val botId: String,
    val title: String,
    val createdAt: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** system | user | assistant | tool */
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Sanitized, unique, contains no "__" (§0). Bearer token is in the Vault. */
    val prefix: String,
    val url: String,
    /** UNKNOWN | CONNECTED | ERROR */
    val status: String = "UNKNOWN",
    val lastSyncAt: Long? = null,
)

@Entity(tableName = "mcp_tools")
data class McpToolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val plainName: String,
    val prefixedName: String,
    val description: String = "",
    val schemaJson: String = "{}",
    /** Two-axis classification (§18), user-editable after sync. */
    val effect: String,
    val disclosure: String,
)

@Entity(tableName = "task_sessions")
data class TaskSessionEntity(
    @PrimaryKey val id: String,
    val botId: String,
    val conversationId: String,
    val instruction: String,
    val contractJson: String? = null,
    val contractHash: String? = null,
    /** PROPOSED | RUNNING | PAUSED_OUT_OF_SCOPE | STOPPED_LIMIT | CANCELLED | INTERRUPTED | DONE | FAILED */
    val status: String = "PROPOSED",
    val stepsUsed: Int = 0,
    val costUsed: Double = 0.0,
    val lastHeartbeatAt: Long = 0,
    val finalReport: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "audit_entries")
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String? = null,
    val botId: String,
    val tool: String,
    val target: String? = null,
    val effect: String,
    val disclosure: String,
    val decision: String,
    val reasonCode: String,
    val argsDigest: String,
    val resultSummary: String? = null,
    val idempotencyKey: String? = null,
    val timestamp: Long,
)

@Entity(tableName = "usage_records")
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val botId: String,
    val taskId: String? = null,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    /** Nullable on purpose (§5.5): unknown cost is recorded as unknown. */
    val costUsd: Double? = null,
    /** actual | estimated | unknown (§26) */
    val costSource: String,
    val timestamp: Long,
)

@Entity(tableName = "approvals")
data class ApprovalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val botId: String,
    /** disclose:<target> | contract | tool-once */
    val approvalKey: String,
    val contractHash: String? = null,
    val grantedAt: Long,
    val note: String = "",
)

@Entity(tableName = "file_grants")
data class FileGrantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val botId: String,
    val treeUri: String,
    val grantedAt: Long,
)
