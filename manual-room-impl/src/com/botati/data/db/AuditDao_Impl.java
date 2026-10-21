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
public final class AuditDao_Impl implements AuditDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<AuditEntryEntity> __insertionAdapter;

    public AuditDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<AuditEntryEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `audit_entries` (`id`,`taskId`,`botId`,`tool`,`target`,`effect`,`disclosure`,`decision`,`reasonCode`,`argsDigest`,`resultSummary`,`idempotencyKey`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, AuditEntryEntity entity) {
                statement.bindLong(1, entity.getId());
                if (entity.getTaskId() == null) {
                    statement.bindNull(2);
                } else {
                    statement.bindString(2, entity.getTaskId());
                }
                statement.bindString(3, entity.getBotId());
                statement.bindString(4, entity.getTool());
                if (entity.getTarget() == null) {
                    statement.bindNull(5);
                } else {
                    statement.bindString(5, entity.getTarget());
                }
                statement.bindString(6, entity.getEffect());
                statement.bindString(7, entity.getDisclosure());
                statement.bindString(8, entity.getDecision());
                statement.bindString(9, entity.getReasonCode());
                statement.bindString(10, entity.getArgsDigest());
                if (entity.getResultSummary() == null) {
                    statement.bindNull(11);
                } else {
                    statement.bindString(11, entity.getResultSummary());
                }
                if (entity.getIdempotencyKey() == null) {
                    statement.bindNull(12);
                } else {
                    statement.bindString(12, entity.getIdempotencyKey());
                }
                statement.bindLong(13, entity.getTimestamp());
            }
        };
    }

    static AuditEntryEntity __mapRow(Cursor c) {
        final int _taskId = CursorUtil.getColumnIndexOrThrow(c, "taskId");
        final int _target = CursorUtil.getColumnIndexOrThrow(c, "target");
        final int _resultSummary = CursorUtil.getColumnIndexOrThrow(c, "resultSummary");
        final int _idempotencyKey = CursorUtil.getColumnIndexOrThrow(c, "idempotencyKey");
        return new AuditEntryEntity(
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.isNull(_taskId) ? null : c.getString(_taskId),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "tool")),
                c.isNull(_target) ? null : c.getString(_target),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "effect")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "disclosure")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "decision")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "reasonCode")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "argsDigest")),
                c.isNull(_resultSummary) ? null : c.getString(_resultSummary),
                c.isNull(_idempotencyKey) ? null : c.getString(_idempotencyKey),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "timestamp")));
    }

    @Override
    public Flow<List<AuditEntryEntity>> observeRecent() {
        final String _sql = "SELECT * FROM audit_entries ORDER BY id DESC LIMIT 500";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"audit_entries"}, new Callable<List<AuditEntryEntity>>() {
            @Override
            public List<AuditEntryEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<AuditEntryEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Flow<List<AuditEntryEntity>> observeForBot(final String botId) {
        final String _sql = "SELECT * FROM audit_entries WHERE botId = ? ORDER BY id DESC LIMIT 500";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"audit_entries"}, new Callable<List<AuditEntryEntity>>() {
            @Override
            public List<AuditEntryEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<AuditEntryEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object insert(final AuditEntryEntity e, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(e);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }
}
