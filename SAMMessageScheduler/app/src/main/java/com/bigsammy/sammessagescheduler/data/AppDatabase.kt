package com.bigsammy.sammessagescheduler.data
import android.content.Context
import androidx.room.*
@Database(entities=[MessageEntity::class], version=1, exportSchema=false)
abstract class AppDatabase: RoomDatabase() {
 abstract fun messageDao(): MessageDao
 companion object {
  @Volatile private var INSTANCE: AppDatabase? = null
  fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
   INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sam_message_scheduler.db").build().also { INSTANCE=it }
  }
 }
}