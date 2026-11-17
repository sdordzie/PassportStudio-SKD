package com.bigsammy.sammessagescheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
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
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val rows = AppDatabase.get(this@MainActivity).messageDao().getAllSnapshot()
                val root = JSONObject().put("format", "SAM_MESSAGE_SCHEDULER_BACKUP").put("version", 1)
                val array = JSONArray()
                rows.forEach { item ->
                    array.put(JSONObject().put("recipient", item.recipient).put("body", item.body)
                        .put("channel", item.channel).put("scheduledAt", item.scheduledAt)
                        .put("status", item.status).put("repeatRule", item.repeatRule).put("createdAt", item.createdAt))
                }
                root.put("messages", array)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
                    ?: error("Could not open backup file.")
                Toast.makeText(this@MainActivity, "Backup exported successfully.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Backup failed.", Toast.LENGTH_LONG).show() }
        }
    }
    private val csvExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val rows = AppDatabase.get(this@MainActivity).messageDao().getAllSnapshot()
                fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
                val header = listOf("ID", "Recipient", "Message", "Channel", "Scheduled At", "Status", "Repeat Rule", "Created At")
                val lines = mutableListOf(header.joinToString(","))
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                rows.forEach { item ->
                    lines += listOf(
                        item.id.toString(), csv(item.recipient), csv(item.body), csv(item.channel),
                        csv(formatter.format(Date(item.scheduledAt))), csv(item.status), csv(item.repeatRule),
                        csv(formatter.format(Date(item.createdAt)))
                    ).joinToString(",")
                }
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write("\uFEFF")
                    writer.write(lines.joinToString("\r\n"))
                } ?: error("Could not create CSV file.")
                Toast.makeText(this@MainActivity, "Message history exported to CSV.", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "CSV export failed.", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read backup file.")
                val root = JSONObject(raw)
                require(root.optString("format") == "SAM_MESSAGE_SCHEDULER_BACKUP") { "Invalid SAM backup file." }
                val array = root.getJSONArray("messages")
                val restored = mutableListOf<MessageEntity>()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    restored += MessageEntity(recipient = item.getString("recipient"), body = item.getString("body"),
                        channel = item.getString("channel"), scheduledAt = item.getLong("scheduledAt"),
                        status = item.optString("status", "SCHEDULED"), repeatRule = item.optString("repeatRule", "NONE"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()))
                }
                val dao = AppDatabase.get(this@MainActivity).messageDao()
                dao.insertAll(restored)
                val all = dao.getAllSnapshot()
                all.filter { it.status == "SCHEDULED" && it.scheduledAt > System.currentTimeMillis() }
                    .forEach { MessageScheduler.schedule(this@MainActivity, it.id, it.scheduledAt) }
                Toast.makeText(this@MainActivity, "Backup restored.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Restore failed. Check the backup file.", Toast.LENGTH_LONG).show() }
        }
    }
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
        // Rebuild pending jobs from saved records whenever the app opens.
        // This repairs delays after device clock/time-zone changes and catches overdue items.
        lifecycleScope.launch {
            dao.getAllSnapshot()
                .filter { it.status == "SCHEDULED" }
                .forEach { MessageScheduler.schedule(this@MainActivity, it.id, it.scheduledAt) }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, background = Pale)) {
                val messages by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                SchedulerScreen(
                    messages = messages,
                    onBackup = { backupLauncher.launch("sam-message-scheduler-backup.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/*")) },
                    onExportCsv = { csvExportLauncher.launch("sam-message-history.csv") },
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
                            MessageScheduler.cancel(this@MainActivity, message.id)
                            dao.update(message.copy(status = "COMPLETED"))
                        }
                    },
                    togglePause = { message ->
                        lifecycleScope.launch {
                            if (message.status == "SCHEDULED") {
                                MessageScheduler.cancel(this@MainActivity, message.id)
                                dao.update(message.copy(status = "PAUSED"))
                            } else if (message.status == "PAUSED") {
                                val resumeAt = maxOf(message.scheduledAt, System.currentTimeMillis() + 5_000L)
                                dao.update(message.copy(status = "SCHEDULED", scheduledAt = resumeAt))
                                MessageScheduler.schedule(this@MainActivity, message.id, resumeAt)
                            }
                        }
                    },
                    retry = { message ->
                        val scheduleAgain: () -> Unit = {
                            lifecycleScope.launch {
                                val nextAttempt = System.currentTimeMillis() + 30_000L
                                dao.update(message.copy(status = "SCHEDULED", scheduledAt = nextAttempt))
                                MessageScheduler.schedule(this@MainActivity, message.id, nextAttempt)
                            }
                            Unit
                        }
                        if (message.channel == "SMS" &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingSmsAction = scheduleAgain
                            smsPermission.launch(Manifest.permission.SEND_SMS)
                        } else scheduleAgain()
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
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit,
    save: (Long?, String, String, String, Long, String) -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    remove: (Long) -> Unit,
    complete: (MessageEntity) -> Unit,
    togglePause: (MessageEntity) -> Unit,
    retry: (MessageEntity) -> Unit
) {
    var page by remember { mutableStateOf("Home") }
    var showComposer by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var duplicateMode by remember { mutableStateOf(false) }
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
                        Stat("Completed", messages.count { it.status == "COMPLETED" || it.status == "SENT" }.toString(), Modifier.weight(1f))
                    }
                    val attentionCount = messages.count { it.status in listOf("FAILED", "PERMISSION_REQUIRED", "AWAITING_USER") }
                    if (attentionCount > 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E8))
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF9A4D00))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("$attentionCount message(s) need attention", fontWeight = FontWeight.SemiBold, color = Navy)
                                    Text("Review permission issues, failed actions or WhatsApp messages awaiting you.", fontSize = 12.sp, color = Color.DarkGray)
                                }
                                TextButton(onClick = { page = "Scheduled"; statusFilter = "ATTENTION" }) { Text("Review") }
                            }
                        }
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
                    (statusFilter == "ALL" || message.status == statusFilter ||
                        (statusFilter == "ATTENTION" && message.status in listOf("FAILED", "PERMISSION_REQUIRED", "AWAITING_USER"))) &&
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
                        listOf("ALL" to "All", "SCHEDULED" to "Upcoming", "PAUSED" to "Paused", "SENT" to "Sent", "FAILED" to "Failed", "ATTENTION" to "Needs attention").forEach { (key, label) ->
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
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalArrangement = Arrangement.spacedBy(0.dp)
                                        ) {
                                            TextButton(onClick = {
                                                editMessage = message.copy(
                                                    id = 0L,
                                                    scheduledAt = System.currentTimeMillis() + 3_600_000L,
                                                    status = "SCHEDULED",
                                                    repeatRule = "NONE",
                                                    createdAt = System.currentTimeMillis()
                                                )
                                                duplicateMode = true
                                                showComposer = true
                                            }) { Text("Duplicate") }
                                            if (message.status == "SCHEDULED") {
                                                TextButton(onClick = { editMessage = message; showComposer = true }) { Text("Edit") }
                                                TextButton(onClick = { togglePause(message) }) { Text("Pause") }
                                                TextButton(onClick = { complete(message) }) { Text("Mark done") }
                                            }
                                            if (message.status == "PAUSED") {
                                                TextButton(onClick = { togglePause(message) }) { Text("Resume") }
                                                TextButton(onClick = { editMessage = message; showComposer = true }) { Text("Edit") }
                                            }
                                            if (message.status in listOf("FAILED", "PERMISSION_REQUIRED", "AWAITING_USER")) {
                                                TextButton(onClick = { retry(message) }) { Text("Retry") }
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
                    Text("Data protection", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.padding(top = 16.dp))
                    Text("Export your message history to a spreadsheet, or create and restore a full backup.", color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) { Text("Export backup") }
                        Button(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("Restore backup") }
                    }
                    OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export message history (CSV)")
                    }
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
            duplicateMode = duplicateMode,
            onClose = { showComposer = false; editMessage = null; duplicateMode = false },
            requestSmsPermission = requestSmsPermission,
            onSave = { id, recipient, body, channel, scheduledAt, repeatRule ->
                save(if (duplicateMode) null else id, recipient, body, channel, scheduledAt, repeatRule)
                showComposer = false
                editMessage = null
                duplicateMode = false
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
    duplicateMode: Boolean = false,
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
        title = { Text(when { duplicateMode -> "Duplicate message"; initialMessage == null -> "Schedule a message"; else -> "Edit scheduled message" }) },
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
                Text(if (duplicateMode || initialMessage == null) "Save schedule" else "Save changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}
