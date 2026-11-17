package com.bigsammy.sammessagescheduler.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao interface MessageDao {
 @Insert suspend fun insert(message: MessageEntity): Long
 @Update suspend fun update(message: MessageEntity)
 @Query("SELECT * FROM scheduled_messages ORDER BY scheduledAt ASC") fun observeAll(): Flow<List<MessageEntity>>
 @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1") suspend fun getById(id: Long): MessageEntity?
 @Query("SELECT * FROM scheduled_messages ORDER BY createdAt ASC") suspend fun getAllSnapshot(): List<MessageEntity>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(messages: List<MessageEntity>)
 @Query("DELETE FROM scheduled_messages WHERE id = :id") suspend fun delete(id: Long)
}