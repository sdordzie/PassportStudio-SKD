package com.bigsammy.sammessagescheduler.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable audit record for one SMS submission or carrier callback.
 * The unique key makes callback processing idempotent across receiver re-deliveries.
 */
@Entity(
    tableName = "message_attempts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["messageId", "attemptId", "eventType", "partIndex"], unique = true),
        Index(value = ["occurredAt"])
    ]
)
data class MessageAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val attemptId: String,
    val recipient: String,
    val channel: String = "SMS",
    val eventType: String,
    val partIndex: Int = -1,
    val partCount: Int = 1,
    val outcome: String,
    val resultCode: Int = 0,
    val detail: String = "",
    val occurredAt: Long = System.currentTimeMillis()
)
