package com.bigsammy.sammessagescheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
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
import com.bigsammy.sammessagescheduler.data.ContactEntity
import com.bigsammy.sammessagescheduler.scheduling.MessageScheduler
import com.bigsammy.sammessagescheduler.scheduling.AnniversaryReminderScheduler
import com.bigsammy.sammessagescheduler.security.BackupCrypto
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Color(0xFF6750A4)
private val Navy = Color(0xFF18243A)
private val Pale = Color(0xFFF5F3FA)

class MainActivity : FragmentActivity() {
    private var backupPassword: String = ""
    private var securityPromptActive = false
    var securityAuthenticated = false

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("biometric_enabled", false) && !securityAuthenticated && !securityPromptActive) authenticateForApp()
    }

    override fun onPause() {
        super.onPause()
        securityAuthenticated = false
    }

    fun authenticateForApp() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Set up a screen lock or biometric in Android Settings first.", Toast.LENGTH_LONG).show()
            getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE).edit().putBoolean("biometric_enabled", false).apply()
            return
        }
        securityPromptActive = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                securityPromptActive = false
                securityAuthenticated = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                securityPromptActive = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@MainActivity, "App authentication was not completed.", Toast.LENGTH_LONG).show()
                }
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(this@MainActivity, "Authentication not recognised. Try again.", Toast.LENGTH_SHORT).show()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SAM Message Scheduler")
            .setSubtitle("Use fingerprint, face unlock, or your Android screen-lock PIN")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val receiveSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(this, if (granted) "Incoming SMS permission granted." else "Incoming SMS permission denied.", Toast.LENGTH_LONG).show()
        }
    private var pendingSmsAction: (() -> Unit)? = null
    private var csvImportPreview by mutableStateOf<List<MessageEntity>>(emptyList())
    private var csvImportInvalidCount by mutableStateOf(0)
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
                val encrypted = BackupCrypto.encrypt(root.toString(), backupPassword.toCharArray())
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(encrypted) }
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

    private val csvImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read CSV file.")
                val rows = parseCsv(raw)
                require(rows.size >= 2) { "CSV must contain a header and at least one data row." }
                val header = rows.first().map { it.trim().removePrefix("\uFEFF").lowercase(Locale.US) }
                val recipientIndex = header.indexOf("recipient")
                val bodyIndex = header.indexOfFirst { it == "message" || it == "body" }
                val channelIndex = header.indexOf("channel")
                val dateIndex = header.indexOf("scheduled at")
                val repeatIndex = header.indexOf("repeat rule")
                require(recipientIndex >= 0 && bodyIndex >= 0 && channelIndex >= 0 && dateIndex >= 0) {
                    "Required columns: Recipient, Message, Channel and Scheduled At."
                }
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
                var invalid = 0
                val valid = rows.drop(1).mapNotNull { row ->
                    runCatching {
                        val recipient = row.getOrElse(recipientIndex) { "" }.trim()
                        val body = row.getOrElse(bodyIndex) { "" }.trim()
                        val channel = row.getOrElse(channelIndex) { "" }.trim()
                        val scheduledAt = formatter.parse(row.getOrElse(dateIndex) { "" }.trim())!!.time
                        val repeat = if (repeatIndex >= 0) row.getOrElse(repeatIndex) { "NONE" }.trim().uppercase(Locale.US) else "NONE"
                        require(recipient.isNotBlank() && body.isNotBlank())
                        require(recipient.count { it.isDigit() } >= 7)
                        require(channel.equals("SMS", true) || channel.equals("WhatsApp", true))
                        require(repeat in listOf("NONE", "DAILY", "WEEKLY", "MONTHLY"))
                        require(scheduledAt > System.currentTimeMillis())
                        MessageEntity(recipient = recipient, body = body,
                            channel = if (channel.equals("SMS", true)) "SMS" else "WhatsApp",
                            scheduledAt = scheduledAt, repeatRule = repeat)
                    }.getOrElse { invalid++; null }
                }
                require(valid.isNotEmpty()) { "No valid future messages were found in this CSV." }
                csvImportPreview = valid
                csvImportInvalidCount = invalid
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: "CSV import failed.", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read backup file.")
                val decrypted = if (JSONObject(raw).optString("format") == "SAM_ENCRYPTED_BACKUP") {
                    BackupCrypto.decrypt(raw, backupPassword.toCharArray())
                } else raw
                val root = JSONObject(decrypted)
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
    private val deviceContactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            runCatching {
                val contactId = contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: error("Could not read the selected contact.")
                val phone = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId), null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null } ?: error("The selected contact has no saved phone number.")
                lifecycleScope.launch {
                    AppDatabase.get(this@MainActivity).contactDao().insert(ContactEntity(name = phone.first, phone = phone.second))
                    Toast.makeText(this@MainActivity, "Contact added to SAM.", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { Toast.makeText(this, it.message ?: "Could not import contact.", Toast.LENGTH_LONG).show() }
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

        AnniversaryReminderScheduler.ensureScheduled(this)
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
                val contacts by AppDatabase.get(this@MainActivity).contactDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                SchedulerScreen(
                    contacts = contacts,
                    onPickContact = { deviceContactPicker.launch(null) },
                    saveContact = { contact -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().insert(contact) } },
                    updateContact = { contact -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().update(contact) } },
                    deleteContact = { id -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().delete(id) } },
                    messages = messages,
                    onBackup = { backupLauncher.launch("sam-message-scheduler-backup.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/*")) },
                    onExportCsv = { csvExportLauncher.launch("sam-message-history.csv") },
                    onImportCsv = { csvImportLauncher.launch(arrayOf("text/csv", "text/*")) },
                    onEnableSmsAutomation = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) receiveSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
                    },
                    importPreview = csvImportPreview,
                    invalidImportCount = csvImportInvalidCount,
                    onConfirmImport = {
                        lifecycleScope.launch {
                            val daoForImport = AppDatabase.get(this@MainActivity).messageDao()
                            val ids = csvImportPreview.map { daoForImport.insert(it) }
                            csvImportPreview.forEachIndexed { index, item ->
                                MessageScheduler.schedule(this@MainActivity, ids[index], item.scheduledAt)
                            }
                            Toast.makeText(this@MainActivity, "${ids.size} messages imported and scheduled.", Toast.LENGTH_LONG).show()
                            csvImportPreview = emptyList()
                        }
                    },
                    onDismissImport = { csvImportPreview = emptyList() },
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


private fun parseCsv(content: String): List<List<String>> {
    val result = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            c == '"' && quoted && i + 1 < content.length && content[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { row += cell.toString(); cell.setLength(0) }
            (c == '\n' || c == '\r') && !quoted -> {
                if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++
                row += cell.toString(); cell.setLength(0)
                if (row.any { it.isNotBlank() }) result += row.toList()
                row.clear()
            }
            else -> cell.append(c)
        }
        i++
    }
    row += cell.toString()
    if (row.any { it.isNotBlank() }) result += row.toList()
    require(!quoted) { "CSV contains an unclosed quoted field." }
    return result
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulerScreen(
    messages: List<MessageEntity>,
    contacts: List<ContactEntity>,
    onPickContact: () -> Unit,
    saveContact: (ContactEntity) -> Unit,
    updateContact: (ContactEntity) -> Unit,
    deleteContact: (Long) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    onEnableSmsAutomation: () -> Unit,
    importPreview: List<MessageEntity>,
    invalidImportCount: Int,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
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
    var pendingRemoval by remember { mutableStateOf<MessageEntity?>(null) }
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
                listOf("Home", "Scheduled", "Calendar", "Contacts", "Settings").forEach { name ->
                    val icon = when (name) {
                        "Home" -> Icons.Default.Home
                        "Scheduled" -> Icons.Default.Schedule
                        "Calendar" -> Icons.Default.CalendarMonth
                        "Contacts" -> Icons.Default.Contacts
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

                    val totalCount = messages.size
                    val completedCount = messages.count { it.status == "COMPLETED" || it.status == "SENT" }
                    val failedCount = messages.count { it.status == "FAILED" || it.status == "PERMISSION_REQUIRED" }
                    val deliveredCount = messages.count { it.deliveryStatus == "DELIVERED" }
                    val smsCount = messages.count { it.channel == "SMS" }
                    val whatsappCount = messages.count { it.channel == "WhatsApp" }
                    val recentCount = messages.count { it.createdAt >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
                    Text("MESSAGE REPORTS & ANALYTICS", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Stat("Total messages", totalCount.toString(), Modifier.weight(1f))
                                Stat("Last 7 days", recentCount.toString(), Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Stat("Failed", failedCount.toString(), Modifier.weight(1f))
                                Stat("Delivered", deliveredCount.toString(), Modifier.weight(1f))
                            }
                            Text("Completion rate", fontWeight = FontWeight.SemiBold, color = Navy)
                            val completionRate = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()
                            LinearProgressIndicator(progress = completionRate, modifier = Modifier.fillMaxWidth(), color = Purple)
                            Text("${(completionRate * 100).toInt()}% completed • $completedCount of $totalCount messages", fontSize = 12.sp, color = Color.Gray)
                            Text("Channel distribution", fontWeight = FontWeight.SemiBold, color = Navy)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SMS", color = Color.Gray); Text("$smsCount", fontWeight = FontWeight.SemiBold, color = Navy) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("WhatsApp", color = Color.Gray); Text("$whatsappCount", fontWeight = FontWeight.SemiBold, color = Navy) }
                            Text("Delivery reports depend on your mobile network. A missing carrier delivery report does not necessarily mean a message failed.", fontSize = 11.sp, color = Color.Gray)
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
                                            TextButton(onClick = { pendingRemoval = message }) { Text("Cancel", color = Color.Red) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "Contacts" -> {
                ContactsPage(
                    contacts, onPickContact, saveContact, updateContact, deleteContact,
                    onBulkSchedule = { selected, body, channel, scheduledAt ->
                        val scheduleAll = {
                            selected.forEach { contact ->
                                val personalised = body.replace("{name}", contact.name, ignoreCase = true)
                                    .replace("{phone}", contact.phone, ignoreCase = true)
                                save(null, contact.phone, personalised, channel, scheduledAt, "NONE")
                            }
                        }
                        if (channel == "SMS") requestSmsPermission(scheduleAll) else scheduleAll()
                    },
                    modifier = Modifier.padding(padding)
                )
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

            "Settings" -> {
                Column(Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val quietPrefs = remember { context.getSharedPreferences("sam_quiet_hours", android.content.Context.MODE_PRIVATE) }
                    var quietEnabled by remember { mutableStateOf(quietPrefs.getBoolean("enabled", false)) }
                    var quietStart by remember { mutableStateOf(quietPrefs.getString("start", "22:00") ?: "22:00") }
                    var quietEnd by remember { mutableStateOf(quietPrefs.getString("end", "07:00") ?: "07:00") }
                    var quietMessage by remember { mutableStateOf("") }
                    Text("Quiet hours", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.padding(top = 16.dp))
                    Text("Scheduled SMS and WhatsApp reminders will be deferred during this daily window. Your original schedule is preserved.", color = Color.Gray, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = quietEnabled, onCheckedChange = { quietEnabled = it })
                        Text("Enable quiet hours")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quietStart, { quietStart = it }, modifier = Modifier.weight(1f), label = { Text("Start (HH:mm)") }, singleLine = true)
                        OutlinedTextField(quietEnd, { quietEnd = it }, modifier = Modifier.weight(1f), label = { Text("End (HH:mm)") }, singleLine = true)
                    }
                    Button(onClick = {
                        val valid = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
                        if (!valid.matches(quietStart) || !valid.matches(quietEnd) || quietStart == quietEnd) {
                            quietMessage = "Enter two different valid 24-hour times."
                        } else {
                            quietPrefs.edit().putBoolean("enabled", quietEnabled).putString("start", quietStart).putString("end", quietEnd).apply()
                            quietMessage = if (quietEnabled) "Quiet hours saved." else "Quiet hours disabled."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save quiet hours") }
                    if (quietMessage.isNotBlank()) Text(quietMessage, color = Purple, fontSize = 12.sp)
                    Text("Incoming SMS automation", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.padding(top = 20.dp))
                    Text("Set optional automatic replies and forward incoming SMS messages to a number you choose.", color = Color.Gray, fontSize = 12.sp)
                    val automationPrefs = remember { context.getSharedPreferences("sam_sms_automation", android.content.Context.MODE_PRIVATE) }
                    var autoReplyEnabled by remember { mutableStateOf(automationPrefs.getBoolean("auto_reply_enabled", false)) }
                    var replyKeyword by remember { mutableStateOf(automationPrefs.getString("reply_keyword", "") ?: "") }
                    var replyText by remember { mutableStateOf(automationPrefs.getString("reply_text", "I have received your message. I will get back to you soon.") ?: "") }
                    var forwardingEnabled by remember { mutableStateOf(automationPrefs.getBoolean("forward_enabled", false)) }
                    var forwardNumber by remember { mutableStateOf(automationPrefs.getString("forward_number", "") ?: "") }
                    var automationMessage by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(autoReplyEnabled, { autoReplyEnabled = it }); Text("Enable automatic replies") }
                    OutlinedTextField(replyKeyword, { replyKeyword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Reply only when message contains (optional)") }, supportingText = { Text("Leave blank to reply to every incoming SMS.") })
                    OutlinedTextField(replyText, { replyText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Automatic reply text") }, minLines = 2)
                    Divider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(forwardingEnabled, { forwardingEnabled = it }); Text("Enable SMS forwarding") }
                    OutlinedTextField(forwardNumber, { forwardNumber = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Forward incoming SMS to") }, singleLine = true)
                    Button(onClick = {
                        if (autoReplyEnabled && replyText.isBlank()) automationMessage = "Enter an automatic reply message."
                        else if (forwardingEnabled && forwardNumber.count { it.isDigit() } < 7) automationMessage = "Enter a valid forwarding number."
                        else {
                            automationPrefs.edit().putBoolean("auto_reply_enabled", autoReplyEnabled).putString("reply_keyword", replyKeyword.trim()).putString("reply_text", replyText.trim()).putBoolean("forward_enabled", forwardingEnabled).putString("forward_number", forwardNumber.trim()).apply()
                            if (autoReplyEnabled || forwardingEnabled) onEnableSmsAutomation()
                            automationMessage = if (autoReplyEnabled || forwardingEnabled) "Rules saved. Grant incoming SMS permission when Android asks." else "Incoming SMS automation disabled."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save SMS automation rules") }
                    if (automationMessage.isNotBlank()) Text(automationMessage, color = Purple, fontSize = 12.sp)
                    Text("Requires incoming SMS and SMS sending permissions. Carrier charges may apply. Forwarding copies message content to the destination number.", color = Color.Gray, fontSize = 11.sp)
                    Text("App security", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.padding(top = 18.dp))
                    val securityPrefs = remember { context.getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE) }
                    var biometricEnabled by remember { mutableStateOf(securityPrefs.getBoolean("biometric_enabled", false)) }
                    var securityMessage by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Require biometric / device PIN", fontWeight = FontWeight.Medium, color = Navy)
                            Text("Request fingerprint, face unlock, or Android screen-lock PIN when opening SAM.", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(checked = biometricEnabled, onCheckedChange = { enabled ->
                            if (enabled) {
                                val available = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                                if (available) {
                                    biometricEnabled = true
                                    securityPrefs.edit().putBoolean("biometric_enabled", true).apply()
                                    securityMessage = "App protection enabled."
                                    (context as? MainActivity)?.let { it.securityAuthenticated = false; it.authenticateForApp() }
                                } else securityMessage = "First set up a screen lock or biometric in Android Settings."
                            } else {
                                biometricEnabled = false
                                securityPrefs.edit().putBoolean("biometric_enabled", false).apply()
                                securityMessage = "App protection disabled."
                            }
                        })
                    }
                    if (securityMessage.isNotBlank()) Text(securityMessage, color = Purple, fontSize = 12.sp)
                    Text("Data protection", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.padding(top = 16.dp))
                    var backupPasswordInput by remember { mutableStateOf("") }
                    var backupPasswordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = backupPasswordInput,
                        onValueChange = { backupPasswordInput = it; backupPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Encrypted backup password") },
                        supportingText = { Text("At least 8 characters. Remember this password; SAM cannot recover it.") },
                        singleLine = true,
                        visualTransformation = if (backupPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { backupPasswordVisible = !backupPasswordVisible }) { Icon(if (backupPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle password visibility") } }
                    )
                    Text("Backups use AES-256-GCM encryption with a password-derived key. Existing plain JSON backups can still be restored.", color = Color.Gray, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        OutlinedButton(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Set a backup password of at least 8 characters." else onBackup() }, modifier = Modifier.weight(1f)) { Text("Encrypted backup") }
                        Button(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Enter the password used for this backup." else onRestore() }, modifier = Modifier.weight(1f)) { Text("Restore backup") }
                    }
                    if (securityMessage.isNotBlank()) Text(securityMessage, color = Purple, fontSize = 12.sp)

                    OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import messages from CSV")
                    }
                    OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export message history (CSV)")
                    }
                    listOf(
                        "Notifications: allow reminders in Android settings.",
                        "SMS: uses the Android default SMS SIM; change the preferred SIM in Android SIM settings.",
                        "SMS delivery tracking: sent and delivered updates appear when your mobile network provides them.",
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


    if (importPreview.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissImport,
            title = { Text("Review CSV import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${importPreview.size} valid future message(s) are ready to import.")
                    if (invalidImportCount > 0) Text("$invalidImportCount row(s) will be skipped because their data is invalid or their scheduled time has passed.", color = Color(0xFF9A4D00))
                    Text("Preview", fontWeight = FontWeight.SemiBold)
                    importPreview.take(4).forEach { item ->
                        Text("${item.recipient} • ${item.channel}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(item.body, maxLines = 2, fontSize = 12.sp, color = Color.Gray)
                        Text(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.scheduledAt)), fontSize = 11.sp, color = Purple)
                    }
                    if (importPreview.size > 4) Text("And ${importPreview.size - 4} more…", fontSize = 12.sp)
                }
            },
            confirmButton = { Button(onClick = onConfirmImport) { Text("Import ${importPreview.size}") } },
            dismissButton = { TextButton(onClick = onDismissImport) { Text("Cancel") } }
        )
    }

    pendingRemoval?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Cancel this message?") },
            text = {
                Text("The scheduled message for ${message.recipient} will be removed from this device. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    remove(message.id)
                    pendingRemoval = null
                }) { Text("Remove message", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Keep message") }
            }
        )
    }
}



@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkComposer(
    contacts: List<ContactEntity>,
    onClose: () -> Unit,
    onSchedule: (List<ContactEntity>, String, String, Long) -> Unit
) {
    var selectedIds by remember { mutableStateOf(contacts.map { it.id }.toSet()) }
    var body by remember { mutableStateOf("Hello {name}, ") }
    var channel by remember { mutableStateOf("SMS") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 3_600_000L))) }
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + 3_600_000L))) }
    var error by remember { mutableStateOf("") }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onClose,
        title = { Text("Bulk message") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * 0.62f).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Select recipients", fontWeight = FontWeight.SemiBold, color = Navy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedIds.size} of ${contacts.size} selected", modifier = Modifier.weight(1f), color = Purple, fontSize = 12.sp)
                    TextButton(onClick = { selectedIds = if (selectedIds.size == contacts.size) emptySet() else contacts.map { it.id }.toSet() }) {
                        Text(if (selectedIds.size == contacts.size) "Clear all" else "Select all")
                    }
                }
                contacts.forEach { contact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = contact.id in selectedIds, onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + contact.id else selectedIds - contact.id
                        })
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, fontWeight = FontWeight.Medium, color = Navy)
                            Text("${contact.phone} • ${contact.groupName}", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
                Text("Message template", fontWeight = FontWeight.SemiBold, color = Navy)
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Message") }, supportingText = { Text("Use {name} and {phone} to personalise each message.") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = channel == "SMS", onClick = { channel = "SMS" }, label = { Text("SMS") })
                    FilterChip(selected = channel == "WhatsApp", onClick = { channel = "WhatsApp" }, label = { Text("WhatsApp") })
                }
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Time (24-hour HH:mm)") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = Color.Red)
                Text("Each recipient gets a separate scheduled message. WhatsApp requires you to review and tap Send when the chat opens.", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }.parse("$date $time")!!.time
                    val selected = contacts.filter { it.id in selectedIds }
                    when {
                        selected.isEmpty() -> error = "Select at least one contact."
                        body.isBlank() -> error = "Enter a message."
                        at <= System.currentTimeMillis() -> error = "Choose a future date and time."
                        else -> onSchedule(selected, body.trim(), channel, at)
                    }
                } catch (_: Exception) { error = "Enter a valid date and time." }
            }) { Text("Schedule ${selectedIds.size}") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactsPage(
    contacts: List<ContactEntity>,
    onPickContact: () -> Unit,
    saveContact: (ContactEntity) -> Unit,
    updateContact: (ContactEntity) -> Unit,
    deleteContact: (Long) -> Unit,
    onBulkSchedule: (List<ContactEntity>, String, String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf("All") }
    var editing by remember { mutableStateOf<ContactEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ContactEntity?>(null) }
    var showBulkComposer by remember { mutableStateOf(false) }
    val groups = listOf("All") + contacts.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
    val visible = contacts.filter { (selectedGroup == "All" || it.groupName == selectedGroup) && (query.isBlank() || it.name.contains(query, true) || it.phone.contains(query, true)) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Contact centre", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("${contacts.size} saved contact(s)", color = Color.Gray, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onPickContact) { Icon(Icons.Default.ContactPhone, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("From phone") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Search contacts") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            groups.forEach { group -> FilterChip(selected = selectedGroup == group, onClick = { selectedGroup = group }, label = { Text(group) }) }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (contacts.isEmpty()) "No contacts saved yet. Add one from your phone." else "No contacts match your search.", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { contact ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.SemiBold, color = Navy)
                                Text(contact.phone, color = Color.Gray, fontSize = 13.sp)
                                Text(contact.groupName, color = Purple, fontSize = 11.sp)
                            }
                            TextButton(onClick = { editing = contact; showEditor = true }) { Text("Edit") }
                            TextButton(onClick = { deleting = contact }) { Text("Delete", color = Color.Red) }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { showBulkComposer = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = contacts.isNotEmpty()) {
            Icon(Icons.Default.Campaign, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Create bulk message")
        }
        Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.PersonAdd, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add contact")
        }
    }
    if (showBulkComposer) {
        BulkComposer(contacts, onClose = { showBulkComposer = false }, onSchedule = { selected, body, channel, at ->
            onBulkSchedule(selected, body, channel, at)
            showBulkComposer = false
        })
    }
    if (showEditor) {
        var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
        var phone by remember(editing?.id) { mutableStateOf(editing?.phone ?: "") }
        var group by remember(editing?.id) { mutableStateOf(editing?.groupName ?: "General") }
        var birthday by remember(editing?.id) { mutableStateOf(editing?.birthday ?: "") }
        var anniversary by remember(editing?.id) { mutableStateOf(editing?.anniversary ?: "") }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editing == null) "Add contact" else "Edit contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true)
                    OutlinedTextField(group, { group = it }, label = { Text("Group") }, singleLine = true, supportingText = { Text("Examples: Family, Staff, Students, Customers") })
                    OutlinedTextField(birthday, { birthday = it }, label = { Text("Birthday (MM-dd, optional)") }, singleLine = true, supportingText = { Text("Example: 09-25. Repeats every year.") })
                    OutlinedTextField(anniversary, { anniversary = it }, label = { Text("Anniversary (MM-dd, optional)") }, singleLine = true, supportingText = { Text("Example: 12-10. Repeats every year.") })
                    if (error.isNotBlank()) Text(error, color = Color.Red)
                }
            },
            confirmButton = { Button(onClick = {
                when {
                    name.isBlank() -> error = "Enter the contact's name."
                    phone.count { it.isDigit() } < 7 -> error = "Enter a valid phone number."
                    group.isBlank() -> error = "Enter a group name."
                    (birthday.isNotBlank() && !Regex("^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$").matches(birthday)) -> error = "Birthday must use MM-dd format."
                    (anniversary.isNotBlank() && !Regex("^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$").matches(anniversary)) -> error = "Anniversary must use MM-dd format."
                    else -> {
                        val item = ContactEntity(id = editing?.id ?: 0, name = name.trim(), phone = phone.trim(), groupName = group.trim(), birthday = birthday.trim(), anniversary = anniversary.trim())
                        if (editing == null) saveContact(item) else updateContact(item)
                        showEditor = false
                    }
                }
            }) { Text("Save contact") } },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }
    deleting?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete contact?") },
            text = { Text("Remove ${contact.name} from SAM's saved contacts? This will not delete the contact from your phone.") },
            confirmButton = { TextButton(onClick = { deleteContact(contact.id); deleting = null }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
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
        Column(horizontalAlignment = Alignment.End) {
            Text(message.status.lowercase(), fontSize = 10.sp, color = Color.Gray)
            if (message.channel == "SMS") Text(message.deliveryStatus.replace("_", " ").lowercase(), fontSize = 9.sp, color = if (message.deliveryStatus == "DELIVERED") Color(0xFF237A45) else Color.Gray)
        }
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
