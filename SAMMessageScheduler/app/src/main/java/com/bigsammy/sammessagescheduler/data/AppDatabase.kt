package com.bigsammy.sammessagescheduler.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class, ContactEntity::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN repeatRule TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_contacts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, groupName TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }


        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_contacts ADD COLUMN birthday TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE saved_contacts ADD COLUMN anniversary TEXT NOT NULL DEFAULT ''")
            }
        }


        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN category TEXT NOT NULL DEFAULT 'General'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN triggerType TEXT NOT NULL DEFAULT 'TIME'")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN radiusMeters REAL NOT NULL DEFAULT 150")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN locationLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN whatsappPackage TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN campaignId TEXT")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sam_message_scheduler.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { INSTANCE = it }
        }
    }
}
