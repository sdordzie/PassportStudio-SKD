package com.bigsammy.sammessagescheduler.scheduling
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
object MessageScheduler {
 fun schedule(context: Context,id: Long,at: Long) {
  val request=OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay((at-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
   .setInputData(workDataOf("message_id" to id)).build()
  WorkManager.getInstance(context).enqueueUniqueWork("sam_message_$id",ExistingWorkPolicy.REPLACE,request)
 }
 fun cancel(context: Context,id: Long) { WorkManager.getInstance(context).cancelUniqueWork("sam_message_$id") }
}