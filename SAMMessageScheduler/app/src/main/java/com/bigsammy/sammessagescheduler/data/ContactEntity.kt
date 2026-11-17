package com.bigsammy.sammessagescheduler.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val groupName: String = "General",
    val birthday: String = "",
    val anniversary: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
