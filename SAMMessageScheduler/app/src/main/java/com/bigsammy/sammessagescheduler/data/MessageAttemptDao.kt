package com.bigsammy.sammessagescheduler.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttemptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attempt: MessageAttemptEntity): Long

    @Query("SELECT * FROM message_attempts ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<MessageAttemptEntity>>

    @Query("SELECT * FROM message_attempts WHERE messageId = :messageId ORDER BY occurredAt DESC, id DESC")
    fun observeForMessage(messageId: Long): Flow<List<MessageAttemptEntity>>

    @Query("SELECT * FROM message_attempts ORDER BY occurredAt ASC, id ASC")
    suspend fun getAllSnapshot(): List<MessageAttemptEntity>
}
