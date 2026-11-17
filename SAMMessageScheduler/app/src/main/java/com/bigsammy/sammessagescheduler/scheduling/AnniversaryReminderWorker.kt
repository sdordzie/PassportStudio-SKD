package com.bigsammy.sammessagescheduler.scheduling

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bigsammy.sammessagescheduler.data.AppDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnniversaryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val now = Calendar.getInstance()
            val today = SimpleDateFormat("MM-dd", Locale.US).format(now.time)
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
            val contacts = AppDatabase.get(applicationContext).contactDao().getAllSnapshot()
            val prefs = applicationContext.getSharedPreferences("sam_anniversary_alerts", Context.MODE_PRIVATE)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel("sam_anniversary_reminders", "Birthday and anniversary reminders", NotificationManager.IMPORTANCE_DEFAULT))
            }
            contacts.forEach { contact ->
                val events = listOf("Birthday" to contact.birthday, "Anniversary" to contact.anniversary)
                events.forEach { (label, date) ->
                    if (date == today) {
                        val key = "${contact.id}_${label}_${dateKey}"
                        if (!prefs.getBoolean(key, false)) {
                            val notification = NotificationCompat.Builder(applicationContext, "sam_anniversary_reminders")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("$label reminder: ${contact.name}")
                                .setContentText("Today is ${contact.name}'s $label. Open SAM to prepare a greeting.")
                                .setStyle(NotificationCompat.BigTextStyle().bigText("Today is ${contact.name}'s $label. You can prepare a personalised SMS or WhatsApp greeting in SAM Message Scheduler."))
                                .setAutoCancel(true)
                                .build()
                            NotificationManagerCompat.from(applicationContext).notify((contact.id * 31 + label.hashCode()).toInt(), notification)
                            prefs.edit().putBoolean(key, true).apply()
                        }
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object AnniversaryReminderScheduler {
    fun ensureScheduled(context: Context) {
        val now = Calendar.getInstance()
        val nextRun = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<AnniversaryReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(nextRun.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sam_birthday_anniversary_daily",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
