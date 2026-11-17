package com.bigsammy.sammessagescheduler.scheduling

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object MessageScheduler {
    private const val TAG = "SAMMessageScheduler"

    fun schedule(context: Context, id: Long, at: Long) {
        // Scheduling is a background capability; failures must not crash the launcher UI.
        runCatching {
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("message_id" to id))
                .addTag("sam_message_$id")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "sam_message_$id",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }.onFailure { Log.e(TAG, "Unable to schedule message $id", it) }
    }

    fun cancel(context: Context, id: Long) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("sam_message_$id")
        }.onFailure { Log.e(TAG, "Unable to cancel message $id", it) }
    }
}
