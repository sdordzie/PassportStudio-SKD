package com.bigsammy.sammessagescheduler.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM saved_contacts ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM saved_contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSnapshot(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("DELETE FROM saved_contacts WHERE id = :id")
    suspend fun delete(id: Long)
}
