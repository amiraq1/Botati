package com.botati.data.db;

import android.database.Cursor;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/** Hand-written equivalent of Room-generated code (see manual-room-impl/README.md). */
public final class TaskDao_Impl implements TaskDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<TaskSessionEntity> __insertionAdapter;

    public TaskDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<TaskSessionEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `task_sessions` (`id`,`botId`,`conversationId`,`instruction`,`contractJson`,`contractHash`,`status`,`stepsUsed`,`costUsed`,`lastHeartbeatAt`,`finalReport`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, TaskSessionEntity entity) {
                statement.bindString(1, entity.getId());
                statement.bindString(2, entity.getBotId());
                statement.bindString(3, entity.getConversationId());
                statement.bindString(4, entity.getInstruction());
                if (entity.getContractJson() == null) {
                    statement.bindNull(5);
                } else {
                    statement.bindString(5, entity.getContractJson());
                }
                if (entity.getContractHash() == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindString(6, entity.getContractHash());
                }
                statement.bindString(7, entity.getStatus());
                statement.bindLong(8, entity.getStepsUsed());
                statement.bindDouble(9, entity.getCostUsed());
                statement.bindLong(10, entity.getLastHeartbeatAt());
                if (entity.getFinalReport() == null) {
                    statement.bindNull(11);
                } else {
                    statement.bindString(11, entity.getFinalReport());
                }
                statement.bindLong(12, entity.getCreatedAt());
            }
        };
    }

    static TaskSessionEntity __mapRow(Cursor c) {
        final int _contractJson = CursorUtil.getColumnIndexOrThrow(c, "contractJson");
        final int _contractHash = CursorUtil.getColumnIndexOrThrow(c, "contractHash");
        final int _finalReport = CursorUtil.getColumnIndexOrThrow(c, "finalReport");
        return new TaskSessionEntity(
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "conversationId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "instruction")),
                c.isNull(_contractJson) ? null : c.getString(_contractJson),
                c.isNull(_contractHash) ? null : c.getString(_contractHash),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "status")),
                c.getInt(CursorUtil.getColumnIndexOrThrow(c, "stepsUsed")),
                c.getDouble(CursorUtil.getColumnIndexOrThrow(c, "costUsed")),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "lastHeartbeatAt")),
                c.isNull(_finalReport) ? null : c.getString(_finalReport),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "createdAt")));
    }

    @Override
    public Flow<List<TaskSessionEntity>> observeAll() {
        final String _sql = "SELECT * FROM task_sessions ORDER BY createdAt DESC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"task_sessions"}, new Callable<List<TaskSessionEntity>>() {
            @Override
            public List<TaskSessionEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<TaskSessionEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapRow(_cursor));
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
    public Object byId(final String id, final Continuation<? super TaskSessionEntity> continuation) {
        final String _sql = "SELECT * FROM task_sessions WHERE id = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<TaskSessionEntity>() {
            @Override
            public TaskSessionEntity call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, id);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    return _cursor.moveToFirst() ? __mapRow(_cursor) : null;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }

    @Override
    public Object upsert(final TaskSessionEntity t, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(t);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object setStatus(final String id, final String status, final long hb, final Continuation<? super Unit> continuation) {
        final String _sql = "UPDATE task_sessions SET status = ?, lastHeartbeatAt = ? WHERE id = ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindString(1, status);
                _stmt.bindLong(2, hb);
                _stmt.bindString(3, id);
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
    public Object heartbeat(final String id, final long hb, final Continuation<? super Unit> continuation) {
        final String _sql = "UPDATE task_sessions SET lastHeartbeatAt = ? WHERE id = ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindLong(1, hb);
                _stmt.bindString(2, id);
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
    public Object interruptOrphans(final long processStartMs, final String report, final Continuation<? super Integer> continuation) {
        final String _sql = "UPDATE task_sessions SET status = 'INTERRUPTED', finalReport = ? WHERE status = 'RUNNING' AND lastHeartbeatAt < ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindString(1, report);
                _stmt.bindLong(2, processStartMs);
                __db.beginTransaction();
                try {
                    final int _result = _stmt.executeUpdateDelete();
                    __db.setTransactionSuccessful();
                    return _result;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }
}
