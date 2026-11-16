package gh.com.bigsammy.passportpro

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.security.MessageDigest

private val ControlGold = Color(0xFFE4B45D)
private val ControlPanel: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF1B2433) else Color.White
private val ControlMuted: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF9AA7B8) else Color(0xFF667085)

@Composable
internal fun StudioControlCentreScreen(modifier: Modifier = Modifier, appearance: String = "System default", onAppearanceChange: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("passportpro_studio_settings", Context.MODE_PRIVATE) }
    var studioName by remember { mutableStateOf(prefs.getString("studio_name", "PassportPro Studio") ?: "PassportPro Studio") }
    var phone by remember { mutableStateOf(prefs.getString("studio_phone", "") ?: "") }
    var address by remember { mutableStateOf(prefs.getString("studio_address", "") ?: "") }
    var service by remember { mutableStateOf(prefs.getString("default_service", "Passport photograph") ?: "Passport photograph") }
    var price by remember { mutableStateOf(prefs.getString("default_price", "20.00") ?: "20.00") }
    var profile by remember { mutableStateOf(prefs.getString("photo_profile", "Ghana Passport • 35 × 45 mm") ?: "Ghana Passport • 35 × 45 mm") }
    var dpi by remember { mutableIntStateOf(prefs.getInt("default_dpi", 300)) }
    var background by remember { mutableStateOf(prefs.getString("default_background", "White") ?: "White") }
    var retention by remember { mutableIntStateOf(prefs.getInt("retention_days", 0)) }
    var diagnostic by remember { mutableStateOf("Run a check to inspect local storage and database health.") }
    var expandedProfile by remember { mutableStateOf(false) }
    var expandedDpi by remember { mutableStateOf(false) }
    var expandedBg by remember { mutableStateOf(false) }
    val profiles = listOf("Ghana Passport • 35 × 45 mm", "ICAO-style portrait • 35 × 45 mm", "US Visa • 2 × 2 in", "Custom • configure in Print Designer")
    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("STUDIO CONTROL CENTRE", color = ControlGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Studio Control Centre", color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Set your studio identity, preferred photo workflow, print access and data-care settings in one place.", color = ControlMuted, fontSize = 13.sp)

        ControlCard("APP APPEARANCE", "Choose the look that feels most comfortable. System default automatically follows your phone's light or dark setting.") {
            ExposedSetting("Appearance", appearance, listOf("System default", "Light", "Dark"), false, {}) { onAppearanceChange(it) }
            Text(when (appearance) {
                "Light" -> "Light appearance is active."
                "Dark" -> "Dark appearance is active."
                else -> "PassportPro follows your Android system appearance."
            }, color = ControlMuted, fontSize = 11.sp)
        }

        ControlCard("BUSINESS IDENTITY", "These details are saved on this device for studio reference and future branded document integration.") {
            ControlField("Studio / business name", studioName, { studioName = it; save("studio_name", it) })
            ControlField("Contact phone", phone, { phone = it; save("studio_phone", it) }, KeyboardType.Phone)
            ControlField("Studio address", address, { address = it; save("studio_address", it) })
            ControlField("Default service", service, { service = it; save("default_service", it) })
            ControlField("Suggested price (GH₵)", price, { price = it; save("default_price", it) }, KeyboardType.Decimal)
        }

        ControlCard("PHOTO COMPLIANCE WORKFLOW", "Presets are workflow starting points. Always confirm the current requirements of the receiving authority before printing or submitting.") {
            ExposedSetting("Preferred photo profile", profile, profiles, expandedProfile, { expandedProfile = it }) { profile = it; save("photo_profile", it) }
            ExposedSetting("Default background", background, listOf("White", "Blue", "Red", "Grey"), expandedBg, { expandedBg = it }) { background = it; save("default_background", it) }
            ExposedSetting("Default print resolution", "$dpi DPI", listOf("150 DPI", "300 DPI", "600 DPI"), expandedDpi, { expandedDpi = it }) {
                dpi = it.substringBefore(" ").toInt(); prefs.edit().putInt("default_dpi", dpi).apply()
            }
            Text("The selected profile is a saved preference; it does not automatically certify facial dimensions, head height or acceptance by an authority.", color = ControlMuted, fontSize = 11.sp)
        }

        ControlCard("PRINTER & PRINT SERVICES", "Connect or configure a printer through Android. Actual printer options depend on the installed manufacturer print service.") {
            Button(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ControlGold, contentColor = Color(0xFF101522))) {
                Icon(Icons.Default.Print, null); Spacer(Modifier.width(8.dp)); Text("OPEN ANDROID PRINT SETTINGS")
            }
            Text("Use Print Designer to create the PDF, then select the printer and verify paper size, scaling, colour and margins in the print preview.", color = ControlMuted, fontSize = 11.sp)
        }

        ControlCard("CUSTOMER PHOTO PRIVACY", "Passport photographs and customer contact details are sensitive personal information. Keep the phone secured and share only with the customer's permission.") {
            Text("Photo retention preference", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            ExposedSetting("Retention policy", if (retention == 0) "Keep until manually deleted" else "$retention days (reminder only)", listOf("Keep until manually deleted", "30 days (reminder only)", "90 days (reminder only)", "180 days (reminder only)"), false, {}) {
                retention = when {
                    it.startsWith("30") -> 30
                    it.startsWith("90") -> 90
                    it.startsWith("180") -> 180
                    else -> 0
                }
                prefs.edit().putInt("retention_days", retention).apply()
            }
            Text("This setting records your chosen policy only; it does not automatically delete customer files. Review and delete records/photos manually when retention is no longer necessary.", color = ControlMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Security, tint = ControlGold, contentDescription = null)
                Text("Enable your device screen lock and avoid leaving customer images in public/shared folders.", color = Color.White, fontSize = 12.sp)
            }
        }

        ControlCard("SYSTEM HEALTH & RELEASE INFO", "Run a lightweight local check. No customer data is uploaded.") {
            Button(onClick = {
                diagnostic = runCatching { runStudioDiagnostics(context) }.fold({ it }, { "Check could not complete: ${it.localizedMessage ?: "unknown error"}" })
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ControlGold, contentColor = Color(0xFF101522))) {
                Icon(Icons.Default.Storage, null); Spacer(Modifier.width(8.dp)); Text("RUN STUDIO HEALTH CHECK")
            }
            Text(diagnostic, color = Color.White, fontSize = 12.sp)
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }.getOrDefault("Unknown") }
            Text("PassportPro Studio v$version • Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}", color = ControlMuted, fontSize = 11.sp)
            Text("For a safe upgrade, create a full backup before installing a newer build.", color = ControlMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ControlCard(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = ControlPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(detail, color = ControlMuted, fontSize = 11.sp)
            content()
        }
    }
}

@Composable
private fun ControlField(label: String, value: String, onValue: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ControlGold, focusedLabelColor = ControlGold, cursorColor = ControlGold), colors = studioOutlinedTextFieldColors())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedSetting(label: String, selected: String, options: List<String>, expanded: Boolean, onExpanded: (Boolean) -> Unit, onSelect: (String) -> Unit) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpanded) {
        OutlinedTextField(value = selected, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ControlGold, focusedLabelColor = ControlGold), colors = studioOutlinedTextFieldColors())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); onExpanded(false) }) }
        }
    }
}

private fun runStudioDiagnostics(context: Context): String {
    val roots = listOf(File(context.filesDir, "PassportPro/Captures"), File(context.getExternalFilesDir(null), "PassportPro/Edited"))
    val photoCount = roots.sumOf { root -> if (root.exists()) root.walkTopDown().count { it.isFile } else 0 }
    val photoBytes = roots.sumOf { root -> if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L }
    val dbNames = listOf("passportpro_customers.db", "passportpro_business.db")
    val dbResults = dbNames.map { name ->
        val file = context.getDatabasePath(name)
        if (!file.exists() || file.length() == 0L) "$name: not created yet" else {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cursor = db.rawQuery("PRAGMA integrity_check", null)
                val result = cursor.use { if (it.moveToFirst()) it.getString(0) else "no result" }
                require(result == "ok") { "$name: $result" }
                "$name: OK"
            } finally { db.close() }
        }
    }
    val available = context.filesDir.usableSpace / (1024L * 1024L)
    return "Local photo files: $photoCount (${photoBytes / (1024L * 1024L)} MB)\nFree internal space: ${available} MB\n" + dbResults.joinToString("\n") + "\nDiagnostics are local and read-only."
}
