package com.bigsammy.sammessagescheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.DialogProperties
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
    private var pendingSmsAction: (() -> Unit)? = null
    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingSmsAction?.invoke()
            else Toast.makeText(this, "SMS permission is needed for scheduled SMS sending.", Toast.LENGTH_LONG).show()
            pendingSmsAction = null
        }

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
                    save = { existingId, recipient, body, channel, scheduledAt, repeatRule ->
                        lifecycleScope.launch {
                            if (existingId != null) {
                                val current = dao.getById(existingId)
                                if (current != null) {
                                    MessageScheduler.cancel(this@MainActivity, existingId)
                                    dao.update(current.copy(
                                        recipient = recipient,
                                        body = body,
                                        channel = channel,
                                        scheduledAt = scheduledAt,
                                        repeatRule = repeatRule,
                                        status = "SCHEDULED"
                                    ))
                                    MessageScheduler.schedule(this@MainActivity, existingId, scheduledAt)
                                }
                            } else {
                                val id = dao.insert(MessageEntity(
                                    recipient = recipient,
                                    body = body,
                                    channel = channel,
                                    scheduledAt = scheduledAt,
                                    repeatRule = repeatRule
                                ))
                                MessageScheduler.schedule(this@MainActivity, id, scheduledAt)
                            }
                        }
                    },
                    requestSmsPermission = { action ->
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED) action()
                        else {
                            pendingSmsAction = action
                            smsPermission.launch(Manifest.permission.SEND_SMS)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulerScreen(
    messages: List<MessageEntity>,
    save: (Long?, String, String, String, Long, String) -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    remove: (Long) -> Unit,
    complete: (MessageEntity) -> Unit
) {
    var page by remember { mutableStateOf("Home") }
    var showComposer by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var searchText by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }

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
                    modifier = Modifier.padding(padding).fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp)
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
                val visibleMessages = messages.filter { message ->
                    (statusFilter == "ALL" || message.status == statusFilter) &&
                        (message.recipient.contains(searchText, true) ||
                            message.body.contains(searchText, true) ||
                            message.channel.contains(searchText, true))
                }.sortedBy { it.scheduledAt }
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Your messages", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                        label = { Text("Search recipient or message") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("ALL" to "All", "SCHEDULED" to "Upcoming", "SENT" to "Sent", "FAILED" to "Failed").forEach { (key, label) ->
                            FilterChip(selected = statusFilter == key, onClick = { statusFilter = key }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                    if (visibleMessages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No messages match your search.", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleMessages, key = { it.id }) { message ->
                                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Column(Modifier.padding(12.dp)) {
                                        MessageCard(message)
                                        Row(
                                            modifier = Modifier.align(Alignment.End),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (message.status == "SCHEDULED") {
                                                TextButton(onClick = { editMessage = message; showComposer = true }) { Text("Edit") }
                                                TextButton(onClick = { complete(message) }) { Text("Mark done") }
                                            }
                                            TextButton(onClick = { remove(message.id) }) { Text("Cancel", color = Color.Red) }
                                        }
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
                Column(Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    listOf(
                        "Notifications: allow reminders in Android settings.",
                        "SMS: sends automatically at the scheduled time after permission is granted.",
                        "WhatsApp: opens a prepared chat; you review and tap Send.",
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
            initialMessage = editMessage,
            onClose = { showComposer = false; editMessage = null },
            requestSmsPermission = requestSmsPermission,
            onSave = { id, recipient, body, channel, scheduledAt, repeatRule ->
                save(id, recipient, body, channel, scheduledAt, repeatRule)
                showComposer = false
                editMessage = null
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    initialMessage: MessageEntity?,
    onClose: () -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    onSave: (Long?, String, String, String, Long, String) -> Unit
) {
    var recipient by remember(initialMessage?.id) { mutableStateOf(initialMessage?.recipient ?: "") }
    var body by remember(initialMessage?.id) { mutableStateOf(initialMessage?.body ?: "") }
    var channel by remember(initialMessage?.id) { mutableStateOf(initialMessage?.channel ?: "SMS") }
    var date by remember(initialMessage?.id) { mutableStateOf(initialMessage?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.scheduledAt)) } ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 3600000))) }
    var time by remember(initialMessage?.id) { mutableStateOf(initialMessage?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.scheduledAt)) } ?: SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + 3600000))) }
    var repeatRule by remember(initialMessage?.id) { mutableStateOf(initialMessage?.repeatRule ?: "NONE") }
    var error by remember { mutableStateOf("") }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onClose,
        title = { Text(if (initialMessage == null) "Schedule a message" else "Edit scheduled message") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp * 0.56f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(selected = channel == "SMS", onClick = { channel = "SMS" }, label = { Text("SMS") })
                    FilterChip(selected = channel == "WhatsApp", onClick = { channel = "WhatsApp" }, label = { Text("WhatsApp") })
                }
                Text("Quick templates", fontWeight = FontWeight.SemiBold, color = Navy)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "Greeting" to "Hello! I hope you are doing well.",
                        "Reminder" to "This is a friendly reminder about our earlier arrangement.",
                        "Birthday" to "Wishing you a very happy birthday. May your new year be filled with blessings!",
                        "Meeting" to "This is a reminder of our scheduled meeting. Kindly be on time."
                    ).forEach { (label, template) ->
                        AssistChip(onClick = { body = template }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                Text("Repeat", fontWeight = FontWeight.SemiBold, color = Navy)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("NONE" to "Never", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly").forEach { (key, label) ->
                        FilterChip(selected = repeatRule == key, onClick = { repeatRule = key }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recipient / phone number") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 3
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Date") },
                    supportingText = { Text("Enter as YYYY-MM-DD") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Time") },
                    supportingText = { Text("24-hour format, HH:mm") },
                    singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = Color.Red)
                Text(
                    "SMS is submitted automatically with permission. WhatsApp opens a prepared chat for your final confirmation.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val action = {
                    try {
                        val scheduledAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .apply { isLenient = false }
                            .parse("$date $time")!!.time
                        when {
                            recipient.isBlank() || body.isBlank() -> error = "Enter recipient and message."
                            recipient.count { it.isDigit() } < 7 -> error = "Enter a valid phone number with country code where required."
                            scheduledAt <= System.currentTimeMillis() -> error = "Choose a future date and time."
                            else -> onSave(initialMessage?.id, recipient.trim(), body.trim(), channel, scheduledAt, repeatRule)
                        }
                    } catch (_: Exception) {
                        error = "Enter a valid date and time."
                    }
                }
                if (channel == "SMS") requestSmsPermission(action) else action()
            }) {
                Text(if (initialMessage == null) "Save schedule" else "Save changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}
