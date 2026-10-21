package com.botati.data.db;

import android.database.Cursor;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
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
public final class BotDao_Impl implements BotDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<BotEntity> __insertionAdapterOfBotEntity;
    private final EntityDeletionOrUpdateAdapter<BotEntity> __deletionAdapterOfBotEntity;

    public BotDao_Impl(RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapterOfBotEntity = new EntityInsertionAdapter<BotEntity>(__db) {
            @Override
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `bots` (`id`,`name`,`icon`,`systemPrompt`,`provider`,`model`,`temperature`,`autonomyMode`,`allowedToolsJson`,`mcpServerIdsJson`,`safTreeUrisJson`,`maxStepsOverride`,`budgetOverride`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, BotEntity entity) {
                statement.bindString(1, entity.getId());
                statement.bindString(2, entity.getName());
                statement.bindString(3, entity.getIcon());
                statement.bindString(4, entity.getSystemPrompt());
                statement.bindString(5, entity.getProvider());
                statement.bindString(6, entity.getModel());
                statement.bindDouble(7, entity.getTemperature());
                statement.bindString(8, entity.getAutonomyMode());
                statement.bindString(9, entity.getAllowedToolsJson());
                statement.bindString(10, entity.getMcpServerIdsJson());
                statement.bindString(11, entity.getSafTreeUrisJson());
                if (entity.getMaxStepsOverride() == null) {
                    statement.bindNull(12);
                } else {
                    statement.bindLong(12, entity.getMaxStepsOverride());
                }
                if (entity.getBudgetOverride() == null) {
                    statement.bindNull(13);
                } else {
                    statement.bindDouble(13, entity.getBudgetOverride());
                }
                statement.bindLong(14, entity.getCreatedAt());
            }
        };
        this.__deletionAdapterOfBotEntity = new EntityDeletionOrUpdateAdapter<BotEntity>(__db) {
            @Override
            protected String createQuery() {
                return "DELETE FROM `bots` WHERE `id` = ?";
            }

            @Override
            protected void bind(SupportSQLiteStatement statement, BotEntity entity) {
                statement.bindString(1, entity.getId());
            }
        };
    }

    static BotEntity __mapRow(Cursor _cursor) {
        final int _id = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
        final int _name = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
        final int _icon = CursorUtil.getColumnIndexOrThrow(_cursor, "icon");
        final int _systemPrompt = CursorUtil.getColumnIndexOrThrow(_cursor, "systemPrompt");
        final int _provider = CursorUtil.getColumnIndexOrThrow(_cursor, "provider");
        final int _model = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
        final int _temperature = CursorUtil.getColumnIndexOrThrow(_cursor, "temperature");
        final int _autonomyMode = CursorUtil.getColumnIndexOrThrow(_cursor, "autonomyMode");
        final int _allowedToolsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "allowedToolsJson");
        final int _mcpServerIdsJson = CursorUtil.getColumnIndexOrThrow(_cursor, "mcpServerIdsJson");
        final int _safTreeUrisJson = CursorUtil.getColumnIndexOrThrow(_cursor, "safTreeUrisJson");
        final int _maxStepsOverride = CursorUtil.getColumnIndexOrThrow(_cursor, "maxStepsOverride");
        final int _budgetOverride = CursorUtil.getColumnIndexOrThrow(_cursor, "budgetOverride");
        final int _createdAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
        return __mapRowAt(_cursor, _id, _name, _icon, _systemPrompt, _provider, _model, _temperature,
                _autonomyMode, _allowedToolsJson, _mcpServerIdsJson, _safTreeUrisJson,
                _maxStepsOverride, _budgetOverride, _createdAt);
    }

    static BotEntity __mapRowAt(Cursor c, int id, int name, int icon, int systemPrompt, int provider,
                                int model, int temperature, int autonomyMode, int allowedToolsJson,
                                int mcpServerIdsJson, int safTreeUrisJson, int maxStepsOverride,
                                int budgetOverride, int createdAt) {
        final Integer _maxSteps = c.isNull(maxStepsOverride) ? null : c.getInt(maxStepsOverride);
        final Double _budget = c.isNull(budgetOverride) ? null : c.getDouble(budgetOverride);
        return new BotEntity(
                c.getString(id), c.getString(name), c.getString(icon), c.getString(systemPrompt),
                c.getString(provider), c.getString(model), c.getDouble(temperature),
                c.getString(autonomyMode), c.getString(allowedToolsJson), c.getString(mcpServerIdsJson),
                c.getString(safTreeUrisJson), _maxSteps, _budget, c.getLong(createdAt));
    }

    @Override
    public Flow<List<BotEntity>> observeAll() {
        final String _sql = "SELECT * FROM bots ORDER BY createdAt ASC";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"bots"}, new Callable<List<BotEntity>>() {
            @Override
            public List<BotEntity> call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    final List<BotEntity> _result = new ArrayList<>(_cursor.getCount());
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
    public Object byId(final String id, final Continuation<? super BotEntity> continuation) {
        final String _sql = "SELECT * FROM bots WHERE id = ?";
        return CoroutinesRoom.execute(__db, false, new Callable<BotEntity>() {
            @Override
            public BotEntity call() throws Exception {
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
    public Flow<BotEntity> observeById(final String id) {
        final String _sql = "SELECT * FROM bots WHERE id = ?";
        return CoroutinesRoom.createFlow(__db, false, new String[]{"bots"}, new Callable<BotEntity>() {
            @Override
            public BotEntity call() throws Exception {
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
        });
    }

    @Override
    public Object upsert(final BotEntity bot, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __insertionAdapterOfBotEntity.insert(bot);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object delete(final BotEntity bot, final Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
            @Override
            public Unit call() throws Exception {
                __db.beginTransaction();
                try {
                    __deletionAdapterOfBotEntity.handle(bot);
                    __db.setTransactionSuccessful();
                    return Unit.INSTANCE;
                } finally {
                    __db.endTransaction();
                }
            }
        }, continuation);
    }

    @Override
    public Object count(final Continuation<? super Integer> continuation) {
        final String _sql = "SELECT COUNT(*) FROM bots";
        return CoroutinesRoom.execute(__db, false, new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
                final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
                try {
                    return _cursor.moveToFirst() ? _cursor.getInt(0) : 0;
                } finally {
                    _cursor.close();
                    _statement.release();
                }
            }
        }, continuation);
    }
}
