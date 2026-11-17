package com.bigsammy.sammessagescheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val prefs = context.getSharedPreferences("sam_sms_automation", Context.MODE_PRIVATE)
        val autoReply = prefs.getBoolean("auto_reply_enabled", false)
        val forward = prefs.getBoolean("forward_enabled", false)
        if (!autoReply && !forward) return
        val messages = try {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")
            pdus.mapNotNull { pdu ->
                val bytes = pdu as? ByteArray ?: return@mapNotNull null
                if (android.os.Build.VERSION.SDK_INT >= 23) SmsMessage.createFromPdu(bytes, format)
                else SmsMessage.createFromPdu(bytes)
            }
        } catch (_: Exception) { return }
        val grouped = messages.groupBy { it.originatingAddress.orEmpty() }
        val smsManager = try {
            @Suppress("DEPRECATION")
            val subId = SmsManager.getDefaultSmsSubscriptionId()
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        } catch (_: Exception) { SmsManager.getDefault() }
        grouped.forEach { (sender, parts) ->
            val body = parts.joinToString("") { it.messageBody.orEmpty() }.trim()
            if (sender.isBlank() || body.isBlank()) return@forEach
            if (autoReply) {
                val keyword = prefs.getString("reply_keyword", "").orEmpty()
                val reply = prefs.getString("reply_text", "").orEmpty()
                if (reply.isNotBlank() && (keyword.isBlank() || body.contains(keyword, ignoreCase = true)))
                    runCatching { smsManager.sendTextMessage(sender, null, reply, null, null) }
            }
            if (forward) {
                val destination = prefs.getString("forward_number", "").orEmpty()
                if (destination.count { it.isDigit() } >= 7 && !destination.equals(sender, ignoreCase = true)) {
                    val forwarded = "Forwarded SMS from $sender:\n$body"
                    runCatching { smsManager.sendMultipartTextMessage(destination, null, smsManager.divideMessage(forwarded), null, null) }
                }
            }
        }
    }
}