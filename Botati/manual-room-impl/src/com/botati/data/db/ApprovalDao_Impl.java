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
public final class ApprovalDao_Impl implements ApprovalDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<ApprovalEntity> __insertionAdapter;

    public ApprovalDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<ApprovalEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `approvals` (`id`,`botId`,`approvalKey`,`contractHash`,`grantedAt`,`note`) VALUES (nullif(?, 0),?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, ApprovalEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getBotId());
                statement.bindString(3, entity.getApprovalKey());
                if (entity.getContractHash() == null) {
                    statement.bindNull(4);
                } else {
                    statement.bindString(4, entity.getContractHash());
                }
                statement.bindLong(5, entity.getGrantedAt());
                statement.bindString(6, entity.getNote());
            }
        };
    }

    static ApprovalEntity __mapRow(Cursor c) {
        final int _contractHash = CursorUtil.getColumnIndexOrThrow(c, "contractHash");
        return new ApprovalEntity(
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "approvalKey")),
                c.isNull(_contractHash) ? null : c.getString(_contractHash),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "grantedAt")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "note")));
    }

    @Override
    public Object insert(final ApprovalEntity a, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(a);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object keysForBot(final String botId, final Continuation<? super List<String>> continuation) {
        final String _sql = "SELECT approvalKey FROM approvals WHERE botId = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<String> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(_cursor.getString(0));
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
    public Flow<List<ApprovalEntity>> observeAll() {
        final String _sql = "SELECT * FROM approvals ORDER BY id DESC LIMIT 200";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"approvals"}, new Callable<List<ApprovalEntity>>() {
            @Override
            public List<ApprovalEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<ApprovalEntity> _result = new ArrayList<>(_cursor.getCount());
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
