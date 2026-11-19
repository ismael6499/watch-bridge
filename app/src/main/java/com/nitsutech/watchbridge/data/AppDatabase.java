package com.nitsutech.watchbridge.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(
        entities = {AppRuleEntity.class, SenderRuleEntity.class, ChatMessageEntity.class},
        version = 5,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public static final String PKG_TEAMS = "com.microsoft.teams";
    public static final String PKG_WHATSAPP = "com.whatsapp";
    public static final String PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b";

    private static volatile AppDatabase instance;
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();

    public abstract AppRuleDao appRuleDao();

    public abstract SenderRuleDao senderRuleDao();

    public abstract ChatMessageDao chatMessageDao();

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("UPDATE app_rules SET directCooldownSec = 0, groupCooldownSec = 0");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN isAfterHoursMuted INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentWatchMode INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentStartHour INTEGER NOT NULL DEFAULT 9");
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentStartMin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentEndHour INTEGER NOT NULL DEFAULT 18");
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentEndMin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_rules ADD COLUMN silentDaysMask INTEGER NOT NULL DEFAULT 127");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentWatchMode INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentStartHour INTEGER NOT NULL DEFAULT 9");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentStartMin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentEndHour INTEGER NOT NULL DEFAULT 18");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentEndMin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sender_rules ADD COLUMN silentDaysMask INTEGER NOT NULL DEFAULT 127");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "watchbridge.db"
                            )
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }

    public ExecutorService executor() {
        return DB_EXECUTOR;
    }

    public void ensureDefaultsAsync() {
        DB_EXECUTOR.execute(this::ensureDefaultsSync);
    }

    public void ensureDefaultsSync() {
        AppRuleDao dao = appRuleDao();
        dao.insertIfMissing(new AppRuleEntity(
                PKG_TEAMS,
                "Microsoft Teams",
                true,
                true, true, 12, 0,
                true, false, 20, 0,
                0,
                0
        ));
        dao.insertIfMissing(new AppRuleEntity(
                PKG_WHATSAPP,
                "WhatsApp",
                true,
                true, true, 12, 0,
                true, false, 25, 0,
                0,
                1
        ));
        dao.insertIfMissing(new AppRuleEntity(
                PKG_WHATSAPP_BUSINESS,
                "WhatsApp Business",
                false,
                true, true, 15, 0,
                true, false, 25, 0,
                0,
                1
        ));
    }
}
