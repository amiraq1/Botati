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
public final class ConversationDao_Impl implements ConversationDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<ConversationEntity> __insertionAdapter;

    public ConversationDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<ConversationEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `conversations` (`id`,`botId`,`title`,`createdAt`) VALUES (?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, ConversationEntity entity) {
                statement.bindString(1, entity.getId());
                statement.bindString(2, entity.getBotId());
                statement.bindString(3, entity.getTitle());
                statement.bindLong(4, entity.getCreatedAt());
            }
        };
    }

    static ConversationEntity __mapRow(Cursor c) {
        return new ConversationEntity(
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "title")),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "createdAt")));
    }

    @Override
    public Flow<List<ConversationEntity>> observeForBot(final String botId) {
        final String _sql = "SELECT * FROM conversations WHERE botId = ? ORDER BY createdAt DESC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"conversations"}, new Callable<List<ConversationEntity>>() {
            @Override
            public List<ConversationEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<ConversationEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object listForBot(final String botId, final Continuation<? super List<ConversationEntity>> continuation) {
        final String _sql = "SELECT * FROM conversations WHERE botId = ? ORDER BY createdAt DESC";
        return CoroutinesRoom.execute(__db, false, new Callable<List<ConversationEntity>>() {
            @Override
            public List<ConversationEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<ConversationEntity> _result = new ArrayList<>(_cursor.getCount());
                    while (_cursor.moveToNext()) {
                        _result.add(__mapRow(_cursor));
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
    public Object upsert(final ConversationEntity c, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(c);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object byId(final String id, final Continuation<? super ConversationEntity> continuation) {
        final String _sql = "SELECT * FROM conversations WHERE id = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<ConversationEntity>() {
            @Override
            public ConversationEntity call() throws Exception {
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
}
