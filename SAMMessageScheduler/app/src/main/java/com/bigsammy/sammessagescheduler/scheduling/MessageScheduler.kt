package com.bigsammy.sammessagescheduler.scheduling

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object MessageScheduler {
    fun schedule(context: Context, id: Long, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("message_id" to id))
            .addTag("sam_message_$id")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sam_message_$id",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag("sam_message_$id")
    }
}
