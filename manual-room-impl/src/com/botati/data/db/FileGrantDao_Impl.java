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
public final class FileGrantDao_Impl implements FileGrantDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<FileGrantEntity> __insertionAdapter;

    public FileGrantDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapter = new EntityInsertionAdapter<FileGrantEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR ABORT INTO `file_grants` (`id`,`botId`,`treeUri`,`grantedAt`) VALUES (nullif(?, 0),?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, FileGrantEntity entity) {
                statement.bindLong(1, entity.getId());
                statement.bindString(2, entity.getBotId());
                statement.bindString(3, entity.getTreeUri());
                statement.bindLong(4, entity.getGrantedAt());
            }
        };
    }

    static FileGrantEntity __mapRow(Cursor c) {
        return new FileGrantEntity(
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "id")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "botId")),
                c.getString(CursorUtil.getColumnIndexOrThrow(c, "treeUri")),
                c.getLong(CursorUtil.getColumnIndexOrThrow(c, "grantedAt")));
    }

    @Override
    public Object insert(final FileGrantEntity g, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapter.insert(g);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Flow<List<FileGrantEntity>> observeForBot(final String botId) {
        final String _sql = "SELECT * FROM file_grants WHERE botId = ?";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"file_grants"}, new Callable<List<FileGrantEntity>>() {
            @Override
            public List<FileGrantEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
                _statement.bindString(1, botId);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<FileGrantEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object urisForBot(final String botId, final Continuation<? super List<String>> continuation) {
        final String _sql = "SELECT treeUri FROM file_grants WHERE botId = ?";
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
    public Object revoke(final String botId, final String uri, final Continuation<? super Unit> continuation) {
        final String _sql = "DELETE FROM file_grants WHERE botId = ? AND treeUri = ?";
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                final SupportSQLiteStatement _stmt = __db.getOpenHelper().getWritableDatabase().compileStatement(_sql);
                _stmt.bindString(1, botId);
                _stmt.bindString(2, uri);
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
