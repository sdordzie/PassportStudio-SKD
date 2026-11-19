package com.bigsammy.sammessagescheduler.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import com.bigsammy.sammessagescheduler.data.MessageAttemptEntity
import java.net.URLEncoder
import java.util.Calendar
import kotlinx.coroutines.launch


class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("message_id", -1L)
        if (id < 0) return
        val part = intent.getIntExtra("part", 0).coerceAtLeast(0)
        val partCount = intent.getIntExtra("part_count", 1).coerceAtLeast(1)
        val isSentEvent = intent.action == "com.bigsammy.sammessagescheduler.SMS_SENT"
        val isDeliveredEvent = intent.action == "com.bigsammy.sammessagescheduler.SMS_DELIVERED"
        if (!isSentEvent && !isDeliveredEvent) return

        // Older pending intents created before attempt IDs were introduced remain readable.
        val attemptId = intent.getStringExtra("attempt_id")?.takeIf { it.isNotBlank() } ?: "legacy_$id"
        val failed = if (isSentEvent) resultCode != android.app.Activity.RESULT_OK else
            resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE ||
            resultCode == SmsManager.RESULT_ERROR_NO_SERVICE ||
            resultCode == SmsManager.RESULT_ERROR_NULL_PDU ||
            resultCode == SmsManager.RESULT_ERROR_RADIO_OFF
        val eventType = if (isSentEvent) "SENT_CALLBACK" else "DELIVERY_CALLBACK"
        val detail = when {
            !failed && isSentEvent -> "Android accepted this SMS part for transmission."
            !failed -> "The mobile network reported this SMS part as delivered."
            resultCode == SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile network service was available."
            resultCode == SmsManager.RESULT_ERROR_RADIO_OFF -> "The phone radio was switched off."
            resultCode == SmsManager.RESULT_ERROR_NULL_PDU -> "Android could not encode the SMS part."
            resultCode == SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "The SMS sending limit was exceeded."
            resultCode == SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "The number was blocked by fixed-dialling restrictions."
            else -> "Android or the mobile network returned error code $resultCode."
        }

        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val database = AppDatabase.get(context)
                val dao = database.messageDao()
                val attemptDao = database.messageAttemptDao()
                val prefs = context.getSharedPreferences("sam_sms_part_status", Context.MODE_PRIVATE)

                val item = dao.getById(id) ?: return@launch
                attemptDao.insert(
                    com.bigsammy.sammessagescheduler.data.MessageAttemptEntity(
                        messageId = id,
                        attemptId = attemptId,
                        recipient = item.recipient,
                        eventType = eventType,
                        partIndex = part,
                        partCount = partCount,
                        outcome = if (failed) "FAILED" else "SUCCESS",
                        resultCode = resultCode,
                        detail = detail
                    )
                )

                val key = "message_${id}_${attemptId}"
                val aggregateAndCurrent = synchronized(SmsStatusReceiver::class.java) {
                    val state = runCatching { org.json.JSONObject(prefs.getString(key, "{}") ?: "{}") }
                        .getOrDefault(org.json.JSONObject())
                    val eventPrefix = if (isSentEvent) "sent" else "delivered"
                    val events = state.optJSONObject(eventPrefix) ?: org.json.JSONObject()
                    events.put(part.toString(), if (failed) "FAILED" else "OK")
                    state.put(eventPrefix, events).put("count", partCount)
                    prefs.edit().putString(key, state.toString()).apply()

                    val sentEvents = state.optJSONObject("sent") ?: org.json.JSONObject()
                    val deliveredEvents = state.optJSONObject("delivered") ?: org.json.JSONObject()
                    val sentValues = (0 until partCount).mapNotNull { index ->
                        sentEvents.optString(index.toString(), "").takeIf { it.isNotEmpty() }
                    }
                    val deliveredValues = (0 until partCount).mapNotNull { index ->
                        deliveredEvents.optString(index.toString(), "").takeIf { it.isNotEmpty() }
                    }
                    val aggregate = when {
                        deliveredValues.size == partCount && deliveredValues.all { it == "OK" } -> "DELIVERED"
                        deliveredValues.any { it == "FAILED" } -> "DELIVERY_FAILED"
                        sentValues.any { it == "FAILED" } -> "SEND_FAILED"
                        sentValues.size == partCount && sentValues.all { it == "OK" } -> "SENT"
                        sentValues.isNotEmpty() -> "SENDING_PARTS_${sentValues.size}_OF_$partCount"
                        else -> "DELIVERING_PARTS_${deliveredValues.size}_OF_$partCount"
                    }
                    val currentAttempt = prefs.getString("current_$id", null)
                    aggregate to (currentAttempt == null || currentAttempt == attemptId)
                }

                // A late callback from an earlier retry is kept in history but cannot overwrite
                // the status of a newer attempt.
                if (aggregateAndCurrent.second) {
                    val latest = dao.getById(id)
                    if (latest != null) dao.update(latest.copy(deliveryStatus = aggregateAndCurrent.first))
                }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "sam_scheduled_messages"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(NotificationChannel(channelId, "Scheduled messages", NotificationManager.IMPORTANCE_DEFAULT))
                }
                val statusText = if (failed) "part ${part + 1} of $partCount failed" else "part ${part + 1} of $partCount updated"
                manager.notify((id + 500000).toInt(), NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(com.bigsammy.sammessagescheduler.R.drawable.ic_stat_sam)
                    .setContentTitle(if (failed) "SMS delivery problem" else "SMS status update")
                    .setContentText("Recipient: ${item.recipient} · $statusText")
                    .setAutoCancel(true).build())
            } catch (error: Exception) {
                android.util.Log.e("SAMSmsStatus", "Could not save SMS callback for message $id", error)
            } finally {
                pending.finish()
            }
        }
    }
}

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
            val attachmentUris = runCatching {
                org.json.JSONArray(item.attachmentsJson).let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        runCatching { Uri.parse(array.getString(index)) }.getOrNull()
                    }
                }
            }.getOrDefault(emptyList())

            if (attachmentUris.isNotEmpty() && item.channel != "Social") {
                // Media messages use a user-confirmed share flow; Android's public SMS API
                // does not provide a dependable carrier-independent MMS send operation.
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    if ((item.channel == "WhatsApp" || item.channel == "Social") && item.whatsappPackage.isNotBlank()) setPackage(item.whatsappPackage)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
                    putExtra(Intent.EXTRA_TEXT, item.body)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    clipData = android.content.ClipData.newUri(
                        applicationContext.contentResolver, "SAM scheduled attachments", attachmentUris.first()
                    ).also { clip ->
                        attachmentUris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                    }
                }
                val chooser = Intent.createChooser(share, "Send scheduled message").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val openShare = PendingIntent.getActivity(
                    applicationContext, id.toInt(), chooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                } else {
                    dao.update(item.copy(status = "AWAITING_USER"))
                }
                runCatching {
                    postNotification(
                        id, "Your scheduled media message is ready",
                        unlockAwareGuidance("Tap to choose an app and recipient. Review the message and attachments, then tap Send. Recipient: ${item.recipient}."),
                        openShare
                    )
                }.onFailure { android.util.Log.w("SAMReminderWorker", "Media hand-off is ready but its notification could not be shown for message $id", it) }
            } else if (item.channel == "SMS") {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    dao.update(item.copy(status = "PERMISSION_REQUIRED"))
                    postNotification(id, "SMS permission required", "Open SAM and grant SMS permission to schedule SMS sending.")
                    return Result.failure()
                }
                @Suppress("DEPRECATION")
                val subscriptionId = SmsManager.getDefaultSmsSubscriptionId()
                @Suppress("DEPRECATION")
                val sms = if (subscriptionId >= 0) SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applicationContext.getSystemService(SmsManager::class.java)
                    else SmsManager.getDefault()
                val parts = sms.divideMessage(item.body)
                val attemptId = java.util.UUID.randomUUID().toString()
                val attemptPrefs = applicationContext.getSharedPreferences("sam_sms_part_status", Context.MODE_PRIVATE)
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()
                repeat(parts.size) { part ->
                    val sent = Intent(applicationContext, SmsStatusReceiver::class.java)
                        .setAction("com.bigsammy.sammessagescheduler.SMS_SENT")
                        .setData(Uri.parse("sam-sms://message/$id/attempt/$attemptId/sent/$part"))
                        .putExtra("message_id", id).putExtra("attempt_id", attemptId)
                        .putExtra("part", part).putExtra("part_count", parts.size)
                    val delivered = Intent(applicationContext, SmsStatusReceiver::class.java)
                        .setAction("com.bigsammy.sammessagescheduler.SMS_DELIVERED")
                        .setData(Uri.parse("sam-sms://message/$id/attempt/$attemptId/delivered/$part"))
                        .putExtra("message_id", id).putExtra("attempt_id", attemptId)
                        .putExtra("part", part).putExtra("part_count", parts.size)
                    val requestBase = attemptId.hashCode() and 0x3fffffff
                    sentIntents += PendingIntent.getBroadcast(applicationContext, requestBase + part, sent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    deliveredIntents += PendingIntent.getBroadcast(applicationContext, requestBase + 1000 + part, delivered, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                // Every retry/recurrence gets an isolated callback state and token. Late callbacks
                // from a previous attempt therefore cannot contaminate this attempt's aggregate.
                attemptPrefs.edit()
                    .putString("current_$id", attemptId)
                    .putString("message_${id}_${attemptId}", org.json.JSONObject().put("count", parts.size).toString())
                    .apply()
                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED", deliveryStatus = "SUBMITTED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                } else {
                    dao.update(item.copy(status = "SENT", deliveryStatus = "SUBMITTED"))
                }

                // Persist the submitted state before the asynchronous sent/delivered callbacks can arrive.
                try {
                    if (parts.size > 1) sms.sendMultipartTextMessage(item.recipient, null, parts, sentIntents, deliveredIntents)
                    else sms.sendTextMessage(item.recipient, null, item.body, sentIntents.first(), deliveredIntents.first())
                    dao.messageAttemptDao().insert(
                        MessageAttemptEntity(
                            messageId = id,
                            attemptId = attemptId,
                            recipient = item.recipient,
                            eventType = "SUBMISSION",
                            partCount = parts.size,
                            outcome = "SUBMITTED",
                            detail = "Android accepted the SMS request. Carrier delivery is not yet confirmed."
                        )
                    )
                } catch (sendError: Exception) {
                    dao.messageAttemptDao().insert(
                        MessageAttemptEntity(
                            messageId = id,
                            attemptId = attemptId,
                            recipient = item.recipient,
                            eventType = "SUBMISSION",
                            partCount = parts.size,
                            outcome = "FAILED",
                            resultCode = -1,
                            detail = sendError.message ?: "Android could not submit this SMS."
                        )
                    )
                    throw sendError
                }
                // Notification display is optional. A notification failure must not
                // overwrite a successfully submitted SMS with a false FAILED state.
                runCatching {
                    postNotification(id, "SMS submitted", "Android accepted the SMS request for ${item.recipient}. Delivery updates will appear when the network reports them.")
                }.onFailure { android.util.Log.w("SAMReminderWorker", "SMS submitted but notification could not be shown for message $id", it) }
            } else if (item.channel == "Social") {
                // Preserve photo/video attachments for social destinations. The destination app
                // receives read access and the user completes the final review/publish step.
                val share = if (attachmentUris.isNotEmpty()) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
                        clipData = android.content.ClipData.newUri(
                            applicationContext.contentResolver, "SAM social post media", attachmentUris.first()
                        ).also { clip -> attachmentUris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) } }
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
                }.apply {
                    putExtra(Intent.EXTRA_TEXT, item.body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (item.whatsappPackage.isNotBlank()) setPackage(item.whatsappPackage)
                }
                val target = if (share.resolveActivity(applicationContext.packageManager) != null) share
                    else Intent.createChooser(share.apply { setPackage(null) }, "Share social post")
                val openSocial = PendingIntent.getActivity(
                    applicationContext, id.toInt(), target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                } else {
                    dao.update(item.copy(status = "AWAITING_USER"))
                }
                runCatching {
                    postNotification(id, "Your social post is ready", unlockAwareGuidance("Tap to open the selected social app. Review your post and publish it there."), openSocial)
                }.onFailure { android.util.Log.w("SAMReminderWorker", "Social hand-off is ready but its notification could not be shown for message $id", it) }
            } else {
                val phone = item.recipient.filter { it.isDigit() }
                val encoded = URLEncoder.encode(item.body, "UTF-8")
                val uri = Uri.parse("https://wa.me/$phone?text=$encoded")
                val openChat = PendingIntent.getActivity(
                    applicationContext, id.toInt(),
                    Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (item.whatsappPackage.isNotBlank()) setPackage(item.whatsappPackage)
                    }.let { target ->
                        if (item.whatsappPackage.isBlank()) Intent.createChooser(target, "Choose WhatsApp")
                        else target
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val next = nextOccurrence(item.scheduledAt, item.repeatRule)
                if (next != null) {
                    dao.update(item.copy(scheduledAt = next, status = "SCHEDULED"))
                    MessageScheduler.schedule(applicationContext, id, next)
                } else {
                    dao.update(item.copy(status = "AWAITING_USER"))
                }
                runCatching {
                    postNotification(id, "Your WhatsApp message is ready", unlockAwareGuidance("Tap to open WhatsApp with the recipient and message prepared. Review it and tap Send."), openChat)
                }.onFailure { android.util.Log.w("SAMReminderWorker", "WhatsApp hand-off is ready but its notification could not be shown for message $id", it) }
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
            "YEARLY" -> Calendar.YEAR
            else -> return null
        }
        return Calendar.getInstance().apply {
            timeInMillis = previous
            add(field, 1)
        }.timeInMillis
    }

    /**
     * Third-party apps cannot be unlocked or safely driven by SAM. If Android's
     * keyguard is active, keep the prepared action behind a private notification;
     * the user unlocks the device and explicitly taps to continue.
     */
    private fun unlockAwareGuidance(actionText: String): String {
        val keyguard = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val deviceLocked = keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true
        val unlockText = if (deviceLocked) {
            " Your phone is locked. Unlock it first, then tap this notification to continue."
        } else {
            " If the destination app has its own App Lock, unlock it yourself when prompted."
        }
        return actionText + unlockText + " SAM cannot bypass device or app security, and the destination app may require you to confirm Send or Publish."
    }

    private fun postNotification(id: Long, title: String, text: String, action: PendingIntent? = null) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sam_scheduled_messages"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Scheduled messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(com.bigsammy.sammessagescheduler.R.drawable.ic_stat_sam)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (action != null) {
            // A notification action is a user-initiated foreground launch. Android
            // presents the system keyguard before opening the destination when locked.
            // The same immutable PendingIntent is used for the body tap and action button.
            builder.setContentIntent(action)
                .addAction(android.R.drawable.ic_lock_lock, "Unlock & continue", action)
        }
        manager.notify(id.toInt(), builder.build())
    }
}
