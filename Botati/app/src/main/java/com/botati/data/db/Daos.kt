package com.botati.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BotDao {
    @Query("SELECT * FROM bots ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<BotEntity>>
    @Query("SELECT * FROM bots WHERE id = :id")
    suspend fun byId(id: String): BotEntity?
    @Query("SELECT * FROM bots WHERE id = :id")
    fun observeById(id: String): Flow<BotEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(bot: BotEntity)
    @Delete suspend fun delete(bot: BotEntity)
    @Query("SELECT COUNT(*) FROM bots") suspend fun count(): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE botId = :botId ORDER BY createdAt DESC")
    fun observeForBot(botId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE botId = :botId ORDER BY createdAt DESC")
    suspend fun listForBot(botId: String): List<ConversationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ConversationEntity)
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun byId(id: String): ConversationEntity?
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id ASC")
    fun observeForConversation(cid: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id ASC")
    suspend fun forConversation(cid: String): List<MessageEntity>
    @Insert suspend fun insert(m: MessageEntity): Long
}

@Dao
interface McpDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun observeServers(): Flow<List<McpServerEntity>>
    @Query("SELECT * FROM mcp_servers WHERE id = :id") suspend fun serverById(id: String): McpServerEntity?
    @Query("SELECT * FROM mcp_servers WHERE prefix = :prefix") suspend fun serverByPrefix(prefix: String): McpServerEntity?
    @Query("SELECT * FROM mcp_servers") suspend fun allServers(): List<McpServerEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertServer(s: McpServerEntity)
    @Query("DELETE FROM mcp_servers WHERE id = :id") suspend fun deleteServer(id: String)
    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY plainName ASC")
    fun observeTools(serverId: String): Flow<List<McpToolEntity>>
    @Query("SELECT * FROM mcp_tools WHERE serverId IN (:serverIds)")
    suspend fun toolsForServers(serverIds: List<String>): List<McpToolEntity>
    @Query("SELECT * FROM mcp_tools ORDER BY prefixedName ASC")
    fun observeAllTools(): Flow<List<McpToolEntity>>
    @Insert suspend fun insertTool(t: McpToolEntity)
    @Update suspend fun updateTool(t: McpToolEntity)
    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId") suspend fun deleteToolsForServer(serverId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM task_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskSessionEntity>>
    @Query("SELECT * FROM task_sessions WHERE id = :id") suspend fun byId(id: String): TaskSessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: TaskSessionEntity)
    @Query("UPDATE task_sessions SET status = :status, lastHeartbeatAt = :hb WHERE id = :id")
    suspend fun setStatus(id: String, status: String, hb: Long)
    @Query("UPDATE task_sessions SET lastHeartbeatAt = :hb WHERE id = :id")
    suspend fun heartbeat(id: String, hb: Long)
    /** §5.7: orphaned RUNNING sessions from a dead process become INTERRUPTED. */
    @Query("UPDATE task_sessions SET status = 'INTERRUPTED', finalReport = :report WHERE status = 'RUNNING' AND lastHeartbeatAt < :processStartMs")
    suspend fun interruptOrphans(processStartMs: Long, report: String): Int
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_entries ORDER BY id DESC LIMIT 500")
    fun observeRecent(): Flow<List<AuditEntryEntity>>
    @Query("SELECT * FROM audit_entries WHERE botId = :botId ORDER BY id DESC LIMIT 500")
    fun observeForBot(botId: String): Flow<List<AuditEntryEntity>>
    @Insert suspend fun insert(e: AuditEntryEntity)
}

@Dao
interface UsageDao {
    @Insert suspend fun insert(u: UsageRecordEntity)
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_records WHERE botId = :botId AND timestamp >= :sinceMs")
    fun observeCostSince(botId: String, sinceMs: Long): Flow<Double>
    @Query("SELECT * FROM usage_records WHERE botId = :botId ORDER BY id DESC LIMIT 100")
    fun observeForBot(botId: String): Flow<List<UsageRecordEntity>>
}

@Dao
interface ApprovalDao {
    @Insert suspend fun insert(a: ApprovalEntity)
    @Query("SELECT approvalKey FROM approvals WHERE botId = :botId")
    suspend fun keysForBot(botId: String): List<String>
    @Query("SELECT * FROM approvals ORDER BY id DESC LIMIT 200")
    fun observeAll(): Flow<List<ApprovalEntity>>
}

@Dao
interface FileGrantDao {
    @Insert suspend fun insert(g: FileGrantEntity)
    @Query("SELECT * FROM file_grants WHERE botId = :botId")
    fun observeForBot(botId: String): Flow<List<FileGrantEntity>>
    @Query("SELECT treeUri FROM file_grants WHERE botId = :botId")
    suspend fun urisForBot(botId: String): List<String>
    @Query("DELETE FROM file_grants WHERE botId = :botId AND treeUri = :uri")
    suspend fun revoke(botId: String, uri: String)
}

@Database(
    entities = [
        BotEntity::class, ConversationEntity::class, MessageEntity::class,
        McpServerEntity::class, McpToolEntity::class, TaskSessionEntity::class,
        AuditEntryEntity::class, UsageRecordEntity::class, ApprovalEntity::class,
        FileGrantEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BotatiDatabase : RoomDatabase() {
    abstract fun botDao(): BotDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun mcpDao(): McpDao
    abstract fun taskDao(): TaskDao
    abstract fun auditDao(): AuditDao
    abstract fun usageDao(): UsageDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun fileGrantDao(): FileGrantDao
}
