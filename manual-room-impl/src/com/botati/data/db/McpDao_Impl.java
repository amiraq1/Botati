package com.botati.data.db;

import android.database.Cursor;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.room.util.StringUtil;
import androidx.sqlite.db.SupportSQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/** Hand-written equivalent of Room-generated code (see manual-room-impl/README.md). */
public final class McpDao_Impl implements McpDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<McpServerEntity> __serverInsertion;
    private final EntityInsertionAdapter<McpToolEntity> __toolInsertion;
    private final EntityDeletionOrUpdateAdapter<McpToolEntity> __toolUpdate;

    public McpDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__serverInsertion = new EntityInsertionAdapter<McpServerEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `mcp_servers` (`id`,`name`,`prefix`,`url`,`status`,`lastSyncAt`) VALUES (?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, McpServerEntity entity) {
                statement.bindString(1, entity.getId());
                statement.bindString(2, entity.getName());
                statement.bindString(3, entity.getPrefix());
                statement.bindString(4, entity.getUrl());
                statement.bindString(5, entity.getStatus());
                if (entity.getLastSyncAt() == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindLong(6, entity.getLastSyncAt());
                }
            }
        };
        this.__toolInsertion = new EntityInsertionAdapter<McpToolEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `mcp_tools` (`id`,`serverId`,`plainName`,`prefixedName`,`description`,`schemaJson`,`effect`,`disclosure`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, McpToolEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getServerId());
                statement.bindString(3, entity.getPlainName());
                statement.bindString(4, entity.getPrefixedName());
                statement.bindString(5, entity.getDescription());
                statement.bindString(6, entity.getSchemaJson());
                statement.bindString(7, entity.getEffect());
                statement.bindString(8, entity.getDisclosure());
            }
        };
        this.__toolUpdate = new EntityDeletionOrUpdateAdapter<McpToolEntity>(__db) {
            @Override
            protected String createQuery() {
                return "UPDATE OR ABORT `mcp_tools` SET `id` = ?,`serverId` = ?,`plainName` = ?,`prefixedName` = ?,`description` = ?,`schemaJson` = ?,`effect` = ?,`disclosure` = ? WHERE `id` = ?";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, McpToolEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getServerId());
                statement.bindString(3, entity.getPlainName());
                statement.bindString(4, entity.getPrefixedName());
                statement.bindString(5, entity.getDescription());
                statement.bindString(6, entity.getSchemaJson());
                statement.bindString(7, entity.getEffect());
                statement.bindString(8, entity.getDisclosure());
                statement.bindLong(9, entity.getId());
            }
        };
    }

    static McpServerEntity __mapServer(Cursor c) {
        final int _lastSyncAt = CursorUtil.getColumnIndexOrThrow(c, "lastSyncAt");
        return new McpServerEntity(
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "name")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "prefix")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "url")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "status")),
                c.isNull(_lastSyncAt) ? null : c.getLong(_lastSyncAt));
    }

    static McpToolEntity __mapTool(Cursor c) {
        return new McpToolEntity(
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "serverId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "plainName")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "prefixedName")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "description")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "schemaJson")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "effect")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "disclosure")));
    }

    @Override
    public Flow<List<McpServerEntity>> observeServers() {
        final String _sql = "SELECT * FROM mcp_servers ORDER BY name ASC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"mcp_servers"}, new Callable<List<McpServerEntity>>() {
            @Override
            public List<McpServerEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<McpServerEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapServer(_cursor));
                    }
                    return _result;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        });
    }

    @Override
    public Object serverById(final String id, final Continuation<? super McpServerEntity> continuation) {
        final String _sql = "SELECT * FROM mcp_servers WHERE id = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<McpServerEntity>() {
            @Override
            public McpServerEntity call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, id);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    return _cursor.moveToFirst() ? __mapServer(_cursor) : null;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }

    @Override
    public Object serverByPrefix(final String prefix, final Continuation<? super McpServerEntity> continuation) {
        final String _sql = "SELECT * FROM mcp_servers WHERE prefix = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<McpServerEntity>() {
            @Override
            public McpServerEntity call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, prefix);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    return _cursor.moveToFirst() ? __mapServer(_cursor) : null;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }

    @Override
    public Object allServers(final Continuation<? super List<McpServerEntity>> continuation) {
        final String _sql = "SELECT * FROM mcp_servers";
        return CoroutinesRoom.execute(__db, false, new Callable<List<McpServerEntity>>() {
            @Override
            public List<McpServerEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<McpServerEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapServer(_cursor));
                    }
                    return _result;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }

    @Override
    public Object upsertServer(final McpServerEntity s, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __serverInsertion.insert(s);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object deleteServer(final String id, final Continuation<? super Unit> continuation) {
        final String _sql = "DELETE FROM mcp_servers WHERE id = ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindString(1, id);
                __db.beginTransaction();
                try {
                    _stmt.execute();
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Flow<List<McpToolEntity>> observeTools(final String serverId) {
        final String _sql = "SELECT * FROM mcp_tools WHERE serverId = ? ORDER BY plainName ASC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"mcp_tools"}, new Callable<List<McpToolEntity>>() {
            @Override
            public List<McpToolEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, serverId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<McpToolEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapTool(_cursor));
                    }
                    return _result;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        });
    }

    @Override
    public Object toolsForServers(final List<String> serverIds, final Continuation<? super List<McpToolEntity>> continuation) {
        final StringBuilder _sb = StringUtil.newStringBuilder();
        _sb.append("SELECT * FROM mcp_tools WHERE serverId IN (");
        final int _inputSize = serverIds.size();
        StringUtil.appendPlaceholders(_sb, _inputSize);
        _sb.append(")");
        final String _sql = _sb.toString();
        return CoroutinesRoom.execute(__db, false, new Callable<List<McpToolEntity>>() {
            @Override
            public List<McpToolEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, _inputSize);
                int _argIndex = 1;
                for (String _item : serverIds) {
                    _statement.bindString(_argIndex, _item);
                    _argIndex++;
                }
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<McpToolEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapTool(_cursor));
                    }
                    return _result;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }

    @Override
    public Flow<List<McpToolEntity>> observeAllTools() {
        final String _sql = "SELECT * FROM mcp_tools ORDER BY prefixedName ASC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"mcp_tools"}, new Callable<List<McpToolEntity>>() {
            @Override
            public List<McpToolEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<McpToolEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapTool(_cursor));
                    }
                    return _result;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        });
    }

    @Override
    public Object insertTool(final McpToolEntity t, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __toolInsertion.insert(t);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object updateTool(final McpToolEntity t, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __toolUpdate.handle(t);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object deleteToolsForServer(final String serverId, final Continuation<? super Unit> continuation) {
        final String _sql = "DELETE FROM mcp_tools WHERE serverId = ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindString(1, serverId);
                __db.beginTransaction();
                try {
                    _stmt.execute();
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }
}
