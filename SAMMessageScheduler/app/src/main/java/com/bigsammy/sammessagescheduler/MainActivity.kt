package com.bigsammy.sammessagescheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bigsammy.sammessagescheduler.data.AppDatabase
import com.bigsammy.sammessagescheduler.data.MessageEntity
import com.bigsammy.sammessagescheduler.scheduling.MessageScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Color(0xFF6750A4)
private val Navy = Color(0xFF18243A)
private val Pale = Color(0xFFF5F3FA)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val dao = AppDatabase.get(this).messageDao()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, background = Pale)) {
                val messages by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                SchedulerScreen(
                    messages = messages,
                    save = { recipient, body, channel, scheduledAt ->
                        lifecycleScope.launch {
                            val id = dao.insert(
                                MessageEntity(
                                    recipient = recipient,
                                    body = body,
                                    channel = channel,
                                    scheduledAt = scheduledAt
                                )
                            )
                            MessageScheduler.schedule(this@MainActivity, id, scheduledAt)
                        }
                    },
                    remove = { id ->
                        lifecycleScope.launch {
                            MessageScheduler.cancel(this@MainActivity, id)
                            dao.delete(id)
                        }
                    },
                    complete = { message ->
                        lifecycleScope.launch {
                            dao.update(message.copy(status = "COMPLETED"))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulerScreen(
    messages: List<MessageEntity>,
    save: (String, String, String, Long) -> Unit,
    remove: (Long) -> Unit,
    complete: (MessageEntity) -> Unit
) {
    var page by remember { mutableStateOf("Home") }
    var showComposer by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Pale,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Forum, contentDescription = null, tint = Purple, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("SAM Message Scheduler", fontWeight = FontWeight.Bold, color = Navy, fontSize = 17.sp)
                    Text("Plan it. Remember it. Send it.", fontSize = 11.sp, color = Color.Gray)
                }
                Icon(Icons.Default.Schedule, contentDescription = null, tint = Purple)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf("Home", "Scheduled", "Calendar", "Settings").forEach { name ->
                    val icon = when (name) {
                        "Home" -> Icons.Default.Home
                        "Scheduled" -> Icons.Default.Schedule
                        "Calendar" -> Icons.Default.CalendarMonth
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = page == name,
                        onClick = { page = name },
                        icon = { Icon(icon, contentDescription = name) },
                        label = { Text(name, fontSize = 10.sp) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (page != "Settings") {
                FloatingActionButton(
                    onClick = { showComposer = true },
                    containerColor = Purple
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add message", tint = Color.White)
                }
            }
        }
    ) { padding ->
        when (page) {
            "Home" -> {
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(18.dp)
                ) {
                    Text("Good day 👋", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Your messages, organised and on time.", color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Stat("Scheduled", messages.count { it.status == "SCHEDULED" }.toString(), Modifier.weight(1f))
                        Stat("Completed", messages.count { it.status == "COMPLETED" }.toString(), Modifier.weight(1f))
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Navy),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Make every message count", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Prepare an SMS or WhatsApp reminder in seconds.",
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Button(
                                onClick = { showComposer = true },
                                modifier = Modifier.padding(top = 14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text(" Schedule message")
                            }
                        }
                    }
                    Text(
                        "UPCOMING MESSAGES",
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)
                    )
                    messages.filter { it.status == "SCHEDULED" }
                        .sortedBy { it.scheduledAt }
                        .take(5)
                        .forEach { MessageCard(it) }
                }
            }

            "Scheduled" -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Your messages", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    }
                    items(messages, key = { it.id }) { message ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(12.dp)) {
                                MessageCard(message)
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (message.status == "SCHEDULED") {
                                        TextButton(onClick = { complete(message) }) {
                                            Text("Mark done")
                                        }
                                    }
                                    TextButton(onClick = { remove(message.id) }) {
                                        Text("Cancel", color = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "Calendar" -> {
                val grouped = messages.sortedBy { it.scheduledAt }.groupBy {
                    SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(it.scheduledAt))
                }
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(18.dp)
                ) {
                    item {
                        Text("Schedule calendar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    }
                    grouped.forEach { (day, dayMessages) ->
                        item(key = "day_$day") {
                            Text(
                                day,
                                color = Purple,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                            )
                        }
                        items(dayMessages, key = { it.id }) { message ->
                            MessageCard(message)
                        }
                    }
                    if (messages.isEmpty()) {
                        item { Text("No messages scheduled yet.", color = Color.Gray) }
                    }
                }
            }

            else -> {
                Column(Modifier.padding(padding).fillMaxSize().padding(18.dp)) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    listOf(
                        "Notifications: allow reminders in Android settings.",
                        "SMS: Phase 1 opens a reminder for manual sending.",
                        "WhatsApp: reminder and confirmation workflow.",
                        "Privacy: scheduled messages are stored on this device."
                    ).forEach { setting ->
                        Text(setting, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
        }
    }

    if (showComposer) {
        Composer(
            onClose = { showComposer = false },
            onSave = { recipient, body, channel, scheduledAt ->
                save(recipient, body, channel, scheduledAt)
                showComposer = false
            }
        )
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(title, color = Color.Gray)
        }
    }
}

@Composable
private fun MessageCard(message: MessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (message.channel == "SMS") Icons.Default.Sms else Icons.Default.Chat,
            contentDescription = message.channel,
            tint = Purple,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(message.recipient, fontWeight = FontWeight.SemiBold, color = Navy)
            Text(message.body, maxLines = 2, fontSize = 12.sp, color = Color.Gray)
            Text(
                "${message.channel} • " + SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                    .format(Date(message.scheduledAt)),
                fontSize = 11.sp,
                color = Purple
            )
        }
        Text(message.status.lowercase(), fontSize = 10.sp, color = Color.Gray)
    }
}

@Composable
private fun Composer(
    onClose: () -> Unit,
    onSave: (String, String, String, Long) -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("SMS") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("09:00") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Schedule a message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = channel == "SMS", onClick = { channel = "SMS" }, label = { Text("SMS") })
                    FilterChip(selected = channel == "WhatsApp", onClick = { channel = "WhatsApp" }, label = { Text("WhatsApp") })
                }
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("Recipient / phone number") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    minLines = 3
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Time (HH:mm)") },
                    singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = Color.Red)
                Text(
                    "SAM will remind you to send the message. WhatsApp may require confirmation.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val scheduledAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .apply { isLenient = false }
                        .parse("$date $time")!!.time
                    when {
                        recipient.isBlank() || body.isBlank() -> error = "Enter recipient and message."
                        scheduledAt <= System.currentTimeMillis() -> error = "Choose a future date and time."
                        else -> onSave(recipient.trim(), body.trim(), channel, scheduledAt)
                    }
                } catch (_: Exception) {
                    error = "Enter a valid date and time."
                }
            }) {
                Text("Save schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}
