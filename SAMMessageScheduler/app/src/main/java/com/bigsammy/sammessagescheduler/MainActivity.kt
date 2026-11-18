package com.bigsammy.sammessagescheduler

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.location.Geocoder
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.bigsammy.sammessagescheduler.scheduling.LocationGeofenceManager
import com.bigsammy.sammessagescheduler.scheduling.AnniversaryReminderScheduler
import com.bigsammy.sammessagescheduler.security.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class WhatsAppAppOption(val packageName: String, val label: String)

private val Purple = Color(0xFF6750A4)
// Shared Compose state lets the settings screen refresh when the Activity resumes.
private val permissionRefreshTick = mutableIntStateOf(0)

class MainActivity : FragmentActivity() {
    private var backupPassword: String = ""
    private var themeMode by mutableStateOf("System")
    private var pickedRecipientNumber by mutableStateOf("")
    private var pickedAttachmentUris by mutableStateOf<List<String>>(emptyList())
    private var pendingCaptureUri: android.net.Uri? = null
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (result.resultCode == RESULT_OK && uri != null) {
            pickedAttachmentUris = (pickedAttachmentUris + uri.toString()).distinct()
            Toast.makeText(this, "Media captured and attached.", Toast.LENGTH_SHORT).show()
        } else if (uri != null) {
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }
    private var securityPromptActive = false
    var securityAuthenticated = false
    override fun onResume() {
        super.onResume()
        permissionRefreshTick.intValue++
        val prefs = getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("biometric_enabled", false) && !securityAuthenticated && !securityPromptActive) authenticateForApp()
    }

    override fun onPause() {
        super.onPause()
        securityAuthenticated = false
    }

    private fun captureMedia(video: Boolean) {
        val collection = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "SAM_MEDIA_" + System.currentTimeMillis() + if (video) ".mp4" else ".jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
        }
        val uri = runCatching { contentResolver.insert(collection, values) }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Could not prepare a media file for capture.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = android.content.Intent(if (video) android.provider.MediaStore.ACTION_VIDEO_CAPTURE else android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            clipData = android.content.ClipData.newRawUri("SAM media output", uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "No compatible camera app is available.", Toast.LENGTH_LONG).show()
            return
        }
        pendingCaptureUri = uri
        captureLauncher.launch(intent)
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
    private var pendingLocationAction: (() -> Unit)? = null

    private fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val foregroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            continueLocationPermissionFlow()
        } else {
            pendingLocationAction = null
            Toast.makeText(this, "Location access is required to activate an arrival reminder.", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingLocationAction?.invoke()
        } else {
            pendingLocationAction = null
            Toast.makeText(this, "Allow location access all the time to activate arrival reminders when SAM is closed.", Toast.LENGTH_LONG).show()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasForegroundLocationPermission() && hasBackgroundLocationPermission()) {
            pendingLocationAction?.invoke()
        } else {
            pendingLocationAction = null
            Toast.makeText(this, "Arrival reminder not saved. Enable Location > Allow all the time in Android app settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestLocationPermissions(action: () -> Unit) {
        pendingLocationAction = action
        if (!hasForegroundLocationPermission()) {
            foregroundLocationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else continueLocationPermissionFlow()
    }

    private fun continueLocationPermissionFlow() {
        if (hasBackgroundLocationPermission()) {
            pendingLocationAction?.invoke()
        } else if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "On the next screen, choose App permissions > Location > Allow all the time, then return to SAM.", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName"))
            locationSettingsLauncher.launch(intent)
        } else {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    private var csvImportPreview by mutableStateOf<List<MessageEntity>>(emptyList())
    private var csvImportInvalidCount by mutableStateOf(0)
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = backupPassword
        backupPassword = ""
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val database = AppDatabase.get(this@MainActivity)
                val rows = database.messageDao().getAllSnapshot()
                val contacts = database.contactDao().getAllSnapshot()
                val root = JSONObject().put("format", "SAM_MESSAGE_SCHEDULER_BACKUP").put("version", 3)
                val array = JSONArray()
                rows.forEach { item ->
                    array.put(JSONObject().put("recipient", item.recipient).put("body", item.body)
                        .put("channel", item.channel).put("scheduledAt", item.scheduledAt)
                        .put("status", item.status).put("deliveryStatus", item.deliveryStatus).put("repeatRule", item.repeatRule).put("category", item.category).put("createdAt", item.createdAt)
                        .put("attachmentsJson", item.attachmentsJson).put("triggerType", item.triggerType)
                        .put("latitude", item.latitude).put("longitude", item.longitude)
                        .put("radiusMeters", item.radiusMeters).put("locationLabel", item.locationLabel)
                        .put("whatsappPackage", item.whatsappPackage))
                }
                val contactArray = JSONArray()
                contacts.forEach { contact ->
                    contactArray.put(JSONObject().put("id", contact.id).put("name", contact.name)
                        .put("phone", contact.phone).put("groupName", contact.groupName)
                        .put("birthday", contact.birthday).put("anniversary", contact.anniversary)
                        .put("createdAt", contact.createdAt))
                }
                root.put("messages", array).put("contacts", contactArray)
                val encrypted = BackupCrypto.encrypt(root.toString(), password.toCharArray())
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(encrypted) }
                    ?: error("Could not open backup file.")
                Toast.makeText(this@MainActivity, "Encrypted cloud-compatible backup exported (messages and contacts).", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Backup failed.", Toast.LENGTH_LONG).show() }
        }
    }
    private val csvExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val rows = AppDatabase.get(this@MainActivity).messageDao().getAllSnapshot()
                fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
                val header = listOf("ID", "Recipient", "Message", "Channel", "Scheduled At", "Status", "Delivery Status", "Repeat Rule", "Created At", "Category", "WhatsApp App")
                val lines = mutableListOf(header.joinToString(","))
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                rows.forEach { item ->
                    lines += listOf(
                        item.id.toString(), csv(item.recipient), csv(item.body), csv(item.channel),
                        csv(formatter.format(Date(item.scheduledAt))), csv(item.status), csv(item.deliveryStatus), csv(item.repeatRule),
                        csv(formatter.format(Date(item.createdAt))), csv(item.category), csv(item.whatsappPackage)
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
                val whatsappAppIndex = header.indexOf("whatsapp app")
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
                        require(repeat in listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY"))
                        require(scheduledAt > System.currentTimeMillis())
                        MessageEntity(recipient = recipient, body = body,
                            channel = when (channel.uppercase(Locale.US)) { "SMS" -> "SMS"; "SOCIAL" -> "Social"; else -> "WhatsApp" },
                            scheduledAt = scheduledAt, repeatRule = repeat, category = row.getOrElse(header.indexOf("category")) { "General" }.ifBlank { "General" },
                            whatsappPackage = if ((channel.equals("WhatsApp", true) || channel.equals("Social", true)) && whatsappAppIndex >= 0) row.getOrElse(whatsappAppIndex) { "" }.trim() else "")
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
        val password = backupPassword
        backupPassword = ""
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read backup file.")
                val decrypted = if (JSONObject(raw).optString("format") == "SAM_ENCRYPTED_BACKUP") {
                    BackupCrypto.decrypt(raw, password.toCharArray())
                } else raw
                val root = JSONObject(decrypted)
                require(root.optString("format") == "SAM_MESSAGE_SCHEDULER_BACKUP") { "Invalid SAM backup file." }
                val array = root.getJSONArray("messages")
                val restored = mutableListOf<MessageEntity>()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    restored += MessageEntity(recipient = item.getString("recipient"), body = item.getString("body"),
                        channel = item.getString("channel"), scheduledAt = item.getLong("scheduledAt"),
                        status = item.optString("status", "SCHEDULED"), deliveryStatus = item.optString("deliveryStatus", "PENDING"), repeatRule = item.optString("repeatRule", "NONE"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()), category = item.optString("category", "General"),
                        attachmentsJson = item.optString("attachmentsJson", "[]"), triggerType = item.optString("triggerType", "TIME"),
                        latitude = if (item.isNull("latitude")) null else item.optDouble("latitude"),
                        longitude = if (item.isNull("longitude")) null else item.optDouble("longitude"),
                        radiusMeters = item.optDouble("radiusMeters", 150.0).toFloat(), locationLabel = item.optString("locationLabel", ""),
                        whatsappPackage = item.optString("whatsappPackage", ""))
                }
                val database = AppDatabase.get(this@MainActivity)
                val dao = database.messageDao()
                dao.insertAll(restored)
                val restoredContacts = mutableListOf<ContactEntity>()
                val contactArray = root.optJSONArray("contacts") ?: JSONArray()
                for (index in 0 until contactArray.length()) {
                    val item = contactArray.getJSONObject(index)
                    restoredContacts += ContactEntity(
                        id = item.optLong("id", 0L),
                        name = item.getString("name"),
                        phone = item.getString("phone"),
                        groupName = item.optString("groupName", "General"),
                        birthday = item.optString("birthday", ""),
                        anniversary = item.optString("anniversary", ""),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                if (restoredContacts.isNotEmpty()) database.contactDao().insertAll(restoredContacts)
                val all = dao.getAllSnapshot()
                all.filter { it.status == "SCHEDULED" && it.triggerType == "TIME" && it.scheduledAt > System.currentTimeMillis() }
                    .forEach { MessageScheduler.schedule(this@MainActivity, it.id, it.scheduledAt) }
                Toast.makeText(this@MainActivity, "Backup restored: messages and contacts.", Toast.LENGTH_LONG).show()
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
    private val recipientContactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            runCatching {
                val contactId = contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: error("Could not read the selected contact.")
                val phone = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId), null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: error("The selected contact has no saved phone number.")
                pickedRecipientNumber = phone
            }.onFailure { Toast.makeText(this, it.message ?: "Could not read the selected contact.", Toast.LENGTH_LONG).show() }
        }
    }
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            pickedAttachmentUris = (pickedAttachmentUris + uris.map { it.toString() }).distinct()
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
        themeMode = getSharedPreferences("sam_appearance", android.content.Context.MODE_PRIVATE).getString("theme_mode", "System") ?: "System"
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        AnniversaryReminderScheduler.ensureScheduled(this)
        val dao = AppDatabase.get(this).messageDao()
        // Rebuild pending time jobs and location geofences whenever SAM opens.
        // This also repairs schedules after permission changes, app updates, and missed system events.
        lifecycleScope.launch {
            dao.getAllSnapshot()
                .filter { it.status == "SCHEDULED" }
                .forEach { message ->
                    if (message.triggerType == "TIME") {
                        MessageScheduler.schedule(this@MainActivity, message.id, message.scheduledAt)
                    } else if (message.triggerType == "LOCATION" && message.latitude != null && message.longitude != null) {
                        runCatching {
                            LocationGeofenceManager.register(this@MainActivity, message.id, message.latitude, message.longitude, message.radiusMeters)
                                .addOnFailureListener { failure ->
                                    android.util.Log.e("SAMRestore", "Could not restore arrival reminder ${message.id}", failure)
                                }
                        }.onFailure { failure ->
                            android.util.Log.w("SAMRestore", "Arrival reminder ${message.id} needs location permissions.", failure)
                        }
                    }
                }
        }
        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val useDark = when (themeMode) { "Dark" -> true; "Light" -> false; else -> systemDark }
            val appColors = if (useDark) darkColorScheme(
                primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
                secondary = Color(0xFFCCC2DC), background = Color(0xFF101218),
                surface = Color(0xFF191C24), surfaceVariant = Color(0xFF292D38),
                onSurface = Color(0xFFE5E1EA), onBackground = Color(0xFFE5E1EA),
                onSurfaceVariant = Color(0xFFC4C6D0), outline = Color(0xFF8E9099)
            ) else lightColorScheme(primary = Purple, background = Color(0xFFF5F3FA), surface = Color.White)
            MaterialTheme(colorScheme = appColors) {
                val messages by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val contacts by AppDatabase.get(this@MainActivity).contactDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                SchedulerScreen(
                    contacts = contacts,
                    pickedRecipientNumber = pickedRecipientNumber,
                    pickedAttachmentUris = pickedAttachmentUris,
                    onPickAttachments = { mediaPicker.launch(arrayOf("image/*", "video/*", "audio/*")) },
                    onCapturePhoto = { captureMedia(false) },
                    onCaptureVideo = { captureMedia(true) },
                    onPickRecipient = { recipientContactPicker.launch(null) },
                    onPickContact = { deviceContactPicker.launch(null) },
                    saveContact = { contact -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().insert(contact) } },
                    updateContact = { contact -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().update(contact) } },
                    deleteContact = { id -> lifecycleScope.launch { AppDatabase.get(this@MainActivity).contactDao().delete(id) } },
                    messages = messages,
                    onBackup = { password -> backupPassword = password; backupLauncher.launch("sam-message-scheduler-backup.json") },
                    onRestore = { password -> backupPassword = password; restoreLauncher.launch(arrayOf("application/json", "text/*")) },
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
                    save = { existingId, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage ->
                        val persistSchedule: () -> Unit = {
                            lifecycleScope.launch {
                            if (existingId != null) {
                                val current = dao.getById(existingId)
                                if (current != null) {
                                    MessageScheduler.cancel(this@MainActivity, existingId)
                                    if (current.triggerType == "LOCATION") LocationGeofenceManager.remove(this@MainActivity, existingId)
                                    dao.update(current.copy(
                                        recipient = recipient,
                                        body = body,
                                        channel = channel,
                                        scheduledAt = scheduledAt,
                                        repeatRule = repeatRule,
                                        category = category,
                                        attachmentsJson = org.json.JSONArray(attachments).toString(),
                                        triggerType = triggerType,
                                        latitude = latitude,
                                        longitude = longitude,
                                        radiusMeters = radiusMeters,
                                        locationLabel = locationLabel,
                                        whatsappPackage = if (channel == "WhatsApp" || channel == "Social") whatsappPackage else "",
                                        status = "SCHEDULED"
                                    ))
                                    if (triggerType == "LOCATION" && latitude != null && longitude != null) {
                                        runCatching { LocationGeofenceManager.register(this@MainActivity, existingId, latitude, longitude, radiusMeters) }
                                            .onSuccess { task ->
                                                task.addOnSuccessListener {
                                                    Toast.makeText(this@MainActivity, "Arrival reminder is active for $locationLabel.", Toast.LENGTH_LONG).show()
                                                }.addOnFailureListener { failure ->
                                                    Toast.makeText(this@MainActivity, failure.message ?: "Android could not activate the arrival reminder. Check Location permissions and Google Play services.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            .onFailure { failure ->
                                                Toast.makeText(this@MainActivity, failure.message ?: "Location permission is required.", Toast.LENGTH_LONG).show()
                                            }
                                    } else MessageScheduler.schedule(this@MainActivity, existingId, scheduledAt)
                                }
                            } else {
                                val id = dao.insert(MessageEntity(
                                    recipient = recipient,
                                    body = body,
                                    channel = channel,
                                    scheduledAt = scheduledAt,
                                    repeatRule = repeatRule,
                                    category = category,
                                    attachmentsJson = org.json.JSONArray(attachments).toString(),
                                    triggerType = triggerType,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMeters = radiusMeters,
                                    locationLabel = locationLabel,
                                    whatsappPackage = if (channel == "WhatsApp" || channel == "Social") whatsappPackage else ""
                                ))
                                if (triggerType == "LOCATION" && latitude != null && longitude != null) {
                                    runCatching { LocationGeofenceManager.register(this@MainActivity, id, latitude, longitude, radiusMeters) }
                                        .onSuccess { task ->
                                            task.addOnSuccessListener {
                                                Toast.makeText(this@MainActivity, "Arrival reminder is active for $locationLabel.", Toast.LENGTH_LONG).show()
                                            }.addOnFailureListener { failure ->
                                                Toast.makeText(this@MainActivity, failure.message ?: "Android could not activate the arrival reminder. Check Location permissions and Google Play services.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        .onFailure { failure ->
                                            Toast.makeText(this@MainActivity, failure.message ?: "Location permission is required.", Toast.LENGTH_LONG).show()
                                        }
                                } else MessageScheduler.schedule(this@MainActivity, id, scheduledAt)
                            }
                            }
                        }
                        if (triggerType == "LOCATION") requestLocationPermissions(persistSchedule) else persistSchedule()
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
                            LocationGeofenceManager.remove(this@MainActivity, id)
                            dao.delete(id)
                        }
                    },
                    complete = { message ->
                        lifecycleScope.launch {
                            MessageScheduler.cancel(this@MainActivity, message.id)
                            LocationGeofenceManager.remove(this@MainActivity, message.id)
                            dao.update(message.copy(status = "COMPLETED"))
                        }
                    },
                    togglePause = { message ->
                        lifecycleScope.launch {
                            if (message.status == "SCHEDULED") {
                                MessageScheduler.cancel(this@MainActivity, message.id)
                                if (message.triggerType == "LOCATION") LocationGeofenceManager.remove(this@MainActivity, message.id)
                                dao.update(message.copy(status = "PAUSED"))
                            } else if (message.status == "PAUSED") {
                                if (message.triggerType == "LOCATION" && message.latitude != null && message.longitude != null) {
                                    requestLocationPermissions {
                                        runCatching {
                                            LocationGeofenceManager.register(this@MainActivity, message.id, message.latitude, message.longitude, message.radiusMeters)
                                                .addOnSuccessListener {
                                                    lifecycleScope.launch { dao.update(message.copy(status = "SCHEDULED")) }
                                                    Toast.makeText(this@MainActivity, "Arrival reminder resumed.", Toast.LENGTH_SHORT).show()
                                                }
                                                .addOnFailureListener { failure ->
                                                    Toast.makeText(this@MainActivity, failure.message ?: "Could not resume arrival reminder.", Toast.LENGTH_LONG).show()
                                                }
                                        }.onFailure { failure ->
                                            Toast.makeText(this@MainActivity, failure.message ?: "Location permission is required.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    val resumeAt = maxOf(message.scheduledAt, System.currentTimeMillis() + 5_000L)
                                    dao.update(message.copy(status = "SCHEDULED", scheduledAt = resumeAt))
                                    MessageScheduler.schedule(this@MainActivity, message.id, resumeAt)
                                }
                            }
                        }
                    },
                    retry = { message ->
                        val scheduleAgain: () -> Unit = {
                            lifecycleScope.launch {
                                val nextAttempt = System.currentTimeMillis() + 30_000L
                                dao.update(message.copy(status = "SCHEDULED", deliveryStatus = "PENDING", scheduledAt = nextAttempt))
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
    pickedRecipientNumber: String,
    pickedAttachmentUris: List<String>,
    onPickAttachments: () -> Unit,
    onCapturePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onPickRecipient: () -> Unit,
    onPickContact: () -> Unit,
    saveContact: (ContactEntity) -> Unit,
    updateContact: (ContactEntity) -> Unit,
    deleteContact: (Long) -> Unit,
    onBackup: (String) -> Unit,
    onRestore: (String) -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    onEnableSmsAutomation: () -> Unit,
    importPreview: List<MessageEntity>,
    invalidImportCount: Int,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    save: (Long?, String, String, String, Long, String, String, List<String>, String, Double?, Double?, Float, String, String) -> Unit,
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("SAM Message Scheduler", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp)
                    Text("Plan it. Remember it. Send it.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Home", "Scheduled", "Calendar", "Social", "Contacts", "Settings").forEach { name ->
                    val icon = when (name) {
                        "Home" -> Icons.Default.Home
                        "Scheduled" -> Icons.Default.Schedule
                        "Calendar" -> Icons.Default.CalendarMonth
                        "Social" -> Icons.Default.Campaign
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
                    containerColor = MaterialTheme.colorScheme.primary
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
                    Text("Good day 👋", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Your messages, organised and on time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("$attentionCount message(s) need attention", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Review permission issues, failed actions or WhatsApp messages awaiting you.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { page = "Scheduled"; statusFilter = "ATTENTION" }) { Text("Review") }
                            }
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Make every message count", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "Prepare an SMS or WhatsApp reminder in seconds.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Button(
                                onClick = { showComposer = true },
                                modifier = Modifier.padding(top = 14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
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
                    Text("MESSAGE REPORTS & ANALYTICS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Stat("Total messages", totalCount.toString(), Modifier.weight(1f))
                                Stat("Last 7 days", recentCount.toString(), Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Stat("Failed", failedCount.toString(), Modifier.weight(1f))
                                Stat("Delivered", deliveredCount.toString(), Modifier.weight(1f))
                            }
                            Text("Completion rate", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            val completionRate = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()
                            LinearProgressIndicator(progress = completionRate, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                            Text("${(completionRate * 100).toInt()}% completed • $completedCount of $totalCount messages", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Channel distribution", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SMS", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("$smsCount", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("WhatsApp", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("$whatsappCount", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
                            Text("Delivery reports depend on your mobile network. A missing carrier delivery report does not necessarily mean a message failed.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Text(
                        "UPCOMING MESSAGES",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
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
                        (statusFilter == "ATTENTION" && message.status in listOf("FAILED", "PERMISSION_REQUIRED", "AWAITING_USER")) ||
                        (statusFilter == "DELIVERED" && message.deliveryStatus == "DELIVERED") ||
                        (statusFilter == "DELIVERY_ISSUES" && message.deliveryStatus in listOf("SEND_FAILED", "DELIVERY_FAILED"))) &&
                        (message.recipient.contains(searchText, true) ||
                            message.body.contains(searchText, true) ||
                            message.channel.contains(searchText, true) ||
                            message.category.contains(searchText, true))
                }.sortedBy { it.scheduledAt }
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Your messages", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                        label = { Text("Search recipient, message or category") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("ALL" to "All", "SCHEDULED" to "Upcoming", "PAUSED" to "Paused", "SENT" to "Sent", "FAILED" to "Failed", "DELIVERED" to "SMS delivered", "DELIVERY_ISSUES" to "SMS issues", "ATTENTION" to "Needs attention").forEach { (key, label) ->
                            FilterChip(selected = statusFilter == key, onClick = { statusFilter = key }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                    if (visibleMessages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No messages match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleMessages, key = { it.id }) { message ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                                            TextButton(onClick = { pendingRemoval = message }) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
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
                                save(null, contact.phone, personalised, channel, scheduledAt, "NONE", "Bulk", emptyList(), "TIME", null, null, 150f, "", "")
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
                        Text("Schedule calendar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    grouped.forEach { (day, dayMessages) ->
                        item(key = "day_$day") {
                            Text(
                                day,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                            )
                        }
                        items(dayMessages, key = { it.id }) { message ->
                            MessageCard(message)
                        }
                    }
                    if (messages.isEmpty()) {
                        item { Text("No messages scheduled yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            "Social" -> {
                val appContext = androidx.compose.ui.platform.LocalContext.current
                val socialPosts = messages.filter { it.channel == "Social" }.sortedBy { it.scheduledAt }
                val upcomingPosts = socialPosts.filter { it.status == "SCHEDULED" }
                val waitingPosts = socialPosts.filter { it.status == "AWAITING_USER" }
                val failedPosts = socialPosts.filter { it.status == "FAILED" || it.status == "PERMISSION_REQUIRED" }
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Social Media Centre", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Plan, prepare and track posts for your social channels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text("Publishing workspace", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text("Prepare a post with a caption and media, choose a social app, then schedule when SAM should bring it up for review.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Button(onClick = { showComposer = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Create social post")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("All posts", socialPosts.size.toString(), Modifier.weight(1f))
                        Stat("Upcoming", upcomingPosts.size.toString(), Modifier.weight(1f))
                        Stat("Needs review", waitingPosts.size.toString(), Modifier.weight(1f))
                    }
                    if (failedPosts.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${failedPosts.size} post(s) need attention", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Check the destination app, permissions and scheduled post details.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("SUPPORTED DESTINATIONS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    val destinations = listOf(
                        "Facebook" to "com.facebook.katana",
                        "Instagram" to "com.instagram.android",
                        "TikTok" to "com.zhiliaoapp.musically",
                        "Snapchat" to "com.snapchat.android",
                        "Telegram" to "org.telegram.messenger",
                        "X" to "com.twitter.android",
                        "LinkedIn" to "com.linkedin.android",
                        "Pinterest" to "com.pinterest"
                    )
                    destinations.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (label, packageName) ->
                                val installed = runCatching { appContext.packageManager.getPackageInfo(packageName, 0) }.isSuccess
                                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (installed) Icons.Default.CheckCircle else Icons.Default.Download, contentDescription = null, tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Column {
                                            Text(label, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                            Text(if (installed) "Installed" else "Not installed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("YOUR POST QUEUE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (socialPosts.isEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("No social posts yet", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Create your first post to start building your publishing calendar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        socialPosts.forEach { post ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(destinations.firstOrNull { it.second == post.whatsappPackage }?.first ?: "Social app", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                        Text(post.status.replace("_", " "), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(post.body, maxLines = 3, color = MaterialTheme.colorScheme.onSurface)
                                    Text(SimpleDateFormat("EEE, dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(post.scheduledAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { editMessage = post; showComposer = true }) { Text("Edit") }
                                        TextButton(onClick = { pendingRemoval = post }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                    Text("Direct API publishing will be enabled only after the relevant platform developer approvals and secure account connections are configured. Until then, SAM uses assisted sharing and requires you to review and publish in the destination app.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            "Settings" -> {
                Column(Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    // Reading this state makes the permission list recompose after returning from Android Settings.
                    val permissionRefresh = permissionRefreshTick.intValue
                    Text("Permissions centre", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
                    Text("SAM requests sensitive access only when a feature needs it. Photos and videos can be selected using Android's system picker without broad gallery access.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val permissionRows = listOf(
                        "Notifications" to android.Manifest.permission.POST_NOTIFICATIONS,
                        "Send SMS" to android.Manifest.permission.SEND_SMS,
                        "Receive SMS" to android.Manifest.permission.RECEIVE_SMS,
                        "Camera" to android.Manifest.permission.CAMERA,
                        "Location (while using)" to android.Manifest.permission.ACCESS_FINE_LOCATION,
                        "Location (background)" to android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                    permissionRows.forEach { (label, permission) ->
                        // Android may grant approximate location only; that is still valid foreground access.
                        val granted = when (permission) {
                            android.Manifest.permission.ACCESS_FINE_LOCATION ->
                                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
                                android.os.Build.VERSION.SDK_INT < 29 ||
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            android.Manifest.permission.POST_NOTIFICATIONS ->
                                android.os.Build.VERSION.SDK_INT < 33 ||
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            else ->
                                androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                Text(if (granted) "Allowed" else "Not allowed", color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                        context.startActivity(intent)
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Security, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Manage SAM permissions") }
                    val quietPrefs = remember { context.getSharedPreferences("sam_quiet_hours", android.content.Context.MODE_PRIVATE) }
                    var quietEnabled by remember { mutableStateOf(quietPrefs.getBoolean("enabled", false)) }
                    var quietStart by remember { mutableStateOf(quietPrefs.getString("start", "22:00") ?: "22:00") }
                    var quietEnd by remember { mutableStateOf(quietPrefs.getString("end", "07:00") ?: "07:00") }
                    var quietMessage by remember { mutableStateOf("") }
                    Text("Quiet hours", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
                    Text("Scheduled SMS and WhatsApp reminders will be deferred during this daily window. Your original schedule is preserved.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                    if (quietMessage.isNotBlank()) Text(quietMessage, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text("Incoming SMS automation", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 20.dp))
                    Text("Set optional automatic replies and forward incoming SMS messages to a number you choose.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val automationPrefs = remember { context.getSharedPreferences("sam_sms_automation", android.content.Context.MODE_PRIVATE) }
                    var autoReplyEnabled by remember { mutableStateOf(automationPrefs.getBoolean("auto_reply_enabled", false)) }
                    var replyRulesText by remember {
                        mutableStateOf(
                            automationPrefs.getString("reply_rules", null) ?: run {
                                val oldKeyword = automationPrefs.getString("reply_keyword", "").orEmpty()
                                val oldReply = automationPrefs.getString("reply_text", "I have received your message. I will get back to you soon.").orEmpty()
                                "$oldKeyword => $oldReply"
                            }
                        )
                    }
                    var forwardingEnabled by remember { mutableStateOf(automationPrefs.getBoolean("forward_enabled", false)) }
                    var forwardNumber by remember { mutableStateOf(automationPrefs.getString("forward_number", "") ?: "") }
                    var forwardKeyword by remember { mutableStateOf(automationPrefs.getString("forward_keyword", "") ?: "") }
                    var automationMessage by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(autoReplyEnabled, { autoReplyEnabled = it }); Text("Enable automatic replies") }
                    OutlinedTextField(
                        replyRulesText,
                        { replyRulesText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Automatic reply rules") },
                        supportingText = { Text("One rule per line: keyword => reply. Use an empty keyword for a default reply, preferably as the last rule.") },
                        minLines = 4
                    )
                    Text("The first matching rule is used. Each sender receives at most one automatic reply from the same rule every 12 hours.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Divider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(forwardingEnabled, { forwardingEnabled = it }); Text("Enable SMS forwarding") }
                    OutlinedTextField(forwardNumber, { forwardNumber = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Forward incoming SMS to") }, singleLine = true)
                    OutlinedTextField(forwardKeyword, { forwardKeyword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Forward only when message contains (optional)") }, supportingText = { Text("Leave blank to forward all incoming SMS.") })
                    Button(onClick = {
                        val parsedRules = replyRulesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { line ->
                            val separator = line.indexOf("=>")
                            if (separator < 0 || line.substring(separator + 2).isBlank()) null
                            else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
                        }.toList()
                        val hasMalformedRule = replyRulesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.any { line ->
                            val separator = line.indexOf("=>")
                            separator < 0 || line.substring(separator + 2).isBlank()
                        }
                        val hasDefaultBeforeLast = parsedRules.dropLast(1).any { it.first.isBlank() }
                        if (autoReplyEnabled && (parsedRules.isEmpty() || hasMalformedRule || hasDefaultBeforeLast)) {
                            automationMessage = "Enter valid reply rules using keyword => reply. Put any blank-keyword default rule last."
                        } else if (forwardingEnabled && forwardNumber.count { it.isDigit() } < 7) {
                            automationMessage = "Enter a valid forwarding number."
                        } else {
                            val firstRule = parsedRules.firstOrNull()
                            automationPrefs.edit()
                                .putBoolean("auto_reply_enabled", autoReplyEnabled)
                                .putString("reply_rules", parsedRules.joinToString("\n") { "${it.first} => ${it.second}" })
                                .putString("reply_keyword", firstRule?.first.orEmpty())
                                .putString("reply_text", firstRule?.second.orEmpty())
                                .putBoolean("forward_enabled", forwardingEnabled)
                                .putString("forward_number", forwardNumber.trim())
                                .putString("forward_keyword", forwardKeyword.trim())
                                .apply()
                            if (autoReplyEnabled || forwardingEnabled) onEnableSmsAutomation()
                            automationMessage = if (autoReplyEnabled || forwardingEnabled) "Rules saved. Grant incoming SMS permission when Android asks." else "Incoming SMS automation disabled."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save SMS automation rules") }
                    if (automationMessage.isNotBlank()) Text(automationMessage, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text("Requires incoming SMS and SMS sending permissions. Carrier charges may apply. Forwarding copies message content to the destination number.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text("App security", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp))
                    val securityPrefs = remember { context.getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE) }
                    var biometricEnabled by remember { mutableStateOf(securityPrefs.getBoolean("biometric_enabled", false)) }
                    var securityMessage by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Require biometric / device PIN", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Request fingerprint, face unlock, or Android screen-lock PIN when opening SAM.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
                    if (securityMessage.isNotBlank()) Text(securityMessage, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text("Data protection", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
                    var backupPasswordInput by remember { mutableStateOf("") }
                    var backupPasswordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = backupPasswordInput,
                        onValueChange = { backupPasswordInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Encrypted backup password") },
                        supportingText = { Text("At least 8 characters. Remember this password; SAM cannot recover it.") },
                        singleLine = true,
                        visualTransformation = if (backupPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { backupPasswordVisible = !backupPasswordVisible }) { Icon(if (backupPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle password visibility") } }
                    )
                    Text("Full encrypted backup includes messages, categories, contacts, groups, birthdays and anniversaries. Choose Google Drive or another cloud-storage provider in the Android file picker. Restore on another device using the same password. Existing message-only backups remain supported.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        OutlinedButton(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Set a backup password of at least 8 characters." else onBackup(backupPasswordInput) }, modifier = Modifier.weight(1f)) { Text("Cloud backup") }
                        Button(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Enter the password used for this backup." else onRestore(backupPasswordInput) }, modifier = Modifier.weight(1f)) { Text("Restore cloud backup") }
                    }
                    if (securityMessage.isNotBlank()) Text(securityMessage, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)

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
            pickedRecipientNumber = pickedRecipientNumber,
            pickedAttachmentUris = pickedAttachmentUris,
            onPickAttachments = onPickAttachments,
            onCapturePhoto = onCapturePhoto,
            onCaptureVideo = onCaptureVideo,
            onPickRecipient = onPickRecipient,
            duplicateMode = duplicateMode,
            onClose = { showComposer = false; editMessage = null; duplicateMode = false },
            requestSmsPermission = requestSmsPermission,
            onSave = { id, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage ->
                save(if (duplicateMode) null else id, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage)
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
                        Text(item.body, maxLines = 2, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.scheduledAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
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
                }) { Text("Remove message", color = MaterialTheme.colorScheme.error) }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onClose,
        title = { Text("Bulk message") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * 0.62f).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Select recipients", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedIds.size} of ${contacts.size} selected", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
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
                            Text(contact.name, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${contact.phone} • ${contact.groupName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Message template", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Message") }, supportingText = { Text("Use {name} and {phone} to personalise each message.") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = channel == "SMS", onClick = { channel = "SMS" }, label = { Text("SMS") })
                    FilterChip(selected = channel == "WhatsApp", onClick = { channel = "WhatsApp" }, label = { Text("WhatsApp") })
                }
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Time (24-hour HH:mm)") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Text("Each recipient gets a separate scheduled message. WhatsApp requires you to review and tap Send when the chat opens.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Contact centre", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("${contacts.size} saved contact(s)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onPickContact) { Icon(Icons.Default.ContactPhone, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("From phone") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Search contacts") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            groups.forEach { group -> FilterChip(selected = selectedGroup == group, onClick = { selectedGroup = group }, label = { Text(group) }) }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (contacts.isEmpty()) "No contacts saved yet. Add one from your phone." else "No contacts match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { contact ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(contact.phone, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(contact.groupName, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                            }
                            TextButton(onClick = { editing = contact; showEditor = true }) { Text("Edit") }
                            TextButton(onClick = { deleting = contact }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
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
            confirmButton = { TextButton(onClick = { deleteContact(contact.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(message.recipient, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(message.body, maxLines = 2, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message.category, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "${message.channel} • " + SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                    .format(Date(message.scheduledAt)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(message.status.lowercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.channel == "SMS") Text(message.deliveryStatus.replace("_", " ").lowercase(), fontSize = 9.sp, color = if (message.deliveryStatus == "DELIVERED") Color(0xFF237A45) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    initialMessage: MessageEntity?,
    pickedRecipientNumber: String,
    pickedAttachmentUris: List<String>,
    onPickAttachments: () -> Unit,
    onCapturePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onPickRecipient: () -> Unit,
    duplicateMode: Boolean = false,
    onClose: () -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    onSave: (Long?, String, String, String, Long, String, String, List<String>, String, Double?, Double?, Float, String, String) -> Unit
) {
    var recipient by remember(initialMessage?.id) { mutableStateOf(initialMessage?.recipient ?: "") }
    LaunchedEffect(pickedRecipientNumber) { if (pickedRecipientNumber.isNotBlank()) recipient = pickedRecipientNumber }
    var body by remember(initialMessage?.id) { mutableStateOf(initialMessage?.body ?: "") }
    var channel by remember(initialMessage?.id) { mutableStateOf(initialMessage?.channel ?: "SMS") }
    var whatsappPackage by remember(initialMessage?.id) { mutableStateOf(initialMessage?.whatsappPackage ?: "") }
    var whatsappMenuExpanded by remember { mutableStateOf(false) }
    var socialMenuExpanded by remember { mutableStateOf(false) }
    var date by remember(initialMessage?.id) { mutableStateOf(initialMessage?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.scheduledAt)) } ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 3600000))) }
    var time by remember(initialMessage?.id) { mutableStateOf(initialMessage?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.scheduledAt)) } ?: SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + 3600000))) }
    var repeatRule by remember(initialMessage?.id) { mutableStateOf(initialMessage?.repeatRule ?: "NONE") }
    var category by remember(initialMessage?.id) { mutableStateOf(initialMessage?.category ?: "General") }
    var triggerType by remember(initialMessage?.id) { mutableStateOf(initialMessage?.triggerType ?: "TIME") }
    var latitudeText by remember(initialMessage?.id) { mutableStateOf(initialMessage?.latitude?.toString() ?: "") }
    var longitudeText by remember(initialMessage?.id) { mutableStateOf(initialMessage?.longitude?.toString() ?: "") }
    var radiusText by remember(initialMessage?.id) { mutableStateOf(initialMessage?.radiusMeters?.toInt()?.toString() ?: "150") }
    var locationLabel by remember(initialMessage?.id) { mutableStateOf(initialMessage?.locationLabel ?: "") }
    var locationSearch by remember(initialMessage?.id) { mutableStateOf(initialMessage?.locationLabel ?: "") }
    var isSearchingLocation by remember { mutableStateOf(false) }
    var locationSearchMessage by remember { mutableStateOf("") }
    val locationSearchScope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var attachments by remember(initialMessage?.id) {
        mutableStateOf(
            initialMessage?.attachmentsJson?.let { raw ->
                runCatching {
                    org.json.JSONArray(raw).let { array ->
                        (0 until array.length()).map { index -> array.getString(index) }
                    }
                }.getOrDefault(emptyList())
            } ?: emptyList()
        )
    }
    LaunchedEffect(pickedAttachmentUris) {
        attachments = (attachments + pickedAttachmentUris).distinct()
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val whatsappApps = remember(context.packageManager) {
        listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business").mapNotNull { (packageName, fallbackLabel) ->
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                WhatsAppAppOption(packageName, context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { fallbackLabel })
            }.getOrNull()
        }
    }
    val selectedWhatsAppMissing = whatsappPackage.isNotBlank() && whatsappApps.none { it.packageName == whatsappPackage }

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
                    FilterChip(selected = channel == "Social", onClick = { channel = "Social" }, label = { Text("Social media") })
                }
                if (channel == "Social") {
                    val socialTargets = listOf(
                        "com.facebook.katana" to "Facebook",
                        "com.instagram.android" to "Instagram",
                        "com.zhiliaoapp.musically" to "TikTok",
                        "com.snapchat.android" to "Snapchat",
                        "org.telegram.messenger" to "Telegram",
                        "com.twitter.android" to "X",
                        "com.linkedin.android" to "LinkedIn",
                        "com.pinterest" to "Pinterest"
                    )
                    Text("Social destination", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Box {
                        OutlinedButton(onClick = { socialMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(socialTargets.firstOrNull { it.first == whatsappPackage }?.second ?: "Choose social app", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose social app")
                        }
                        DropdownMenu(expanded = socialMenuExpanded, onDismissRequest = { socialMenuExpanded = false }) {
                            socialTargets.forEach { (pkg, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    trailingIcon = { if (whatsappPackage == pkg) Icon(Icons.Default.Check, contentDescription = "Selected") },
                                    onClick = { whatsappPackage = pkg; socialMenuExpanded = false }
                                )
                            }
                        }
                    }
                    Text("At the scheduled time SAM opens the selected app with your text and media ready. Review and publish inside that app. Direct publishing will activate only for accounts and platforms whose official API access has been approved and connected.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (channel == "WhatsApp") {
                    Text("WhatsApp application", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Box {
                        OutlinedButton(onClick = { whatsappMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(whatsappApps.firstOrNull { it.packageName == whatsappPackage }?.label ?: "Ask every time", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose WhatsApp application")
                        }
                        DropdownMenu(expanded = whatsappMenuExpanded, onDismissRequest = { whatsappMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Ask every time") }, onClick = { whatsappPackage = ""; whatsappMenuExpanded = false })
                            whatsappApps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app.label) },
                                    leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null) },
                                    trailingIcon = { if (whatsappPackage == app.packageName) Icon(Icons.Default.Check, contentDescription = "Selected") },
                                    onClick = { whatsappPackage = app.packageName; whatsappMenuExpanded = false }
                                )
                            }
                        }
                    }
                    if (whatsappApps.isEmpty()) {
                        Text("No supported WhatsApp app was detected. Install WhatsApp or WhatsApp Business, or choose Ask every time to let Android handle the link.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selectedWhatsAppMissing) {
                        Text("Your previously selected WhatsApp app is no longer available. Choose another app or Ask every time.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Text("The selected app will be opened when this reminder is due. You will still review and send the message yourself.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Quick templates", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                Text("Category", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("General", "Personal", "Family", "Work", "School", "Business", "Bulk", "Birthday", "Reminder").forEach { value ->
                        FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) })
                    }
                }
                Text("Schedule trigger", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = triggerType == "TIME", onClick = { triggerType = "TIME" }, label = { Text("Date & time") })
                    FilterChip(selected = triggerType == "LOCATION", onClick = { triggerType = "LOCATION" }, label = { Text("When I arrive") })
                }
                if (triggerType == "LOCATION") {
                    Text("Search for a destination to fill in its coordinates. Place lookup may require an internet connection and availability of Android's geocoder. You can still enter coordinates manually.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = locationSearch,
                        onValueChange = { locationSearch = it; locationSearchMessage = "" },
                        label = { Text("Search place or address") },
                        placeholder = { Text("e.g. Kejetia Market, Kumasi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val query = locationSearch.trim()
                            if (query.length < 3) {
                                locationSearchMessage = "Enter at least 3 characters to search."
                            } else {
                                isSearchingLocation = true
                                locationSearchMessage = "Searching for the place…"
                                locationSearchScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            @Suppress("DEPRECATION")
                                            Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
                                        }.getOrNull()?.firstOrNull()
                                    }
                                    isSearchingLocation = false
                                    if (result != null) {
                                        latitudeText = result.latitude.toString()
                                        longitudeText = result.longitude.toString()
                                        locationLabel = result.getAddressLine(0) ?: query
                                        locationSearch = locationLabel
                                        locationSearchMessage = "Location found. Confirm the coordinates below."
                                    } else {
                                        locationSearchMessage = "No result found. Check the spelling or enter coordinates manually."
                                    }
                                }
                            }
                        },
                        enabled = !isSearchingLocation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSearchingLocation) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isSearchingLocation) "Searching…" else "Find destination")
                    }
                    if (locationSearchMessage.isNotBlank()) {
                        Text(locationSearchMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(locationLabel, { locationLabel = it }, label = { Text("Selected place / address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(latitudeText, { latitudeText = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(longitudeText, { longitudeText = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedTextField(radiusText, { radiusText = it }, label = { Text("Arrival radius (metres, 100–1000)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                Text("Repeat", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("NONE" to "Never", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly").forEach { (key, label) ->
                        FilterChip(selected = repeatRule == key, onClick = { repeatRule = key }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = recipient,
                        onValueChange = { recipient = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Recipient / phone number") },
                        singleLine = true
                    )
                    OutlinedButton(onClick = onPickRecipient) {
                        Icon(Icons.Default.Contacts, contentDescription = "Choose contact")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCapturePhoto,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Photo", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onCaptureVideo,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Video", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onPickAttachments,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Media", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
                if (attachments.isNotEmpty()) {
                    Text("${attachments.size} attachment(s) selected", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    attachments.forEach { uri -> Row(verticalAlignment = Alignment.CenterVertically) { Text(uri.substringAfterLast("/"), modifier = Modifier.weight(1f), maxLines = 1); IconButton(onClick = { attachments = attachments - uri }) { Icon(Icons.Default.Close, contentDescription = "Remove attachment") } } }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val cal = java.util.Calendar.getInstance().apply { setTime(runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!! }.getOrDefault(Date())) }
                        android.app.DatePickerDialog(context, { _, y, m, d -> date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d) }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CalendarMonth, contentDescription = null); Text("Choose date") }
                    OutlinedButton(onClick = {
                        val cal = java.util.Calendar.getInstance().apply { setTime(runCatching { SimpleDateFormat("HH:mm", Locale.US).parse(time)!! }.getOrDefault(Date())) }
                        android.app.TimePickerDialog(context, { _, h, m -> time = String.format(Locale.US, "%02d:%02d", h, m) }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Schedule, contentDescription = null); Text("Choose time") }
                }
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
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Text(
                    "SMS is submitted automatically with permission. WhatsApp opens a prepared chat for your final confirmation.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            triggerType == "TIME" && scheduledAt <= System.currentTimeMillis() -> error = "Choose a future date and time."
                            triggerType == "LOCATION" && ((latitudeText.toDoubleOrNull()?.let { it !in -90.0..90.0 } ?: true) || (longitudeText.toDoubleOrNull()?.let { it !in -180.0..180.0 } ?: true)) -> error = "Enter valid latitude and longitude coordinates."
                            triggerType == "LOCATION" && (radiusText.toFloatOrNull()?.let { it !in 100f..1000f } ?: true) -> error = "Arrival radius must be between 100 and 1000 metres."
                            else -> onSave(initialMessage?.id, recipient.trim(), body.trim(), channel, scheduledAt, repeatRule, category, attachments, triggerType, latitudeText.toDoubleOrNull(), longitudeText.toDoubleOrNull(), radiusText.toFloatOrNull() ?: 150f, locationLabel.trim(), whatsappPackage)
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
