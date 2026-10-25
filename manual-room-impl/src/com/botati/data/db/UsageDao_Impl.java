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
public final class UsageDao_Impl implements UsageDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<UsageRecordEntity> __insertionAdapter;

    public UsageDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<UsageRecordEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `usage_records` (`id`,`botId`,`taskId`,`model`,`promptTokens`,`completionTokens`,`costUsd`,`costSource`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, UsageRecordEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getBotId());
                if (entity.getTaskId() == null) {
                    statement.bindNull(3);
                } else {
                    statement.bindString(3, entity.getTaskId());
                }
                statement.bindString(4, entity.getModel());
                statement.bindLong(5, entity.getPromptTokens());
                statement.bindLong(6, entity.getCompletionTokens());
                if (entity.getCostUsd() == null) {
                    statement.bindNull(7);
                } else {
                    statement.bindDouble(7, entity.getCostUsd());
                }
                statement.bindString(8, entity.getCostSource());
                statement.bindLong(9, entity.getTimestamp());
            }
        };
    }

    static UsageRecordEntity __mapRow(Cursor c) {
        final int _taskId = CursorUtil.getColumnIndexOrThrow(c, "taskId");
        final int _costUsd = CursorUtil.getColumnIndexOrThrow(c, "costUsd");
        return new UsageRecordEntity(
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.isNull(_taskId) ? null : c.getString(_taskId),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "model")),
                c.getInt(CursorUtil.getColumnIndexOrThrow(c, "promptTokens")),
                c.getInt(CursorUtil.getColumnIndexOrThrow(c, "completionTokens")),
                c.isNull(_costUsd) ? null : c.getDouble(_costUsd),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "costSource")),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "timestamp")));
    }

    @Override
    public Object insert(final UsageRecordEntity u, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(u);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Flow<Double> observeCostSince(final String botId, final long sinceMs) {
        final String _sql = "SELECT COALESCE(SUM(costUsd), 0) FROM usage_records WHERE botId = ? AND timestamp >= ?";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"usage_records"}, new Callable<Double>() {
            @Override
            public Double call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
                _statement.bindString(1, botId);
                _statement.bindLong(2, sinceMs);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    return _cursor.moveToFirst() ? _cursor.getDouble(0) : 0.0;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        });
    }

    @Override
    public Flow<List<UsageRecordEntity>> observeForBot(final String botId) {
        final String _sql = "SELECT * FROM usage_records WHERE botId = ? ORDER BY id DESC LIMIT 100";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"usage_records"}, new Callable<List<UsageRecordEntity>>() {
            @Override
            public List<UsageRecordEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<UsageRecordEntity> _result = new ArrayList<>(_cursor.getCount());
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
}
