package com.bigsammy.sammessagescheduler.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipient: String,
    val body: String,
    val channel: String,
    val scheduledAt: Long,
    val status: String = "SCHEDULED",
    val repeatRule: String = "NONE",
    val createdAt: Long = System.currentTimeMillis(),
    val deliveryStatus: String = "PENDING",
    val category: String = "General",
    val attachmentsJson: String = "[]",
    val triggerType: String = "TIME",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 150f,
    val locationLabel: String = ""
)
