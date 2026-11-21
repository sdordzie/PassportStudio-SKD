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
import com.bigsammy.sammessagescheduler.data.MessageAttemptEntity
import com.bigsammy.sammessagescheduler.data.ContactEntity
import com.bigsammy.sammessagescheduler.scheduling.MessageScheduler
import com.bigsammy.sammessagescheduler.scheduling.LocationGeofenceManager
import com.bigsammy.sammessagescheduler.scheduling.AnniversaryReminderScheduler
import com.bigsammy.sammessagescheduler.security.BackupCrypto
import com.bigsammy.sammessagescheduler.ui.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class WhatsAppAppOption(val packageName: String, val label: String)
private data class SocialCampaign(
    val id: String,
    val name: String,
    val objective: String,
    val startAt: Long,
    val endAt: Long,
    val platforms: List<String>
)
private data class SocialCaptionTemplate(
    val name: String,
    val platform: String,
    val caption: String
)

private val Purple = Color(0xFF6750A4)
// Shared Compose state lets the settings screen refresh when the Activity resumes.
private val permissionRefreshTick = mutableIntStateOf(0)

class MainActivity : FragmentActivity() {
    private var backupPassword: String = ""
    private var themeMode by mutableStateOf("System")
    private var pickedRecipientNumber by mutableStateOf("")
    private var phoneContacts by mutableStateOf<List<ContactEntity>>(emptyList())
    private var contactsPermissionGranted by mutableStateOf(false)
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
        contactsPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (contactsPermissionGranted) loadPhoneContacts()
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
                if (!securityAuthenticated && getSharedPreferences("sam_app_security", android.content.Context.MODE_PRIVATE)
                        .getBoolean("biometric_enabled", false)) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(this@MainActivity, "App authentication was not completed.", Toast.LENGTH_LONG).show()
                    }
                    // Do not leave protected content visible after a cancelled or failed unlock.
                    finish()
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
    private val deviceContactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsPermissionGranted = granted
        if (granted) loadPhoneContacts() else Toast.makeText(this, "Phone contacts access was not granted. SAM contacts and the Android contact picker remain available.", Toast.LENGTH_LONG).show()
    }
    private fun requestDeviceContactsAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            contactsPermissionGranted = true
            loadPhoneContacts()
        } else deviceContactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }
    private fun loadPhoneContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            contactsPermissionGranted = false; phoneContacts = emptyList(); return
        }
        lifecycleScope.launch {
            phoneContacts = withContext(Dispatchers.IO) {
                runCatching {
                    val result = mutableListOf<ContactEntity>()
                    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
                    contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { cursor ->
                        val id = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                        val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val number = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (cursor.moveToNext()) {
                            val n = cursor.getString(name)?.trim().orEmpty()
                            val p = cursor.getString(number)?.trim().orEmpty()
                            if (n.isNotBlank() && p.count { it.isDigit() } >= 7) result += ContactEntity(id = -cursor.getLong(id), name = n, phone = p, groupName = "Phone contact")
                        }
                    }
                    result.distinctBy { it.phone.filter(Char::isDigit) }
                }.getOrElse { android.util.Log.e("SAMContacts", "Device contacts query failed", it); emptyList() }
            }
        }
    }
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
                val attemptRows = database.messageAttemptDao().getAllSnapshot()
                val root = JSONObject().put("format", "SAM_MESSAGE_SCHEDULER_BACKUP").put("version", 4)
                val array = JSONArray()
                rows.forEach { item ->
                    array.put(JSONObject().put("id", item.id).put("recipient", item.recipient).put("body", item.body)
                        .put("channel", item.channel).put("scheduledAt", item.scheduledAt)
                        .put("status", item.status).put("deliveryStatus", item.deliveryStatus).put("repeatRule", item.repeatRule).put("category", item.category).put("createdAt", item.createdAt)
                        .put("attachmentsJson", item.attachmentsJson).put("triggerType", item.triggerType)
                        .put("latitude", item.latitude).put("longitude", item.longitude)
                        .put("radiusMeters", item.radiusMeters).put("locationLabel", item.locationLabel)
                        .put("whatsappPackage", item.whatsappPackage).put("campaignId", item.campaignId))
                }
                val attemptArray = JSONArray()
                attemptRows.forEach { attempt ->
                    attemptArray.put(JSONObject().put("messageId", attempt.messageId).put("attemptId", attempt.attemptId)
                        .put("recipient", attempt.recipient).put("channel", attempt.channel).put("eventType", attempt.eventType)
                        .put("partIndex", attempt.partIndex).put("partCount", attempt.partCount).put("outcome", attempt.outcome)
                        .put("resultCode", attempt.resultCode).put("detail", attempt.detail).put("occurredAt", attempt.occurredAt))
                }
                val contactArray = JSONArray()
                contacts.forEach { contact ->
                    contactArray.put(JSONObject().put("id", contact.id).put("name", contact.name)
                        .put("phone", contact.phone).put("groupName", contact.groupName)
                        .put("birthday", contact.birthday).put("anniversary", contact.anniversary)
                        .put("createdAt", contact.createdAt))
                }
                val preferences = getSharedPreferences("sam_social_campaigns", android.content.Context.MODE_PRIVATE)
                val templates = getSharedPreferences("sam_social_templates", android.content.Context.MODE_PRIVATE)
                root.put("messages", array).put("contacts", contactArray).put("attempts", attemptArray)
                    .put("campaigns", JSONArray(preferences.getString("campaigns", "[]") ?: "[]"))
                    .put("captionTemplates", JSONArray(templates.getString("templates", "[]") ?: "[]"))
                val passwordChars = password.toCharArray()
                val encrypted = try { BackupCrypto.encrypt(root.toString(), passwordChars) } finally { passwordChars.fill('\u0000') }
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
                        require(channel.equals("SMS", true) || channel.equals("WhatsApp", true) || channel.equals("Social", true))
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
                val database = AppDatabase.get(this@MainActivity)
                val dao = database.messageDao()
                val restoredIdMap = mutableMapOf<Long, Long>()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val restoredMessage = MessageEntity(recipient = item.getString("recipient"), body = item.getString("body"),
                        channel = item.getString("channel"), scheduledAt = item.getLong("scheduledAt"),
                        status = item.optString("status", "SCHEDULED"), deliveryStatus = item.optString("deliveryStatus", "PENDING"), repeatRule = item.optString("repeatRule", "NONE"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()), category = item.optString("category", "General"),
                        attachmentsJson = item.optString("attachmentsJson", "[]"), triggerType = item.optString("triggerType", "TIME"),
                        latitude = if (item.isNull("latitude")) null else item.optDouble("latitude"),
                        longitude = if (item.isNull("longitude")) null else item.optDouble("longitude"),
                        radiusMeters = item.optDouble("radiusMeters", 150.0).toFloat(), locationLabel = item.optString("locationLabel", ""),
                        whatsappPackage = item.optString("whatsappPackage", ""), campaignId = item.optString("campaignId").takeIf { it.isNotBlank() })
                    val newId = dao.insert(restoredMessage)
                    val oldId = item.optLong("id", -1L)
                    if (oldId > 0L) restoredIdMap[oldId] = newId
                }
                val attemptArray = root.optJSONArray("attempts") ?: JSONArray()
                for (index in 0 until attemptArray.length()) {
                    val item = attemptArray.getJSONObject(index)
                    val newMessageId = restoredIdMap[item.optLong("messageId", -1L)] ?: continue
                    database.messageAttemptDao().insert(MessageAttemptEntity(
                        messageId = newMessageId,
                        attemptId = item.optString("attemptId", "restored_$index"),
                        recipient = item.optString("recipient", ""),
                        channel = item.optString("channel", "SMS"),
                        eventType = item.optString("eventType", "RESTORED_EVENT"),
                        partIndex = item.optInt("partIndex", -1),
                        partCount = item.optInt("partCount", 1),
                        outcome = item.optString("outcome", "UNKNOWN"),
                        resultCode = item.optInt("resultCode", 0),
                        detail = item.optString("detail", ""),
                        occurredAt = item.optLong("occurredAt", System.currentTimeMillis())
                    ))
                }
                val restoredContacts = mutableListOf<ContactEntity>()
                val contactArray = root.optJSONArray("contacts") ?: JSONArray()
                for (index in 0 until contactArray.length()) {
                    val item = contactArray.getJSONObject(index)
                    restoredContacts += ContactEntity(
                        id = 0L,
                        name = item.getString("name"),
                        phone = item.getString("phone"),
                        groupName = item.optString("groupName", "General"),
                        birthday = item.optString("birthday", ""),
                        anniversary = item.optString("anniversary", ""),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                if (restoredContacts.isNotEmpty()) database.contactDao().insertAll(restoredContacts)
                root.optJSONArray("campaigns")?.let { campaigns ->
                    getSharedPreferences("sam_social_campaigns", android.content.Context.MODE_PRIVATE)
                        .edit().putString("campaigns", campaigns.toString()).apply()
                }
                root.optJSONArray("captionTemplates")?.let { templates ->
                    getSharedPreferences("sam_social_templates", android.content.Context.MODE_PRIVATE)
                        .edit().putString("templates", templates.toString()).apply()
                }
                val all = dao.getAllSnapshot()
                all.filter { it.status == "SCHEDULED" && it.triggerType == "TIME" && it.scheduledAt > System.currentTimeMillis() }
                    .forEach { MessageScheduler.schedule(this@MainActivity, it.id, it.scheduledAt) }
                all.filter { it.status == "SCHEDULED" && it.triggerType == "LOCATION" && it.latitude != null && it.longitude != null }
                    .forEach { message ->
                        val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if ((fine || coarse) && background) {
                            runCatching {
                                LocationGeofenceManager.register(this@MainActivity, message.id, message.latitude!!, message.longitude!!, message.radiusMeters)
                            }.onFailure { android.util.Log.e("SAMRestore", "Could not restore arrival reminder ${message.id}", it) }
                        }
                    }
                Toast.makeText(this@MainActivity, "Backup restored: messages, contacts, campaigns and captions.", Toast.LENGTH_LONG).show()
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
            val appColors = if (useDark) SamDarkColors else SamLightColors
            MaterialTheme(colorScheme = appColors) {
                val messages by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val attempts by AppDatabase.get(this@MainActivity).messageAttemptDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val contacts by AppDatabase.get(this@MainActivity).contactDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val contactNames = remember(contacts, phoneContacts) {
                    (phoneContacts + contacts).filter { phoneKey(it.phone).length >= 7 }.associate { phoneKey(it.phone) to it.name }
                }
                CompositionLocalProvider(LocalContactNames provides contactNames) {
                SchedulerScreen(
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        themeMode = mode
                        getSharedPreferences("sam_appearance", android.content.Context.MODE_PRIVATE).edit().putString("theme_mode", mode).apply()
                    },
                    contacts = contacts,
                    phoneContacts = phoneContacts,
                    contactsPermissionGranted = contactsPermissionGranted,
                    onRequestDeviceContacts = { requestDeviceContactsAccess() },
                    onRefreshDeviceContacts = { loadPhoneContacts() },
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
                    attempts = attempts,
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
                    save = { existingId, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage, campaignId ->
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
                                         campaignId = if (channel == "Social") campaignId else null,
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
                                    whatsappPackage = if (channel == "WhatsApp" || channel == "Social") whatsappPackage else "",
                                     campaignId = if (channel == "Social") campaignId else null
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
    attempts: List<MessageAttemptEntity>,
    contacts: List<ContactEntity>,
    phoneContacts: List<ContactEntity>,
    contactsPermissionGranted: Boolean,
    onRequestDeviceContacts: () -> Unit,
    onRefreshDeviceContacts: () -> Unit,
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
    save: (Long?, String, String, String, Long, String, String, List<String>, String, Double?, Double?, Float, String, String, String?) -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    remove: (Long) -> Unit,
    complete: (MessageEntity) -> Unit,
    togglePause: (MessageEntity) -> Unit,
    retry: (MessageEntity) -> Unit,
    themeMode: String = "System",
    onThemeChange: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember { mutableStateOf("Home") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var duplicateMode by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<MessageEntity?>(null) }
    var searchText by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var composerChannel by remember { mutableStateOf("SMS") }
    var sortNewestFirst by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    fun openComposer(channel: String = "SMS") { composerChannel = channel; editMessage = null; duplicateMode = false; showComposer = true }
    fun notify(text: String) { uiScope.launch { snackbarHostState.currentSnackbarData?.dismiss(); snackbarHostState.showSnackbar(text) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(samHeroBrush(isDark)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.ScheduleSend, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SAM Message Scheduler", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (page) { "Home" -> "Dashboard"; "Scheduled" -> "All messages"; "Calendar" -> "Agenda"; "Social" -> "Social media centre"; "Contacts" -> "Contact centre"; else -> "Settings & tools" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val attention = messages.count { it.needsAttention() }
                if (attention > 0) {
                    BadgedBox(badge = { Badge { Text("$attention") } }, modifier = Modifier.padding(end = 8.dp)) {
                        IconButton(onClick = { page = "Scheduled"; statusFilter = "ATTENTION" }) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = "Messages needing attention", tint = SamColors.Amber)
                        }
                    }
                }
                IconButton(onClick = { page = "Settings" }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
                windowInsets = NavigationBarDefaults.windowInsets
            ) {
                val primaryTabs = listOf(
                    Triple("Home", "Home", Icons.Default.SpaceDashboard),
                    Triple("Scheduled", "Messages", Icons.Default.ScheduleSend),
                    Triple("Calendar", "Agenda", Icons.Default.CalendarMonth),
                    Triple("Social", "Social", Icons.Default.Campaign)
                )
                primaryTabs.forEach { (name, label, icon) ->
                    NavigationBarItem(
                        selected = page == name,
                        onClick = { page = name; showMoreMenu = false },
                        icon = {
                            if (name == "Scheduled" && messages.any { it.needsAttention() }) {
                                BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) }
                            } else Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                        },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        alwaysShowLabel = true
                    )
                }
                NavigationBarItem(
                    selected = page == "Contacts" || page == "Settings",
                    onClick = { showMoreMenu = true },
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "More", modifier = Modifier.size(21.dp)) },
                    label = { Text("More", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    alwaysShowLabel = true
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Contacts") },
                        leadingIcon = { Icon(Icons.Default.Contacts, contentDescription = null) },
                        onClick = { page = "Contacts"; showMoreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = { page = "Settings"; showMoreMenu = false }
                    )
                }
            }
        },
        floatingActionButton = {
            if (page != "Settings" && page != "Contacts") {
                ExtendedFloatingActionButton(
                    onClick = { openComposer(if (page == "Social") "Social" else "SMS") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text(if (page == "Social") "New post" else "New message", fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    ) { padding ->
        when (page) {
            "Home" -> {
                val now = System.currentTimeMillis()
                val upcoming = messages.filter { it.status == "SCHEDULED" }.sortedBy { if (it.triggerType == "LOCATION") Long.MAX_VALUE else it.scheduledAt }
                val nextTimed = upcoming.firstOrNull { it.triggerType == "TIME" && it.scheduledAt >= now - 60_000L }
                val todayCount = upcoming.count { it.triggerType == "TIME" && dayHeading(it.scheduledAt) == "Today" }
                val attentionCount = messages.count { it.needsAttention() }
                val sentCount = messages.count { (it.status == "SENT" || it.status == "COMPLETED") && it.deliveryStatus !in listOf("SEND_FAILED", "DELIVERY_FAILED") }
                val deliveredCount = messages.count { it.deliveryStatus == "DELIVERED" }
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Hero
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(samHeroBrush(isDark)).padding(20.dp)
                    ) {
                        Column {
                            Text("${greetingForNow()} 👋", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                when {
                                    upcoming.isEmpty() -> "Nothing is queued. Schedule your first message."
                                    todayCount > 0 -> "$todayCount message(s) going out today • ${upcoming.size} scheduled in total"
                                    else -> "${upcoming.size} message(s) scheduled and ready"
                                },
                                color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                            )
                            if (nextTimed != null) {
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f))
                                        .clickable { editMessage = nextTimed; duplicateMode = false; showComposer = true }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(channelIcon(nextTimed.channel), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("NEXT UP • ${countdown(nextTimed.scheduledAt).uppercase()}", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(nextTimed.recipient, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(friendlyDateTime(nextTimed.scheduledAt) + " • " + nextTimed.channel, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { openComposer("SMS") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SamColors.Indigo),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) { Icon(Icons.Default.Sms, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("SMS", fontWeight = FontWeight.SemiBold) }
                                OutlinedButton(
                                    onClick = { openComposer("WhatsApp") },
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) { Icon(Icons.Default.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("WhatsApp") }
                            }
                        }
                    }

                    if (attentionCount > 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { page = "Scheduled"; statusFilter = "ATTENTION" },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("$attentionCount message(s) need attention", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("Failed sends, missing permissions or WhatsApp chats waiting for your tap.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                                }
                                Text("Review", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    SectionHeader("Overview")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Scheduled", upcoming.size.toString(), Icons.Default.Schedule, SamColors.Indigo, Modifier.weight(1f)) { page = "Scheduled"; statusFilter = "SCHEDULED" }
                        StatTile("Sent / done", sentCount.toString(), Icons.Default.Send, SamColors.Teal, Modifier.weight(1f)) { page = "Scheduled"; statusFilter = "SENT" }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("SMS delivered", deliveredCount.toString(), Icons.Default.DoneAll, SamColors.Green, Modifier.weight(1f)) { page = "Scheduled"; statusFilter = "DELIVERED" }
                        StatTile("Need attention", attentionCount.toString(), Icons.Default.ErrorOutline, if (attentionCount > 0) SamColors.Red else SamColors.Slate, Modifier.weight(1f)) { page = "Scheduled"; statusFilter = "ATTENTION" }
                    }

                    SectionHeader("Quick actions")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple("Bulk message", Icons.Default.Groups) { page = "Contacts" },
                            Triple("Social post", Icons.Default.Campaign) { openComposer("Social") },
                            Triple("Import CSV", Icons.Default.FileUpload, onImportCsv),
                            Triple("Export CSV", Icons.Default.FileDownload, onExportCsv),
                            Triple("Backup", Icons.Default.CloudUpload) { page = "Settings" }
                        ).forEach { (label, icon, action) ->
                            AssistChip(
                                onClick = action,
                                label = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    SectionHeader("Up next", actionLabel = if (upcoming.size > 5) "See all ${upcoming.size}" else null, onAction = { page = "Scheduled"; statusFilter = "SCHEDULED" })
                    if (upcoming.isEmpty()) {
                        SamCard { EmptyState(Icons.Default.ScheduleSend, "No upcoming messages", "Tap New message to plan an SMS, WhatsApp reminder or social post.", actionLabel = "New message", onAction = { openComposer("SMS") }) }
                    } else {
                        SamCard {
                            upcoming.take(5).forEachIndexed { index, message ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                                MessageCard(message, onClick = { editMessage = message; duplicateMode = false; showComposer = true })
                            }
                        }
                    }

                    val totalCount = messages.size
                    val processedCount = messages.count { it.status == "COMPLETED" || (it.status == "SENT" && it.deliveryStatus !in listOf("SEND_FAILED", "DELIVERY_FAILED")) }
                    val recentCount = messages.count { it.createdAt >= now - 7L * 24 * 60 * 60 * 1000 }
                    SectionHeader("Insights")
                    SamCard {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("$totalCount", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Total messages", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("$recentCount", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Created last 7 days", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val completionRate = if (totalCount == 0) 0f else processedCount.toFloat() / totalCount.toFloat()
                        Row(Modifier.fillMaxWidth()) {
                            Text("Processed", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text("${(completionRate * 100).toInt()}%  •  $processedCount of $totalCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { completionRate },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                            color = SamColors.Teal,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Channel mix", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ShareBar("SMS", messages.count { it.channel == "SMS" }, totalCount, SamColors.Indigo)
                            ShareBar("WhatsApp", messages.count { it.channel == "WhatsApp" }, totalCount, SamColors.WhatsApp)
                            ShareBar("Social media", messages.count { it.channel == "Social" }, totalCount, SamColors.Social)
                        }
                        Text("Delivery reports depend on your mobile network. A missing carrier report does not necessarily mean a message failed.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }

                    SectionHeader("Recent SMS activity")
                    SamCard {
                        if (attempts.isEmpty()) {
                            Text("No SMS attempts recorded yet. Submission and carrier delivery events will appear here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val recentAttempts = attempts.take(8)
                            recentAttempts.forEachIndexed { index, attempt ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                                val ok = attempt.outcome != "FAILED"
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null, tint = if (ok) SamColors.Green else SamColors.Red, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(attempt.recipient, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                        val eventLabel = attempt.eventType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                                        val partLabel = if (attempt.partIndex >= 0) " • part ${attempt.partIndex + 1}/${attempt.partCount}" else ""
                                        Text("$eventLabel$partLabel • ${friendlyDateTime(attempt.occurredAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (attempt.detail.isNotBlank()) Text(attempt.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    StatusPill(attempt.outcome.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, if (ok) SamColors.Teal else SamColors.Red)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(88.dp))
                }
            }

            "Scheduled" -> {
                val filters = listOf(
                    "ALL" to "All", "SCHEDULED" to "Upcoming", "ATTENTION" to "Needs attention", "PAUSED" to "Paused",
                    "SENT" to "Sent", "DELIVERED" to "Delivered", "FAILED" to "Failed", "DELIVERY_ISSUES" to "SMS issues", "COMPLETED" to "Done"
                )
                fun matchesFilter(message: MessageEntity, key: String): Boolean = when (key) {
                    "ALL" -> true
                    "FAILED" -> message.isFailed()
                    "SENT" -> message.status == "SENT" && message.deliveryStatus !in listOf("SEND_FAILED", "DELIVERY_FAILED")
                    "ATTENTION" -> message.needsAttention()
                    "DELIVERED" -> message.deliveryStatus == "DELIVERED"
                    "DELIVERY_ISSUES" -> message.deliveryStatus in listOf("SEND_FAILED", "DELIVERY_FAILED")
                    else -> message.status == key
                }
                val visibleMessages = messages.filter { message ->
                    matchesFilter(message, statusFilter) &&
                        (searchText.isBlank() ||
                            message.recipient.contains(searchText, true) ||
                            message.body.contains(searchText, true) ||
                            message.channel.contains(searchText, true) ||
                            message.category.contains(searchText, true) ||
                            message.locationLabel.contains(searchText, true) ||
                            message.status.contains(searchText, true) ||
                            message.deliveryStatus.replace("_", " ").contains(searchText, true))
                }.let { list -> if (sortNewestFirst) list.sortedByDescending { it.scheduledAt } else list.sortedBy { it.scheduledAt } }
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(top = 10.dp)
                ) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("Search recipient, message, category…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = { if (searchText.isNotEmpty()) IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filters, key = { it.first }) { (key, label) ->
                            val count = messages.count { matchesFilter(it, key) }
                            val checkIcon: @Composable () -> Unit = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            FilterChip(
                                selected = statusFilter == key,
                                onClick = { statusFilter = key },
                                label = { Text("$label  $count", fontSize = 12.sp) },
                                leadingIcon = if (statusFilter == key) checkIcon else null
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visibleMessages.size} message(s)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { sortNewestFirst = !sortNewestFirst }) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (sortNewestFirst) "Latest first" else "Soonest first", fontSize = 12.sp)
                        }
                    }
                    if (visibleMessages.isEmpty()) {
                        EmptyState(
                            if (searchText.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Inbox,
                            if (searchText.isNotBlank()) "No matches" else "Nothing here yet",
                            if (searchText.isNotBlank()) "Try a different name, number or keyword." else "Messages in this view will appear here.",
                            actionLabel = if (searchText.isBlank() && statusFilter in listOf("ALL", "SCHEDULED")) "New message" else null,
                            onAction = { openComposer("SMS") }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleMessages, key = { it.id }) { message ->
                                var menuOpen by remember { mutableStateOf(false) }
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, if (message.needsAttention()) MaterialTheme.colorScheme.error.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
                                        MessageCard(message, onClick = if (message.status in listOf("SCHEDULED", "PAUSED")) { { editMessage = message; duplicateMode = false; showComposer = true } } else null)
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                                            when {
                                                message.needsAttention() -> TextButton(onClick = { retry(message); notify("Retrying ${message.recipient} in 30 seconds.") }) {
                                                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Retry")
                                                }
                                                message.status == "SCHEDULED" -> TextButton(onClick = { togglePause(message); notify("Paused. Resume any time.") }) {
                                                    Icon(Icons.Default.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Pause")
                                                }
                                                message.status == "PAUSED" -> TextButton(onClick = { togglePause(message); notify("Resumed.") }) {
                                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Resume")
                                                }
                                            }
                                            if (message.status in listOf("SCHEDULED", "PAUSED")) {
                                                TextButton(onClick = { editMessage = message; duplicateMode = false; showComposer = true }) {
                                                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit")
                                                }
                                            }
                                            Box {
                                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                                        menuOpen = false
                                                        editMessage = message.copy(id = 0L, scheduledAt = System.currentTimeMillis() + 3_600_000L, status = "SCHEDULED", deliveryStatus = "PENDING", repeatRule = "NONE", createdAt = System.currentTimeMillis())
                                                        duplicateMode = true
                                                        showComposer = true
                                                    })
                                                    if (message.status == "SCHEDULED") DropdownMenuItem(text = { Text("Mark as done") }, leadingIcon = { Icon(Icons.Default.TaskAlt, null) }, onClick = {
                                                        menuOpen = false; complete(message); notify("Marked as done.")
                                                    })
                                                    DropdownMenuItem(text = { Text("Copy message text") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) }, onClick = {
                                                        menuOpen = false
                                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SAM message", message.body))
                                                        notify("Message text copied.")
                                                    })
                                                    HorizontalDivider()
                                                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = {
                                                        menuOpen = false; pendingRemoval = message
                                                    })
                                                }
                                            }
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
                    phoneContacts = phoneContacts,
                    contactsPermissionGranted = contactsPermissionGranted,
                    onRequestDeviceContacts = onRequestDeviceContacts,
                    onRefreshDeviceContacts = onRefreshDeviceContacts,
                    onBulkSchedule = { selected, body, channel, scheduledAt ->
                        val scheduleAll = {
                            selected.forEach { contact ->
                                val personalised = body.replace("{name}", contact.name, ignoreCase = true)
                                    .replace("{phone}", contact.phone, ignoreCase = true)
                                save(null, contact.phone, personalised, channel, scheduledAt, "NONE", "Bulk", emptyList(), "TIME", null, null, 150f, "", "", null)
                            }
                            notify("${selected.size} $channel message(s) scheduled for ${friendlyDateTime(scheduledAt)}.")
                        }
                        if (channel == "SMS") requestSmsPermission(scheduleAll) else scheduleAll()
                    },
                    onSendNow = { selected, body, channel ->
                        if (channel == "SMS") {
                            requestSmsPermission {
                                var sent = 0
                                var failed = 0
                                @Suppress("DEPRECATION")
                                val smsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
                                selected.forEach { contact ->
                                    val personalised = body.replace("{name}", contact.name, ignoreCase = true)
                                        .replace("{phone}", contact.phone, ignoreCase = true)
                                    runCatching {
                                        val parts = smsManager.divideMessage(personalised)
                                        if (parts.size > 1) smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                                        else smsManager.sendTextMessage(contact.phone, null, personalised, null, null)
                                    }.onSuccess { sent++ }.onFailure { failed++; android.util.Log.e("SAMBulkSMS", "SMS submission failed for ${contact.phone}", it) }
                                }
                                Toast.makeText(context, "SMS submitted: $sent • Failed: $failed. Delivery depends on your mobile network.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val contact = selected.firstOrNull()
                            if (contact == null) {
                                Toast.makeText(context, "Select at least one contact first.", Toast.LENGTH_SHORT).show()
                            } else {
                                val personalised = body.replace("{name}", contact.name, ignoreCase = true)
                                    .replace("{phone}", contact.phone, ignoreCase = true)
                                val digits = contact.phone.filter { it.isDigit() }
                                runCatching {
                                    val uri = android.net.Uri.parse("https://wa.me/$digits?text=${android.net.Uri.encode(personalised)}")
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(android.content.Intent.createChooser(intent, "Continue with WhatsApp"))
                                }.onFailure { Toast.makeText(context, "Could not open WhatsApp for ${contact.name}.", Toast.LENGTH_LONG).show() }
                            }
                        }
                    },
                    modifier = Modifier.padding(padding)
                )
            }

            "Calendar" -> {
                var showHistory by remember { mutableStateOf(false) }
                val agendaSource = if (showHistory)
                    messages.filter { it.status !in listOf("SCHEDULED", "PAUSED") }.sortedByDescending { it.scheduledAt }
                else messages.filter { it.status in listOf("SCHEDULED", "PAUSED") && it.triggerType == "TIME" }.sortedBy { it.scheduledAt }
                val arrivalReminders = if (showHistory) emptyList() else messages.filter { it.status in listOf("SCHEDULED", "PAUSED") && it.triggerType == "LOCATION" }
                val grouped = agendaSource.groupBy { dayHeading(it.scheduledAt) }
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
                ) {
                    item(key = "agenda_toggle") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showHistory, onClick = { showHistory = false }, label = { Text("Upcoming") }, leadingIcon = { Icon(Icons.Default.Upcoming, null, Modifier.size(16.dp)) })
                            FilterChip(selected = showHistory, onClick = { showHistory = true }, label = { Text("History") }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
                        }
                    }
                    if (arrivalReminders.isNotEmpty()) {
                        item(key = "arrival_header") {
                            Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("When I arrive", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                                StatusPill("${arrivalReminders.size}", MaterialTheme.colorScheme.secondary)
                            }
                        }
                        items(arrivalReminders, key = { "loc_${it.id}" }) { message ->
                            SamCard(Modifier.padding(bottom = 8.dp)) { MessageCard(message, onClick = { editMessage = message; duplicateMode = false; showComposer = true }) }
                        }
                    }
                    grouped.forEach { (day, dayMessages) ->
                        item(key = "day_$day") {
                            Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(day, fontWeight = FontWeight.Bold, color = if (day == "Today") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                                StatusPill("${dayMessages.size}", MaterialTheme.colorScheme.primary)
                            }
                        }
                        item(key = "group_$day") {
                            SamCard(Modifier.padding(bottom = 4.dp)) {
                                dayMessages.forEachIndexed { index, message ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                                    MessageCard(message, showTimeOnly = true, onClick = if (message.status in listOf("SCHEDULED", "PAUSED")) { { editMessage = message; duplicateMode = false; showComposer = true } } else null)
                                }
                            }
                        }
                    }
                    if (agendaSource.isEmpty() && arrivalReminders.isEmpty()) {
                        item(key = "agenda_empty") {
                            EmptyState(
                                Icons.Default.EventAvailable,
                                if (showHistory) "No history yet" else "Your agenda is clear",
                                if (showHistory) "Sent, completed and failed messages will be listed here." else "Scheduled messages appear here, grouped by day.",
                                actionLabel = if (showHistory) null else "New message",
                                onAction = { openComposer("SMS") }
                            )
                        }
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
                            Button(onClick = { openComposer("Social") }, modifier = Modifier.fillMaxWidth()) {
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
                    var calendarMonth by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    var selectedCalendarDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
                    val monthCalendar = java.util.Calendar.getInstance().apply {
                        timeInMillis = calendarMonth
                        set(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                    val monthTitle = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(monthCalendar.time)
                    val monthDays = monthCalendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    val leadingBlanks = monthCalendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
                    val calendarCells = List(leadingBlanks) { 0 } + (1..monthDays).toList()
                    val calendarRows = calendarCells.chunked(7).map { it + List(7 - it.size) { 0 } }
                    val selectedDayPosts = socialPosts.filter {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.scheduledAt)) == selectedCalendarDate
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Publishing calendar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = calendarMonth; add(java.util.Calendar.MONTH, -1) }
                                    calendarMonth = cal.timeInMillis
                                }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month") }
                                Text(monthTitle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = {
                                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = calendarMonth; add(java.util.Calendar.MONTH, 1) }
                                    calendarMonth = cal.timeInMillis
                                }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next month") }
                            }
                            Row(Modifier.fillMaxWidth()) {
                                listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                                    Text(day, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            calendarRows.forEach { week ->
                                Row(Modifier.fillMaxWidth()) {
                                    week.forEach { day ->
                                        val dayKey = if (day == 0) "" else String.format(Locale.US, "%04d-%02d-%02d",
                                            monthCalendar.get(java.util.Calendar.YEAR), monthCalendar.get(java.util.Calendar.MONTH) + 1, day)
                                        val count = if (day == 0) 0 else socialPosts.count {
                                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.scheduledAt)) == dayKey
                                        }
                                        TextButton(
                                            onClick = { if (day > 0) selectedCalendarDate = dayKey },
                                            enabled = day > 0,
                                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(if (day == 0) " " else day.toString(), fontWeight = if (selectedCalendarDate == dayKey) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                                                Text(if (count > 0) count.toString() else " ", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text(selectedCalendarDate, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            if (selectedDayPosts.isEmpty()) {
                                Text("No social posts scheduled for this date.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                selectedDayPosts.forEach { post ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text((listOf("Facebook" to "com.facebook.katana", "Instagram" to "com.instagram.android", "TikTok" to "com.zhiliaoapp.musically", "Snapchat" to "com.snapchat.android", "Telegram" to "org.telegram.messenger", "X" to "com.twitter.android", "LinkedIn" to "com.linkedin.android", "Pinterest" to "com.pinterest").firstOrNull { it.second == post.whatsappPackage }?.first ?: "Social post"),
                                                fontWeight = FontWeight.Medium, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(post.scheduledAt)) + " • " + post.body,
                                                maxLines = 2, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { editMessage = post; showComposer = true }) { Text("Edit") }
                                    }
                                }
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
                    val campaignPlatforms = listOf("Facebook", "Instagram", "TikTok", "Snapchat", "Telegram", "X", "LinkedIn", "Pinterest", "WhatsApp")
                    var socialCampaigns by remember {
                        mutableStateOf(runCatching {
                            val raw = appContext.getSharedPreferences("sam_social_campaigns", android.content.Context.MODE_PRIVATE)
                                .getString("campaigns", "[]") ?: "[]"
                            val array = JSONArray(raw)
                            (0 until array.length()).map { index ->
                                val item = array.getJSONObject(index)
                                val platforms = item.optJSONArray("platforms") ?: JSONArray()
                                SocialCampaign(
                                    item.optString("id"), item.optString("name"), item.optString("objective"),
                                    item.optLong("startAt"), item.optLong("endAt"),
                                    (0 until platforms.length()).map { platforms.optString(it) }
                                )
                            }
                        }.getOrDefault(emptyList()) )
                    }
                    var campaignDialog by remember { mutableStateOf(false) }
                    var campaignName by remember { mutableStateOf("") }
                    var campaignObjective by remember { mutableStateOf("") }
                    var campaignStart by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
                    var campaignEnd by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 7L * 86400000L))) }
                    var selectedCampaignPlatforms by remember { mutableStateOf(setOf("Facebook", "Instagram")) }
                    var campaignError by remember { mutableStateOf("") }
                    fun persistCampaigns(items: List<SocialCampaign>) {
                        val array = JSONArray()
                        items.forEach { item ->
                            val platformArray = JSONArray()
                            item.platforms.forEach { platformArray.put(it) }
                            array.put(JSONObject().put("id", item.id).put("name", item.name)
                                .put("objective", item.objective).put("startAt", item.startAt)
                                .put("endAt", item.endAt).put("platforms", platformArray))
                        }
                        appContext.getSharedPreferences("sam_social_campaigns", android.content.Context.MODE_PRIVATE)
                            .edit().putString("campaigns", array.toString()).apply()
                        socialCampaigns = items
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Campaign planner", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Organise campaign goals, dates and intended platforms.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    campaignName = ""; campaignObjective = ""
                                    campaignStart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    campaignEnd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 7L * 86400000L))
                                    selectedCampaignPlatforms = setOf("Facebook", "Instagram")
                                    campaignError = ""; campaignDialog = true
                                }) { Icon(Icons.Default.Add, contentDescription = "Create campaign") }
                            }
                            if (socialCampaigns.isEmpty()) {
                                Text("No campaigns planned. Create one to organise your next promotion, school announcement or business campaign.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                socialCampaigns.sortedBy { it.startAt }.forEach { campaign ->
                                    HorizontalDivider()
                                    val now = System.currentTimeMillis()
                                    val stateLabel = when {
                                        now < campaign.startAt -> "UPCOMING"
                                        now > campaign.endAt -> "COMPLETED"
                                        else -> "ACTIVE"
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(campaign.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                            Text(campaign.objective, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(campaign.startAt)) +
                                                " – " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(campaign.endAt)),
                                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(campaign.platforms.joinToString(" • "), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            Text(stateLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                             val campaignPosts = messages.filter { it.channel == "Social" && it.campaignId == campaign.id }
                                             val scheduledCampaignPosts = campaignPosts.count { it.status == "SCHEDULED" }
                                             val awaitingCampaignPosts = campaignPosts.count { it.status == "AWAITING_USER" }
                                             val failedCampaignPosts = campaignPosts.count { it.status == "FAILED" || it.status == "PERMISSION_REQUIRED" || it.deliveryStatus in listOf("SEND_FAILED", "DELIVERY_FAILED") }
                                             val completedCampaignPosts = campaignPosts.count { it.status == "COMPLETED" || it.status == "SENT" }
                                             Text("${campaignPosts.size} linked • $scheduledCampaignPosts scheduled • $awaitingCampaignPosts awaiting review • $completedCampaignPosts completed • $failedCampaignPosts failed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { persistCampaigns(socialCampaigns.filterNot { it.id == campaign.id }) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete campaign", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (campaignDialog) {
                        AlertDialog(
                            onDismissRequest = { campaignDialog = false },
                            title = { Text("Create social campaign") },
                            text = {
                                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(campaignName, { campaignName = it }, label = { Text("Campaign name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(campaignObjective, { campaignObjective = it }, label = { Text("Campaign objective") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(campaignStart, { campaignStart = it }, label = { Text("Start date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(campaignEnd, { campaignEnd = it }, label = { Text("End date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("Target platforms", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    campaignPlatforms.forEach { platform ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = platform in selectedCampaignPlatforms, onCheckedChange = { checked ->
                                                selectedCampaignPlatforms = if (checked) selectedCampaignPlatforms + platform else selectedCampaignPlatforms - platform
                                            })
                                            Text(platform, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                    if (campaignError.isNotBlank()) Text(campaignError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                                    val start = runCatching { formatter.parse(campaignStart.trim())?.time }.getOrNull()
                                    val end = runCatching { formatter.parse(campaignEnd.trim())?.time?.plus(86399999L) }.getOrNull()
                                    when {
                                        campaignName.isBlank() || campaignObjective.isBlank() -> campaignError = "Enter a campaign name and objective."
                                        socialCampaigns.any { it.name.equals(campaignName.trim(), true) } -> campaignError = "A campaign with this name already exists."
                                        start == null || end == null -> campaignError = "Enter valid dates in YYYY-MM-DD format."
                                        end < start -> campaignError = "End date must be on or after the start date."
                                        selectedCampaignPlatforms.isEmpty() -> campaignError = "Select at least one target platform."
                                        else -> {
                                            persistCampaigns(socialCampaigns + SocialCampaign(
                                                java.util.UUID.randomUUID().toString(), campaignName.trim(), campaignObjective.trim(),
                                                start, end, campaignPlatforms.filter { it in selectedCampaignPlatforms }
                                            ))
                                            campaignDialog = false
                                        }
                                    }
                                }) { Text("Save campaign") }
                            },
                            dismissButton = { TextButton(onClick = { campaignDialog = false }) { Text("Cancel") } }
                        )
                    }
                    val captionPlatforms = listOf("All platforms", "Facebook", "Instagram", "TikTok", "Snapchat", "Telegram", "X", "LinkedIn", "Pinterest", "WhatsApp", "WhatsApp Business")
                    var captionTemplates by remember {
                        mutableStateOf(runCatching {
                            val saved = appContext.getSharedPreferences("sam_social_templates", android.content.Context.MODE_PRIVATE)
                                .getString("templates", "[]") ?: "[]"
                            val array = JSONArray(saved)
                            (0 until array.length()).map { index ->
                                val item = array.getJSONObject(index)
                                SocialCaptionTemplate(item.optString("name"), item.optString("platform", "All platforms"), item.optString("caption"))
                            }
                        }.getOrDefault(emptyList()))
                    }
                    var templateDialog by remember { mutableStateOf(false) }
                    var templateName by remember { mutableStateOf("") }
                    var templateCaption by remember { mutableStateOf("") }
                    var templatePlatform by remember { mutableStateOf("All platforms") }
                    var templatePlatformMenu by remember { mutableStateOf(false) }
                    var templateError by remember { mutableStateOf("") }
                    fun persistCaptionTemplates(items: List<SocialCaptionTemplate>) {
                        val array = JSONArray()
                        items.forEach { item ->
                            array.put(JSONObject().put("name", item.name).put("platform", item.platform).put("caption", item.caption))
                        }
                        appContext.getSharedPreferences("sam_social_templates", android.content.Context.MODE_PRIVATE)
                            .edit().putString("templates", array.toString()).apply()
                        captionTemplates = items
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Caption library", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Save reusable captions and campaign copy on this device.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { templateName = ""; templateCaption = ""; templatePlatform = "All platforms"; templateError = ""; templateDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add caption template")
                                }
                            }
                            if (captionTemplates.isEmpty()) {
                                Text("No saved captions yet. Add a reusable caption for announcements, promotions or campaigns.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                captionTemplates.forEachIndexed { index, item ->
                                    HorizontalDivider()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(item.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                            Text(item.platform, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            Text(item.caption, maxLines = 2, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = {
                                            val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(item.name, item.caption))
                                            Toast.makeText(appContext, "${item.platform} caption copied.", Toast.LENGTH_SHORT).show()
                                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy caption") }
                                        IconButton(onClick = { persistCaptionTemplates(captionTemplates.filterIndexed { i, _ -> i != index }) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete caption", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (templateDialog) {
                        AlertDialog(
                            onDismissRequest = { templateDialog = false },
                            title = { Text("Save platform caption") },
                            text = {
                                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(templateName, { templateName = it }, label = { Text("Template name") }, singleLine = true)
                                    Box {
                                        OutlinedButton(onClick = { templatePlatformMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Platform: $templatePlatform", modifier = Modifier.weight(1f))
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose platform")
                                        }
                                        DropdownMenu(expanded = templatePlatformMenu, onDismissRequest = { templatePlatformMenu = false }) {
                                            captionPlatforms.forEach { platform ->
                                                DropdownMenuItem(text = { Text(platform) }, onClick = { templatePlatform = platform; templatePlatformMenu = false })
                                            }
                                        }
                                    }
                                    OutlinedTextField(templateCaption, { templateCaption = it }, label = { Text("Caption text") }, minLines = 4)
                                    if (templateError.isNotBlank()) Text(templateError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val name = templateName.trim()
                                    val caption = templateCaption.trim()
                                    when {
                                        name.isBlank() || caption.isBlank() -> templateError = "Enter both a name and caption."
                                        captionTemplates.any { it.name.equals(name, true) && it.platform.equals(templatePlatform, true) } -> templateError = "This template already has a caption for $templatePlatform."
                                        else -> {
                                            persistCaptionTemplates(captionTemplates + SocialCaptionTemplate(name, templatePlatform, caption))
                                            templateDialog = false
                                        }
                                    }
                                }) { Text("Save caption") }
                            },
                            dismissButton = { TextButton(onClick = { templateDialog = false }) { Text("Cancel") } }
                        )
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
                    Spacer(Modifier.height(88.dp))
                }
            }

            "Settings" -> {
                Column(Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Tune how SAM looks, sends and protects your data.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    SettingsSection("Appearance", "Choose a light, dark or automatic theme", Icons.Default.Palette, SamColors.Social) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("System" to Icons.Default.BrightnessAuto, "Light" to Icons.Default.LightMode, "Dark" to Icons.Default.DarkMode).forEach { (mode, icon) ->
                                FilterChip(selected = themeMode == mode, onClick = { onThemeChange(mode) }, label = { Text(mode) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    // Reading this state makes the permission list recompose after returning from Android Settings.
                    val permissionRefresh = permissionRefreshTick.intValue
                    SettingsSection("Permissions", "Access SAM needs to send and remind on time", Icons.Default.Security) {
                    Text("SAM requests sensitive access only when a feature needs it. Photos and videos can be selected using Android's system picker without broad gallery access.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val permissionRows = listOf(
                        "Notifications" to android.Manifest.permission.POST_NOTIFICATIONS,
                        "Send SMS" to android.Manifest.permission.SEND_SMS,
                        "Receive SMS" to android.Manifest.permission.RECEIVE_SMS,
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
                    }
                    SettingsSection("Quiet hours", "Hold messages during your rest window", Icons.Default.Bedtime, SamColors.Indigo) {
                    val quietPrefs = remember { context.getSharedPreferences("sam_quiet_hours", android.content.Context.MODE_PRIVATE) }
                    var quietEnabled by remember { mutableStateOf(quietPrefs.getBoolean("enabled", false)) }
                    var quietStart by remember { mutableStateOf(quietPrefs.getString("start", "22:00") ?: "22:00") }
                    var quietEnd by remember { mutableStateOf(quietPrefs.getString("end", "07:00") ?: "07:00") }
                    var quietMessage by remember { mutableStateOf("") }
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
                    }
                    SettingsSection("Incoming SMS automation", "Automatic replies and forwarding", Icons.Default.AutoMode, SamColors.Teal) {
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
                    }
                    SettingsSection("Security & backup", "App lock, encrypted backup and restore", Icons.Default.Shield, SamColors.Amber) {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Encrypted backup", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                    Text("Full encrypted backup includes messages, contacts, birthdays, anniversaries, social campaigns and saved captions. Choose Google Drive or another cloud-storage provider in the Android file picker. Restore on another device using the same password. Existing message-only backups remain supported.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        OutlinedButton(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Set a backup password of at least 8 characters." else onBackup(backupPasswordInput) }, modifier = Modifier.weight(1f)) { Text("Cloud backup") }
                        Button(onClick = { if (backupPasswordInput.length < 8) securityMessage = "Enter the password used for this backup." else onRestore(backupPasswordInput) }, modifier = Modifier.weight(1f)) { Text("Restore cloud backup") }
                    }
                    if (securityMessage.isNotBlank()) Text(securityMessage, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)

                    }
                    SettingsSection("Import & export", "Move messages in and out using CSV files", Icons.Default.ImportExport, SamColors.Teal) {
                    Text("CSV columns: Recipient, Message, Channel, Scheduled At (yyyy-MM-dd HH:mm:ss), optional Repeat Rule, Category, WhatsApp App.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import messages from CSV")
                    }
                    OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export message history (CSV)")
                    }
                    }
                    SettingsSection("How SAM works", "Good to know", Icons.Default.Lightbulb, SamColors.Amber) {
                    listOf(
                        "Notifications: allow reminders in Android settings.",
                        "SMS: uses the Android default SMS SIM; change the preferred SIM in Android SIM settings.",
                        "SMS delivery tracking: sent and delivered updates appear when your mobile network provides them.",
                        "WhatsApp: opens a prepared chat; you review and tap Send.",
                        "Privacy: scheduled messages are stored on this device."
                    ).forEach { setting ->
                        Row(verticalAlignment = Alignment.Top) { Icon(Icons.Default.CheckCircle, null, tint = SamColors.Teal, modifier = Modifier.size(16.dp).padding(top = 2.dp)); Spacer(Modifier.width(8.dp)); Text(setting, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
                    }
                    }
                    AboutSection()
                    Spacer(Modifier.height(24.dp))
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
            initialChannel = composerChannel,
            onClose = { showComposer = false; editMessage = null; duplicateMode = false },
            requestSmsPermission = requestSmsPermission,
            onSave = { id, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage, campaignId ->
                save(if (duplicateMode) null else id, recipient, body, channel, scheduledAt, repeatRule, category, attachments, triggerType, latitude, longitude, radiusMeters, locationLabel, whatsappPackage, campaignId)
                notify(
                    if (triggerType == "LOCATION") "Arrival reminder saved for ${locationLabel.ifBlank { "your destination" }}."
                    else if (id != null && !duplicateMode) "Changes saved • ${friendlyDateTime(scheduledAt)}"
                    else "$channel scheduled for ${friendlyDateTime(scheduledAt)} (${countdown(scheduledAt)})."
                )
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
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onSchedule: (List<ContactEntity>, String, String, Long) -> Unit,
    onSendNow: (List<ContactEntity>, String, String) -> Unit
) {
    var selectedIds by remember { mutableStateOf(contacts.map { it.id }.toSet()) }
    var body by remember { mutableStateOf("Hello {name}, ") }
    var channel by remember { mutableStateOf("SMS") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 3_600_000L))) }
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + 3_600_000L))) }
    var error by remember { mutableStateOf("") }
    var whatsappQueue by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var whatsappIndex by remember { mutableIntStateOf(0) }
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
                if (channel == "WhatsApp") {
                    Text("WhatsApp sends one chat at a time. Tap Open next chat, review the recipient and message, then tap Send in WhatsApp. Return here for the next contact.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (whatsappQueue.isNotEmpty()) {
                        Text("WhatsApp queue: ${whatsappIndex.coerceAtMost(whatsappQueue.size)} of ${whatsappQueue.size} opened", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (whatsappIndex < whatsappQueue.size) {
                            Button(onClick = {
                                onSendNow(listOf(whatsappQueue[whatsappIndex]), body.trim(), "WhatsApp")
                                whatsappIndex++
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open next chat — ${whatsappQueue[whatsappIndex].name}") }
                        } else Text("All selected chats have been opened. Sending is confirmed by you inside WhatsApp.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    Text("SMS is submitted separately to each selected number. Carrier charges, network availability and message limits may apply.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Each recipient receives an individually personalised message. Use {name} and {phone} in the template.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (channel == "WhatsApp") {
                    OutlinedButton(onClick = {
                        val selected = contacts.filter { it.id in selectedIds }
                        when {
                            selected.isEmpty() -> error = "Select at least one contact."
                            body.isBlank() -> error = "Enter a message."
                            else -> { whatsappQueue = selected; whatsappIndex = 0; error = "" }
                        }
                    }) { Text(if (whatsappQueue.isEmpty()) "Prepare send now" else "Reset queue") }
                } else {
                    OutlinedButton(onClick = {
                        val selected = contacts.filter { it.id in selectedIds }
                        when {
                            selected.isEmpty() -> error = "Select at least one contact."
                            body.isBlank() -> error = "Enter a message."
                            else -> onSendNow(selected, body.trim(), "SMS")
                        }
                    }) { Text("Send now") }
                }
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
            }
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
    phoneContacts: List<ContactEntity>,
    contactsPermissionGranted: Boolean,
    onRequestDeviceContacts: () -> Unit,
    onRefreshDeviceContacts: () -> Unit,
    onBulkSchedule: (List<ContactEntity>, String, String, Long) -> Unit,
    onSendNow: (List<ContactEntity>, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf("All") }
    var source by remember { mutableStateOf("Phone") }
    var editing by remember { mutableStateOf<ContactEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ContactEntity?>(null) }
    var showBulkComposer by remember { mutableStateOf(false) }
    val activeContacts = if (source == "Phone") phoneContacts else contacts
    val groups = listOf("All") + contacts.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
    val visible = activeContacts.filter { (source == "Phone" || selectedGroup == "All" || it.groupName == selectedGroup) && (query.isBlank() || it.name.contains(query, true) || it.phone.contains(query, true)) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Contact centre", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (source == "Phone") "${phoneContacts.size} phone contact(s)" else "${contacts.size} SAM contact(s)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (source == "Phone") IconButton(onClick = if (contactsPermissionGranted) onRefreshDeviceContacts else onRequestDeviceContacts) { Icon(Icons.Default.Refresh, contentDescription = "Refresh phone contacts") }
            else OutlinedButton(onClick = onPickContact) { Icon(Icons.Default.ContactPhone, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Import") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = source == "Phone", onClick = { source = "Phone"; query = "" }, label = { Text("Phone contacts") })
            FilterChip(selected = source == "SAM", onClick = { source = "SAM"; query = ""; selectedGroup = "All" }, label = { Text("SAM contacts") })
        }
        if (source == "Phone" && !contactsPermissionGranted) Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow SAM to read your phone contacts", fontWeight = FontWeight.SemiBold)
                Text("This read-only access lets you search and select phone contacts for scheduled messages. SAM cannot edit or delete your phone address book.", fontSize = 12.sp)
                Button(onClick = onRequestDeviceContacts) { Text("Grant contacts access") }
                TextButton(onClick = onPickContact) { Text("Choose one contact without full access") }
            }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Search contacts") }, singleLine = true, enabled = source == "SAM" || contactsPermissionGranted)
        if (source == "SAM") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            groups.forEach { group -> FilterChip(selected = selectedGroup == group, onClick = { selectedGroup = group }, label = { Text(group) }) }
        }
        if (source == "Phone" && !contactsPermissionGranted) { Spacer(Modifier.height(8.dp)) }
        else if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(if (query.isNotBlank()) "No contacts match your search." else if (source == "Phone") "No phone contacts with saved numbers were found." else "No contacts saved in SAM yet. Import one from your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(visible, key = { it.id }) { contact ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(contact.phone, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(contact.groupName, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                            }
                            if (source == "Phone") TextButton(onClick = { saveContact(contact.copy(id = 0L)); Toast.makeText(context, "Contact copied into SAM.", Toast.LENGTH_SHORT).show() }) { Text("Save to SAM") }
                            else {
                                TextButton(onClick = { editing = contact; showEditor = true }) { Text("Edit") }
                                TextButton(onClick = { deleting = contact }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { showBulkComposer = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = activeContacts.isNotEmpty()) {
            Icon(Icons.Default.Campaign, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Create bulk message")
        }
        if (source == "SAM") Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.PersonAdd, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Add contact")
        }
    }
    if (showBulkComposer) {
        BulkComposer(activeContacts, onClose = { showBulkComposer = false }, onSchedule = { selected, body, channel, at ->
            onBulkSchedule(selected, body, channel, at)
            showBulkComposer = false
        }, onSendNow = onSendNow)
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
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Maps the last 9 digits of a phone number to a saved contact name, so lists show people, not numbers. */
private val LocalContactNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

private fun phoneKey(phone: String): String = phone.filter(Char::isDigit).takeLast(9)

@Composable
private fun MessageCard(message: MessageEntity, onClick: (() -> Unit)? = null, showTimeOnly: Boolean = false) {
    val names = LocalContactNames.current
    val displayName = names[phoneKey(message.recipient)]
    val visual = statusVisual(message)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        ChannelAvatar(message.channel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName ?: message.recipient,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(visual.label, visual.color, visual.icon)
            }
            if (displayName != null) Text(message.recipient, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                message.body, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val whenText = when {
                    message.triggerType == "LOCATION" -> message.locationLabel.ifBlank { "On arrival" }
                    showTimeOnly -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.scheduledAt))
                    else -> friendlyDateTime(message.scheduledAt)
                }
                Icon(
                    if (message.triggerType == "LOCATION") Icons.Default.Place else Icons.Default.AccessTime,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(whenText, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (message.status == "SCHEDULED" && message.triggerType == "TIME" && message.scheduledAt > System.currentTimeMillis()) {
                    Text("  •  " + countdown(message.scheduledAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            val extras = buildList {
                add(message.category)
                repeatLabel(message.repeatRule).takeIf { it.isNotBlank() }?.let { add("Repeats $it") }
                val attachmentCount = runCatching { JSONArray(message.attachmentsJson).length() }.getOrDefault(0)
                if (attachmentCount > 0) add("$attachmentCount attachment(s)")
                if (message.channel == "SMS" && message.deliveryStatus !in listOf("PENDING", "DELIVERED", "SEND_FAILED", "DELIVERY_FAILED"))
                    add(message.deliveryStatus.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
            }
            Text(extras.joinToString("  •  "), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    initialChannel: String = "SMS",
    onClose: () -> Unit,
    requestSmsPermission: (() -> Unit) -> Unit,
    onSave: (Long?, String, String, String, Long, String, String, List<String>, String, Double?, Double?, Float, String, String, String?) -> Unit
) {
    var recipient by remember(initialMessage?.id) { mutableStateOf(initialMessage?.recipient ?: "") }
    LaunchedEffect(pickedRecipientNumber) { if (pickedRecipientNumber.isNotBlank()) recipient = pickedRecipientNumber }
    var body by remember(initialMessage?.id) { mutableStateOf(initialMessage?.body ?: "") }
    var channel by remember(initialMessage?.id) { mutableStateOf(initialMessage?.channel ?: initialChannel) }
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
    val socialCampaignOptions = remember(context) {
        runCatching {
            val raw = context.getSharedPreferences("sam_social_campaigns", android.content.Context.MODE_PRIVATE).getString("campaigns", "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                item.optString("id") to item.optString("name")
            }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        }.getOrDefault(emptyList())
    }
    var selectedCampaignId by remember(initialMessage?.id) { mutableStateOf(initialMessage?.campaignId ?: "") }
    var campaignMenuExpanded by remember { mutableStateOf(false) }
    val whatsappApps = remember(context.packageManager) {
        listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business").mapNotNull { (packageName, fallbackLabel) ->
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                WhatsAppAppOption(packageName, context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { fallbackLabel })
            }.getOrNull()
        }
    }
    val selectedWhatsAppMissing = whatsappPackage.isNotBlank() && whatsappApps.none { it.packageName == whatsappPackage }

    val submit: () -> Unit = {
        val action = {
            try {
                val scheduledAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .apply { isLenient = false }
                    .parse("$date $time")!!.time
                when {
                    recipient.isBlank() -> error = "Enter who should receive this message."
                    body.isBlank() -> error = "Write the message you want to send."
                    recipient.count { it.isDigit() } < 7 -> error = "Enter a valid phone number with country code where required."
                    triggerType == "TIME" && scheduledAt <= System.currentTimeMillis() -> error = "Choose a future date and time."
                    triggerType == "LOCATION" && ((latitudeText.toDoubleOrNull()?.let { it !in -90.0..90.0 } ?: true) || (longitudeText.toDoubleOrNull()?.let { it !in -180.0..180.0 } ?: true)) -> error = "Enter valid latitude and longitude coordinates."
                    triggerType == "LOCATION" && (radiusText.toFloatOrNull()?.let { it !in 100f..1000f } ?: true) -> error = "Arrival radius must be between 100 and 1000 metres."
                    else -> {
                        error = ""
                        onSave(initialMessage?.id, recipient.trim(), body.trim(), channel, scheduledAt, repeatRule, category, attachments, triggerType, latitudeText.toDoubleOrNull(), longitudeText.toDoubleOrNull(), radiusText.toFloatOrNull() ?: 150f, locationLabel.trim(), whatsappPackage, selectedCampaignId.takeIf { channel == "Social" && it.isNotBlank() })
                    }
                }
            } catch (_: Exception) {
                error = "Enter a valid date and time."
            }
        }
        if (channel == "SMS") requestSmsPermission(action) else action()
    }
    val parsedAt = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }.parse("$date $time")!!.time }.getOrNull()
    val names = LocalContactNames.current
    val recipientName = names[phoneKey(recipient)]
    fun setFrom(millis: Long) {
        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
        time = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Header
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text(
                        when { duplicateMode -> "Duplicate message"; initialMessage == null -> "New message"; else -> "Edit message" },
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = submit) { Text("Save", fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Channel
                    ComposerStep(1, "Send with") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("SMS" to "SMS", "WhatsApp" to "WhatsApp", "Social" to "Social").forEach { (key, label) ->
                                val selected = channel == key
                                val tint = channelColor(key)
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                        .background(if (selected) tint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .clickable { channel = key }
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(channelIcon(key), contentDescription = null, tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                            Box {
                                OutlinedButton(onClick = { campaignMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Flag, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(socialCampaignOptions.firstOrNull { it.first == selectedCampaignId }?.second ?: "No campaign", modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose campaign")
                                }
                                DropdownMenu(expanded = campaignMenuExpanded, onDismissRequest = { campaignMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("No campaign") }, onClick = { selectedCampaignId = ""; campaignMenuExpanded = false })
                                    socialCampaignOptions.forEach { (id, name) ->
                                        DropdownMenuItem(text = { Text(name) }, trailingIcon = { if (selectedCampaignId == id) Icon(Icons.Default.Check, contentDescription = "Selected") }, onClick = { selectedCampaignId = id; campaignMenuExpanded = false })
                                    }
                                }
                            }
                            ComposerHint("At the scheduled time SAM opens the selected app with your text and media ready. You review and publish inside that app.")
                        }
                        if (channel == "WhatsApp") {
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
                            if (whatsappApps.isEmpty()) ComposerHint("No WhatsApp app was detected. Install WhatsApp or WhatsApp Business, or keep Ask every time.")
                            if (selectedWhatsAppMissing) Text("Your previously selected WhatsApp app is no longer installed. Choose another app or Ask every time.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            ComposerHint("When the message is due, SAM opens the chat with your text ready. You tap Send in WhatsApp.")
                        }
                        if (channel == "SMS") ComposerHint("SMS is sent automatically from your default SIM at the scheduled time.")
                    }

                    // 2. Recipient
                    ComposerStep(2, "To") {
                        val savedAsText: (@Composable () -> Unit)? = recipientName?.let { name -> @Composable { Text("Saved as $name", color = MaterialTheme.colorScheme.secondary) } }
                        OutlinedTextField(
                            value = recipient,
                            onValueChange = { recipient = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Phone number, e.g. 024 000 0000") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            trailingIcon = { IconButton(onClick = onPickRecipient) { Icon(Icons.Default.Contacts, contentDescription = "Choose from contacts", tint = MaterialTheme.colorScheme.primary) } },
                            supportingText = savedAsText,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    // 3. Message
                    ComposerStep(3, "Message") {
                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Type your message…") },
                            minLines = 4,
                            shape = RoundedCornerShape(14.dp),
                            supportingText = {
                                if (channel == "SMS" && body.isNotEmpty()) {
                                    val info = runCatching { android.telephony.SmsMessage.calculateLength(body, false) }.getOrNull()
                                    if (info != null) Text("${body.length} characters • ${info[0]} SMS part(s) • ${info[2]} left in this part")
                                } else Text("${body.length} characters")
                            }
                        )
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Greeting" to "Hello! I hope you are doing well.",
                                "Reminder" to "This is a friendly reminder about our earlier arrangement.",
                                "Birthday" to "Wishing you a very happy birthday. May your new year be filled with blessings!",
                                "Meeting" to "This is a reminder of our scheduled meeting. Kindly be on time.",
                                "Thank you" to "Thank you very much. I truly appreciate it."
                            ).forEach { (label, template) ->
                                AssistChip(onClick = { body = template }, label = { Text(label, fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp)) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple("Photo", Icons.Default.PhotoCamera, onCapturePhoto),
                                Triple("Video", Icons.Default.Videocam, onCaptureVideo),
                                Triple("Attach", Icons.Default.AttachFile, onPickAttachments)
                            ).forEach { (label, icon, action) ->
                                OutlinedButton(onClick = action, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (attachments.isNotEmpty()) {
                            if (channel == "SMS") Text("Attachments are not sent by SMS. They are kept for WhatsApp and social posts.", fontSize = 11.sp, color = SamColors.Amber)
                            attachments.forEach { uri ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(android.net.Uri.decode(uri.substringAfterLast("/")), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    IconButton(onClick = { attachments = attachments - uri }) { Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }

                    // 4. When
                    ComposerStep(4, "When") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = triggerType == "TIME", onClick = { triggerType = "TIME" }, label = { Text("Date & time") }, leadingIcon = { Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp)) })
                            FilterChip(selected = triggerType == "LOCATION", onClick = { triggerType = "LOCATION" }, label = { Text("When I arrive") }, leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(16.dp)) })
                        }
                        if (triggerType == "TIME") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val presets = listOf(
                                    "In 1 hour" to { System.currentTimeMillis() + 3_600_000L },
                                    "Tonight 7 pm" to {
                                        java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, 19); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                                            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                                        }.timeInMillis
                                    },
                                    "Tomorrow 8 am" to {
                                        java.util.Calendar.getInstance().apply {
                                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                                            set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                                        }.timeInMillis
                                    },
                                    "Next Monday 8 am" to {
                                        java.util.Calendar.getInstance().apply {
                                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                                            while (get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) add(java.util.Calendar.DAY_OF_YEAR, 1)
                                            set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                                        }.timeInMillis
                                    }
                                )
                                presets.forEach { (label, compute) ->
                                    SuggestionChip(onClick = { setFrom(compute()) }, label = { Text(label, fontSize = 12.sp) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PickerField(
                                    label = "Date",
                                    value = parsedAt?.let { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: date,
                                    icon = Icons.Default.CalendarMonth,
                                    modifier = Modifier.weight(1.2f)
                                ) {
                                    val cal = java.util.Calendar.getInstance().apply { setTime(runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!! }.getOrDefault(Date())) }
                                    android.app.DatePickerDialog(context, { _, y, m, d -> date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d) }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                                        datePicker.minDate = System.currentTimeMillis() - 1000L
                                    }.show()
                                }
                                PickerField(label = "Time", value = time, icon = Icons.Default.Schedule, modifier = Modifier.weight(0.8f)) {
                                    val cal = java.util.Calendar.getInstance().apply { setTime(runCatching { SimpleDateFormat("HH:mm", Locale.US).parse(time)!! }.getOrDefault(Date())) }
                                    android.app.TimePickerDialog(context, { _, h, m -> time = String.format(Locale.US, "%02d:%02d", h, m) }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                                }
                            }
                            if (parsedAt != null) {
                                val past = parsedAt <= System.currentTimeMillis()
                                Text(
                                    if (past) "This time has already passed — pick a future time." else "Sends ${countdown(parsedAt)}",
                                    fontSize = 12.sp, color = if (past) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            ComposerHint("Search for a destination to fill in its coordinates. Place lookup may need internet access. You can also enter coordinates manually.")
                            OutlinedTextField(
                                value = locationSearch,
                                onValueChange = { locationSearch = it; locationSearchMessage = "" },
                                label = { Text("Search place or address") },
                                placeholder = { Text("e.g. Kejetia Market, Kumasi") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                trailingIcon = {
                                    if (isSearchingLocation) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else IconButton(onClick = {
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
                                                    locationSearchMessage = "Location found. Confirm the details below."
                                                } else {
                                                    locationSearchMessage = "No result found. Check the spelling or enter coordinates manually."
                                                }
                                            }
                                        }
                                    }) { Icon(Icons.Default.Search, contentDescription = "Find destination") }
                                }
                            )
                            if (locationSearchMessage.isNotBlank()) Text(locationSearchMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(locationLabel, { locationLabel = it }, label = { Text("Selected place / address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(latitudeText, { latitudeText = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp))
                                OutlinedTextField(longitudeText, { longitudeText = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp))
                            }
                            Text("Arrival radius: ${radiusText.toFloatOrNull()?.toInt() ?: 150} m", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Slider(
                                value = (radiusText.toFloatOrNull() ?: 150f).coerceIn(100f, 1000f),
                                onValueChange = { radiusText = ((it / 50f).toInt() * 50).toString() },
                                valueRange = 100f..1000f
                            )
                        }
                        Text("Repeat", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("NONE" to "Never", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly").forEach { (key, label) ->
                                FilterChip(selected = repeatRule == key, onClick = { repeatRule = key }, label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    }

                    // 5. Category
                    ComposerStep(5, "Category") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf("General", "Personal", "Family", "Work", "School", "Business", "Bulk", "Birthday", "Reminder").forEach { value ->
                                FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 12.sp) })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Summary + save bar
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (error.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            }
                        }
                        val summaryWho = recipientName ?: recipient.ifBlank { "…" }
                        val summaryWhen = if (triggerType == "LOCATION") "on arrival at ${locationLabel.ifBlank { "your destination" }}"
                            else parsedAt?.let { friendlyDateTime(it) } ?: "—"
                        val repeatText = repeatLabel(repeatRule).takeIf { it.isNotBlank() }?.let { " • repeats ${it.lowercase()}" } ?: ""
                        Text(
                            "${if (channel == "Social") "Social post" else channel} to $summaryWho • $summaryWhen$repeatText",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = submit,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ScheduleSend, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (duplicateMode || initialMessage == null) "Schedule message" else "Save changes", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerStep(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    SamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ComposerHint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickerField(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AboutSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    SettingsSection("About SAM", "Version $version", Icons.Default.Info, SamColors.Indigo) {
        Text(
            "SAM Message Scheduler plans SMS, WhatsApp reminders and social posts, sends them on time and keeps a full delivery history on your device.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("DEVELOPER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Samuel Kwame Dordzie", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("T.I. Ahmadiyya Girls' SHS, Asokore", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:0246652528"))) } },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) { Icon(Icons.Default.Call, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("0246652528", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:sdordzie@gmail.com")).apply {
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "SAM Message Scheduler $version")
                        })
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) { Icon(Icons.Default.Email, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Email", fontSize = 12.sp, maxLines = 1) }
        }
        Text("sdordzie@gmail.com", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text("© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Samuel Kwame Dordzie. All rights reserved.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
