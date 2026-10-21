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

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/** Hand-written equivalent of Room-generated code (see manual-room-impl/README.md). */
public final class MessageDao_Impl implements MessageDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<MessageEntity> __insertionAdapter;

    public MessageDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<MessageEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `messages` (`id`,`conversationId`,`role`,`content`,`toolCallsJson`,`toolCallId`,`toolName`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, MessageEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getConversationId());
                statement.bindString(3, entity.getRole());
                statement.bindString(4, entity.getContent());
                if (entity.getToolCallsJson() == null) {
                    statement.bindNull(5);
                } else {
                    statement.bindString(5, entity.getToolCallsJson());
                }
                if (entity.getToolCallId() == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindString(6, entity.getToolCallId());
                }
                if (entity.getToolName() == null) {
                    statement.bindNull(7);
                } else {
                    statement.bindString(7, entity.getToolName());
                }
                statement.bindLong(8, entity.getCreatedAt());
            }
        };
    }

    static MessageEntity __mapRow(Cursor c) {
        final int _id = CursorUtil.getColumnIndexOrThrow(c, "id");
        final int _toolCallsJson = CursorUtil.getColumnIndexOrThrow(c, "toolCallsJson");
        final int _toolCallId = CursorUtil.getColumnIndexOrThrow(c, "toolCallId");
        final int _toolName = CursorUtil.getColumnIndexOrThrow(c, "toolName");
        return new MessageEntity(
                c.getLong(_id),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "conversationId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "role")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "content")),
                c.isNull(_toolCallsJson) ? null : c.getString(_toolCallsJson),
                c.isNull(_toolCallId) ? null : c.getString(_toolCallId),
                c.isNull(_toolName) ? null : c.getString(_toolName),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "createdAt")));
    }

    @Override
    public Flow<List<MessageEntity>> observeForConversation(final String cid) {
        final String _sql = "SELECT * FROM messages WHERE conversationId = ? ORDER BY id ASC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"messages"}, new Callable<List<MessageEntity>>() {
            @Override
            public List<MessageEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, cid);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<MessageEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object forConversation(final String cid, final Continuation<? super List<MessageEntity>> continuation) {
        final String _sql = "SELECT * FROM messages WHERE conversationId = ? ORDER BY id ASC";
        return CoroutinesRoom.execute(__db, false, new Callable<List<MessageEntity>>() {
            @Override
            public List<MessageEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, cid);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<MessageEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object insert(final MessageEntity m, final Continuation<? super Long> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                __db.beginTransaction();
                try {
                    final long _result = __insertionAdapter.insertAndReturnId(m);
                    __db.setTransactionSuccessful();
                    return _result;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }
}
