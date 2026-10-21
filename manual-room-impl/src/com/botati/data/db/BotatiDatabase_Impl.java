package com.botati.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomOpenHelper;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written equivalent of the Room-KSP-generated database implementation
 * (see manual-room-impl/README.md). Mirrors Room 2.6 generated code.
 */
public final class BotatiDatabase_Impl extends BotatiDatabase {
    private volatile BotDao _botDao;
    private volatile ConversationDao _conversationDao;
    private volatile MessageDao _messageDao;
    private volatile McpDao _mcpDao;
    private volatile TaskDao _taskDao;
    private volatile AuditDao _auditDao;
    private volatile UsageDao _usageDao;
    private volatile ApprovalDao _approvalDao;
    private volatile FileGrantDao _fileGrantDao;

    @Override
    protected SupportSQLiteOpenHelper createOpenHelper(DatabaseConfiguration configuration) {
        final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(configuration, new RoomOpenHelper.Delegate(1) {
            @Override
            public void createAllTables(@NonNull SupportSQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `bots` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `systemPrompt` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `temperature` REAL NOT NULL, `autonomyMode` TEXT NOT NULL, `allowedToolsJson` TEXT NOT NULL, `mcpServerIdsJson` TEXT NOT NULL, `safTreeUrisJson` TEXT NOT NULL, `maxStepsOverride` INTEGER, `budgetOverride` REAL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `botId` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
                db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `toolCallsJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, `createdAt` INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prefix` TEXT NOT NULL, `url` TEXT NOT NULL, `status` TEXT NOT NULL, `lastSyncAt` INTEGER, PRIMARY KEY(`id`))");
                db.execSQL("CREATE TABLE IF NOT EXISTS `mcp_tools` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serverId` TEXT NOT NULL, `plainName` TEXT NOT NULL, `prefixedName` TEXT NOT NULL, `description` TEXT NOT NULL, `schemaJson` TEXT NOT NULL, `effect` TEXT NOT NULL, `disclosure` TEXT NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_sessions` (`id` TEXT NOT NULL, `botId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `instruction` TEXT NOT NULL, `contractJson` TEXT, `contractHash` TEXT, `status` TEXT NOT NULL, `stepsUsed` INTEGER NOT NULL, `costUsed` REAL NOT NULL, `lastHeartbeatAt` INTEGER NOT NULL, `finalReport` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
                db.execSQL("CREATE TABLE IF NOT EXISTS `audit_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT, `botId` TEXT NOT NULL, `tool` TEXT NOT NULL, `target` TEXT, `effect` TEXT NOT NULL, `disclosure` TEXT NOT NULL, `decision` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `argsDigest` TEXT NOT NULL, `resultSummary` TEXT, `idempotencyKey` TEXT, `timestamp` INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS `usage_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `botId` TEXT NOT NULL, `taskId` TEXT, `model` TEXT NOT NULL, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `costUsd` REAL, `costSource` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS `approvals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `botId` TEXT NOT NULL, `approvalKey` TEXT NOT NULL, `contractHash` TEXT, `grantedAt` INTEGER NOT NULL, `note` TEXT NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS `file_grants` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `botId` TEXT NOT NULL, `treeUri` TEXT NOT NULL, `grantedAt` INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'botati-db-v1-identity')");
            }

            @Override
            public void dropAllTables(@NonNull SupportSQLiteDatabase db) {
                db.execSQL("DROP TABLE IF EXISTS `bots`");
                db.execSQL("DROP TABLE IF EXISTS `conversations`");
                db.execSQL("DROP TABLE IF EXISTS `messages`");
                db.execSQL("DROP TABLE IF EXISTS `mcp_servers`");
                db.execSQL("DROP TABLE IF EXISTS `mcp_tools`");
                db.execSQL("DROP TABLE IF EXISTS `task_sessions`");
                db.execSQL("DROP TABLE IF EXISTS `audit_entries`");
                db.execSQL("DROP TABLE IF EXISTS `usage_records`");
                db.execSQL("DROP TABLE IF EXISTS `approvals`");
                db.execSQL("DROP TABLE IF EXISTS `file_grants`");
                if (mCallbacks != null) {
                    for (int _i = 0, _size = mCallbacks.size(); _i < _size; _i++) {
                        mCallbacks.get(_i).onDestructiveMigration(db);
                    }
                }
            }

            @Override
            public void onCreate(@NonNull SupportSQLiteDatabase db) {
                if (mCallbacks != null) {
                    for (int _i = 0, _size = mCallbacks.size(); _i < _size; _i++) {
                        mCallbacks.get(_i).onCreate(db);
                    }
                }
            }

            @Override
            public void onOpen(@NonNull SupportSQLiteDatabase db) {
                mDatabase = db;
                internalInitInvalidationTracker(db);
                if (mCallbacks != null) {
                    for (int _i = 0, _size = mCallbacks.size(); _i < _size; _i++) {
                        mCallbacks.get(_i).onOpen(db);
                    }
                }
            }

            @Override
            public RoomOpenHelper.ValidationResult onValidateSchema(@NonNull SupportSQLiteDatabase db) {
                Map<String, TableInfo.Column> _columns;
                Set<TableInfo.ForeignKey> _fks;
                Set<TableInfo.Index> _indices;
                TableInfo _expected;
                TableInfo _found;

                _columns = new HashMap<>(14);
                _columns.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("icon", new TableInfo.Column("icon", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("systemPrompt", new TableInfo.Column("systemPrompt", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("provider", new TableInfo.Column("provider", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("model", new TableInfo.Column("model", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("temperature", new TableInfo.Column("temperature", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("autonomyMode", new TableInfo.Column("autonomyMode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("allowedToolsJson", new TableInfo.Column("allowedToolsJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("mcpServerIdsJson", new TableInfo.Column("mcpServerIdsJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("safTreeUrisJson", new TableInfo.Column("safTreeUrisJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("maxStepsOverride", new TableInfo.Column("maxStepsOverride", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("budgetOverride", new TableInfo.Column("budgetOverride", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("bots", _columns, _fks, _indices);
                _found = TableInfo.read(db, "bots");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "bots(com.botati.data.db.BotEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(4);
                _columns.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("title", new TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("conversations", _columns, _fks, _indices);
                _found = TableInfo.read(db, "conversations");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "conversations(com.botati.data.db.ConversationEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(8);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("conversationId", new TableInfo.Column("conversationId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("role", new TableInfo.Column("role", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("content", new TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("toolCallsJson", new TableInfo.Column("toolCallsJson", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("toolCallId", new TableInfo.Column("toolCallId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("toolName", new TableInfo.Column("toolName", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("messages", _columns, _fks, _indices);
                _found = TableInfo.read(db, "messages");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "messages(com.botati.data.db.MessageEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(6);
                _columns.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("prefix", new TableInfo.Column("prefix", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("url", new TableInfo.Column("url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("lastSyncAt", new TableInfo.Column("lastSyncAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("mcp_servers", _columns, _fks, _indices);
                _found = TableInfo.read(db, "mcp_servers");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "mcp_servers(com.botati.data.db.McpServerEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(8);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("serverId", new TableInfo.Column("serverId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("plainName", new TableInfo.Column("plainName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("prefixedName", new TableInfo.Column("prefixedName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("description", new TableInfo.Column("description", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("schemaJson", new TableInfo.Column("schemaJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("effect", new TableInfo.Column("effect", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("disclosure", new TableInfo.Column("disclosure", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("mcp_tools", _columns, _fks, _indices);
                _found = TableInfo.read(db, "mcp_tools");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "mcp_tools(com.botati.data.db.McpToolEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(12);
                _columns.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("conversationId", new TableInfo.Column("conversationId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("instruction", new TableInfo.Column("instruction", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("contractJson", new TableInfo.Column("contractJson", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("contractHash", new TableInfo.Column("contractHash", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("stepsUsed", new TableInfo.Column("stepsUsed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("costUsed", new TableInfo.Column("costUsed", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("lastHeartbeatAt", new TableInfo.Column("lastHeartbeatAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("finalReport", new TableInfo.Column("finalReport", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("task_sessions", _columns, _fks, _indices);
                _found = TableInfo.read(db, "task_sessions");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "task_sessions(com.botati.data.db.TaskSessionEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(13);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("taskId", new TableInfo.Column("taskId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("tool", new TableInfo.Column("tool", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("target", new TableInfo.Column("target", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("effect", new TableInfo.Column("effect", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("disclosure", new TableInfo.Column("disclosure", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("decision", new TableInfo.Column("decision", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("reasonCode", new TableInfo.Column("reasonCode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("argsDigest", new TableInfo.Column("argsDigest", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("resultSummary", new TableInfo.Column("resultSummary", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("idempotencyKey", new TableInfo.Column("idempotencyKey", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("timestamp", new TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("audit_entries", _columns, _fks, _indices);
                _found = TableInfo.read(db, "audit_entries");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "audit_entries(com.botati.data.db.AuditEntryEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(9);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("taskId", new TableInfo.Column("taskId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("model", new TableInfo.Column("model", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("promptTokens", new TableInfo.Column("promptTokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("completionTokens", new TableInfo.Column("completionTokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("costUsd", new TableInfo.Column("costUsd", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("costSource", new TableInfo.Column("costSource", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("timestamp", new TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("usage_records", _columns, _fks, _indices);
                _found = TableInfo.read(db, "usage_records");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "usage_records(com.botati.data.db.UsageRecordEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(6);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("approvalKey", new TableInfo.Column("approvalKey", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("contractHash", new TableInfo.Column("contractHash", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("grantedAt", new TableInfo.Column("grantedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("note", new TableInfo.Column("note", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("approvals", _columns, _fks, _indices);
                _found = TableInfo.read(db, "approvals");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "approvals(com.botati.data.db.ApprovalEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                _columns = new HashMap<>(4);
                _columns.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("botId", new TableInfo.Column("botId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("treeUri", new TableInfo.Column("treeUri", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _columns.put("grantedAt", new TableInfo.Column("grantedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
                _fks = new HashSet<>(0);
                _indices = new HashSet<>(0);
                _expected = new TableInfo("file_grants", _columns, _fks, _indices);
                _found = TableInfo.read(db, "file_grants");
                if (!_expected.equals(_found)) {
                    return new RoomOpenHelper.ValidationResult(false, "file_grants(com.botati.data.db.FileGrantEntity).\n Expected:\n" + _expected + "\n Found:\n" + _found);
                }

                return new RoomOpenHelper.ValidationResult(true, null);
            }
        }, "botati-db-v1-identity", "botati-db-v1-identity");
        final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(_openCallback)
                .build();
        final SupportSQLiteOpenHelper _helper = configuration.sqliteOpenHelperFactory.create(_sqliteConfig);
        return _helper;
    }

    @Override
    protected InvalidationTracker createInvalidationTracker() {
        final HashMap<String, String> _shadowTablesMap = new HashMap<>(0);
        HashMap<String, Set<String>> _viewTables = new HashMap<>(0);
        return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "bots", "conversations", "messages", "mcp_servers", "mcp_tools", "task_sessions", "audit_entries", "usage_records", "approvals", "file_grants");
    }

    @Override
    public void clearAllTables() {
        super.assertNotMainThread();
        final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
        try {
            super.beginTransaction();
            _db.execSQL("DELETE FROM `bots`");
            _db.execSQL("DELETE FROM `conversations`");
            _db.execSQL("DELETE FROM `messages`");
            _db.execSQL("DELETE FROM `mcp_servers`");
            _db.execSQL("DELETE FROM `mcp_tools`");
            _db.execSQL("DELETE FROM `task_sessions`");
            _db.execSQL("DELETE FROM `audit_entries`");
            _db.execSQL("DELETE FROM `usage_records`");
            _db.execSQL("DELETE FROM `approvals`");
            _db.execSQL("DELETE FROM `file_grants`");
            super.setTransactionSuccessful();
        } finally {
            super.endTransaction();
            _db.query("PRAGMA wal_checkpoint(FULL)").close();
            if (!_db.inTransaction()) {
                _db.execSQL("VACUUM");
            }
        }
    }

    @Override
    public BotDao botDao() {
        if (_botDao != null) {
            return _botDao;
        } else {
            synchronized (this) {
                if (_botDao == null) {
                    _botDao = new BotDao_Impl(this);
                }
                return _botDao;
            }
        }
    }

    @Override
    public ConversationDao conversationDao() {
        if (_conversationDao != null) {
            return _conversationDao;
        } else {
            synchronized (this) {
                if (_conversationDao == null) {
                    _conversationDao = new ConversationDao_Impl(this);
                }
                return _conversationDao;
            }
        }
    }

    @Override
    public MessageDao messageDao() {
        if (_messageDao != null) {
            return _messageDao;
        } else {
            synchronized (this) {
                if (_messageDao == null) {
                    _messageDao = new MessageDao_Impl(this);
                }
                return _messageDao;
            }
        }
    }

    @Override
    public McpDao mcpDao() {
        if (_mcpDao != null) {
            return _mcpDao;
        } else {
            synchronized (this) {
                if (_mcpDao == null) {
                    _mcpDao = new McpDao_Impl(this);
                }
                return _mcpDao;
            }
        }
    }

    @Override
    public TaskDao taskDao() {
        if (_taskDao != null) {
            return _taskDao;
        } else {
            synchronized (this) {
                if (_taskDao == null) {
                    _taskDao = new TaskDao_Impl(this);
                }
                return _taskDao;
            }
        }
    }

    @Override
    public AuditDao auditDao() {
        if (_auditDao != null) {
            return _auditDao;
        } else {
            synchronized (this) {
                if (_auditDao == null) {
                    _auditDao = new AuditDao_Impl(this);
                }
                return _auditDao;
            }
        }
    }

    @Override
    public UsageDao usageDao() {
        if (_usageDao != null) {
            return _usageDao;
        } else {
            synchronized (this) {
                if (_usageDao == null) {
                    _usageDao = new UsageDao_Impl(this);
                }
                return _usageDao;
            }
        }
    }

    @Override
    public ApprovalDao approvalDao() {
        if (_approvalDao != null) {
            return _approvalDao;
        } else {
            synchronized (this) {
                if (_approvalDao == null) {
                    _approvalDao = new ApprovalDao_Impl(this);
                }
                return _approvalDao;
            }
        }
    }

    @Override
    public FileGrantDao fileGrantDao() {
        if (_fileGrantDao != null) {
            return _fileGrantDao;
        } else {
            synchronized (this) {
                if (_fileGrantDao == null) {
                    _fileGrantDao = new FileGrantDao_Impl(this);
                }
                return _fileGrantDao;
            }
        }
    }
}
