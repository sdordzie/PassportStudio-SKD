package com.bigsammy.sammessagescheduler.scheduling
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bigsammy.sammessagescheduler.data.AppDatabase
class ReminderWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
 override suspend fun doWork(): Result {
  val id=inputData.getLong("message_id",-1L)
  val item=AppDatabase.get(applicationContext).messageDao().getById(id) ?: return Result.success()
  if(item.status!="SCHEDULED") return Result.success()
  val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  val channel="sam_scheduled_messages"
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channel,"Scheduled messages",NotificationManager.IMPORTANCE_HIGH))
  val content="To: ${item.recipient}\n${item.body.take(120)}"
  val notification=NotificationCompat.Builder(applicationContext,channel)
   .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Time to send your ${item.channel} message")
   .setStyle(NotificationCompat.BigTextStyle().bigText(content)).setContentText(content).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
  manager.notify(id.toInt(),notification)
  return Result.success()
 }
}