package com.bigsammy.sammessagescheduler.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bigsammy.sammessagescheduler.data.AppDatabase
import java.net.URLEncoder
import java.util.Calendar

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("message_id", -1L)
        val dao = AppDatabase.get(applicationContext).messageDao()
        val item = dao.getById(id) ?: return Result.success()
        if (item.status != "SCHEDULED") return Result.success()

        val quietPrefs = applicationContext.getSharedPreferences("sam_quiet_hours", Context.MODE_PRIVATE)
        if (quietPrefs.getBoolean("enabled", false)) {
            val start = quietPrefs.getString("start", "22:00") ?: "22:00"
            val end = quietPrefs.getString("end", "07:00") ?: "07:00"
            val deferredUntil = quietHoursEnd(System.currentTimeMillis(), start, end)
            if (deferredUntil != null) {
                MessageScheduler.schedule(applicationContext, id, deferredUntil)
                postNotification(id, "Message postponed by quiet hours", "SAM will process this message after quiet hours end.")
                return Result.success()
            }
        }

        return try {
            if (item.channel == "SMS") {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    dao.update(item.copy(status = "PERMISSION_REQUIRED"))
                    postNotification(id, "SMS permission required", "Open SAM and grant SMS permission to schedule SMS sending.")
                    return Result.failure()
                }
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    applicationContext.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = sms.divideMessage(item.body)
                if (parts.size > 1) sms.sendMultipartTextMessage(item.recipient, null, parts, null, null)
                else sms.sendTextMessage(item.recipient, null, item.body, null, null)

                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                    postNotification(id, "Recurring SMS submitted", "Your SMS was submitted to Android's messaging service for ${item.recipient}.")
                } else {
                    dao.update(item.copy(status = "SENT"))
                    postNotification(id, "SMS submitted", "Your SMS was submitted to Android's messaging service for ${item.recipient}.")
                }
            } else {
                val phone = item.recipient.filter { it.isDigit() }
                val encoded = URLEncoder.encode(item.body, "UTF-8")
                val uri = Uri.parse("https://wa.me/$phone?text=$encoded")
                val openChat = PendingIntent.getActivity(
                    applicationContext, id.toInt(),
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                } else {
                    dao.update(item.copy(status = "AWAITING_USER"))
                }
                postNotification(id, "Your WhatsApp message is ready", "Tap to open WhatsApp with the recipient and message prepared. Review it and tap Send.", openChat)
            }
            Result.success()
        } catch (e: SecurityException) {
            dao.update(item.copy(status = "PERMISSION_REQUIRED"))
            postNotification(id, "Permission required", "SAM could not send this SMS. Check SMS permission in Android settings.")
            Result.failure()
        } catch (e: Exception) {
            dao.update(item.copy(status = "FAILED"))
            postNotification(id, "Message action failed", e.message ?: "SAM could not process this scheduled message.")
            Result.failure()
        }
    }


    private fun quietHoursEnd(now: Long, start: String, end: String): Long? {
        return try {
            val startParts = start.split(":").map { it.toInt() }
            val endParts = end.split(":").map { it.toInt() }
            if (startParts.size != 2 || endParts.size != 2) return null
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val minuteNow = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val startMinute = startParts[0] * 60 + startParts[1]
            val endMinute = endParts[0] * 60 + endParts[1]
            val inside = if (startMinute < endMinute) minuteNow >= startMinute && minuteNow < endMinute
                else minuteNow >= startMinute || minuteNow < endMinute
            if (!inside) return null
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.set(Calendar.HOUR_OF_DAY, endParts[0])
            calendar.set(Calendar.MINUTE, endParts[1])
            if (startMinute > endMinute && minuteNow >= startMinute) calendar.add(Calendar.DAY_OF_YEAR, 1)
            if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
            calendar.timeInMillis
        } catch (_: Exception) { null }
    }

    private fun nextOccurrence(previous: Long, rule: String): Long? {
        val field = when (rule) {
            "DAILY" -> Calendar.DAY_OF_MONTH
            "WEEKLY" -> Calendar.WEEK_OF_YEAR
            "MONTHLY" -> Calendar.MONTH
            else -> return null
        }
        return Calendar.getInstance().apply {
            timeInMillis = previous
            add(field, 1)
        }.timeInMillis
    }

    private fun postNotification(id: Long, title: String, text: String, action: PendingIntent? = null) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sam_scheduled_messages"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Scheduled messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (action != null) builder.setContentIntent(action)
        manager.notify(id.toInt(), builder.build())
    }
}
