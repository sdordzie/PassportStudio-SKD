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
                val configured = prefs.getString("reply_rules", "").orEmpty()
                val rules = configured.lineSequence().mapNotNull { line ->
                    val separator = line.indexOf("=>")
                    if (separator < 0) null
                    else {
                        val keyword = line.substring(0, separator).trim()
                        val reply = line.substring(separator + 2).trim()
                        if (reply.isBlank()) null else keyword to reply
                    }
                }.toList()
                val effectiveRules = if (rules.isNotEmpty()) rules else {
                    val keyword = prefs.getString("reply_keyword", "").orEmpty()
                    val reply = prefs.getString("reply_text", "").orEmpty()
                    if (reply.isNotBlank()) listOf(keyword to reply) else emptyList()
                }
                val matched = effectiveRules.withIndex().firstOrNull { (_, rule) ->
                    rule.first.isBlank() || body.contains(rule.first, ignoreCase = true)
                }
                if (matched != null) {
                    val cooldownMillis = 12L * 60L * 60L * 1000L
                    val cooldownKey = "reply_last_${sender.hashCode()}_${matched.index}"
                    val lastReply = prefs.getLong(cooldownKey, 0L)
                    if (System.currentTimeMillis() - lastReply >= cooldownMillis) {
                        runCatching {
                            smsManager.sendTextMessage(sender, null, matched.value.second, null, null)
                            prefs.edit().putLong(cooldownKey, System.currentTimeMillis()).apply()
                        }
                    }
                }
            }

            if (forward) {
                val destination = prefs.getString("forward_number", "").orEmpty()
                val keyword = prefs.getString("forward_keyword", "").orEmpty()
                val matchesFilter = keyword.isBlank() || body.contains(keyword, ignoreCase = true)
                if (matchesFilter && destination.count { it.isDigit() } >= 7 &&
                    !destination.equals(sender, ignoreCase = true)) {
                    val forwarded = "Forwarded SMS from $sender:\n$body"
                    runCatching {
                        smsManager.sendMultipartTextMessage(
                            destination, null, smsManager.divideMessage(forwarded), null, null
                        )
                    }
                }
            }
        }
    }
}
