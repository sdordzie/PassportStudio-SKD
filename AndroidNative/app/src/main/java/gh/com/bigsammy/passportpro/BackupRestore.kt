package gh.com.bigsammy.passportpro

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val SafeGold = Color(0xFFE4B45D)
private val SafePanel = Color(0xFF1B2433)
private val SafeMuted = Color(0xFF9AA7B8)

@Composable
internal fun BackupRestoreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("Back up your studio records and photographs to a location you control.") }
    var working by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) { runCatching { exportBackup(context, uri) } }
            message = result.fold({ "Backup completed. " + it + " files included." }, { "Backup failed: " + (it.localizedMessage ?: "Unknown error") })
            working = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("PHASE 10 • DATA PROTECTION", color = SafeGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Backup & Restore", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Create a portable ZIP backup containing your customer database, business jobs, captured photos and edited photos. Choose phone storage, an SD card or a cloud drive in the Android file picker.", color = SafeMuted, fontSize = 13.sp)
        BackupPanelCard("FULL STUDIO BACKUP", "Export all PassportPro databases and photographs with SHA-256 integrity checks.", Icons.Default.Backup, "Create backup", working) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            save.launch("PassportPro_Backup_" + stamp + ".zip")
        }
        BackupPanelCard("RESTORE STUDIO BACKUP", "Validate the backup before replacing existing records and photo folders.", Icons.Default.Restore, "Choose backup ZIP", working) {
            open.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        Surface(color = SafePanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("IMPORTANT RESTORE NOTES", color = SafeGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("• The archive is checked against its SHA-256 manifest before restore.\n• Current files are copied to a private safety folder before replacement.\n• Restore replaces the current customer/business databases and photo folders.\n• Close and reopen PassportPro after restoring so all screens reload the restored data.\n• Backup files contain personal photos, phone numbers and payment records; keep them private.", color = Color.White, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SafeGold)
        Text(message, color = if (message.startsWith("Backup completed") || message.startsWith("Restore completed")) Color(0xFF64D6A0) else SafeMuted, fontSize = 12.sp)
    }
    if (restoreUri != null) AlertDialog(
        onDismissRequest = { restoreUri = null },
        title = { Text("Replace current studio data?") },
        text = { Text("This replaces the customer records, business jobs and photo folders with the selected backup. A private safety copy of current files will be made first. Continue only if this is the correct backup.") },
        confirmButton = { TextButton(onClick = {
            val uri = restoreUri!!
            restoreUri = null
            scope.launch {
                working = true
                val result = withContext(Dispatchers.IO) { runCatching { importBackup(context, uri) } }
                message = result.fold({ "Restore completed. " + it + " files restored. Close and reopen PassportPro." }, { "Restore stopped: " + (it.localizedMessage ?: "Invalid backup") })
                working = false
            }
        }) { Text("Restore", color = SafeGold) } },
        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } }
    )
}

@Composable
private fun BackupPanelCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, button: String, busy: Boolean, action: () -> Unit) {
    Surface(color = SafePanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row { Icon(icon, null, tint = SafeGold); Spacer(Modifier.width(9.dp)); Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Text(detail, color = SafeMuted, fontSize = 12.sp)
            Button(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SafeGold, contentColor = Color(0xFF101522))) { Text(button, fontWeight = FontWeight.Bold) }
        }
    }
}

private data class BackupSource(val path: String, val file: File)
private fun backupSources(c: Context): List<BackupSource> {
    val out = mutableListOf<BackupSource>()
    listOf("passportpro_customers.db", "passportpro_business.db").forEach { name ->
        val f = c.getDatabasePath(name)
        if (f.exists() && f.length() > 0) out.add(BackupSource("databases/" + name, f))
    }
    val roots = listOf(
        "photos/captures/" to File(c.filesDir, "PassportPro/Captures"),
        "photos/edited/" to File(c.getExternalFilesDir(null), "PassportPro/Edited")
    )
    roots.forEach { pair ->
        val root = pair.second
        if (root.exists()) root.walkTopDown().filter { it.isFile }.forEach { f ->
            out.add(BackupSource(pair.first + f.relativeTo(root).invariantSeparatorsPath, f))
        }
    }
    return out
}
private fun digest(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val b = ByteArray(8192)
        while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
private fun exportBackup(c: Context, uri: Uri): Int {
    val files = backupSources(c)
    c.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
        ZipOutputStream(stream.buffered()).use { zip ->
            val manifest = StringBuilder("PASSPORTPRO_BACKUP_V1\n")
            files.forEach { item ->
                zip.putNextEntry(ZipEntry(item.path))
                item.file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
                manifest.append(item.path).append("|").append(digest(item.file)).append("\n")
            }
            zip.putNextEntry(ZipEntry("manifest.sha256.txt"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    } ?: error("Cannot write to selected destination")
    return files.size
}
private fun importBackup(c: Context, uri: Uri): Int {
    val stage = File(c.cacheDir, "passportpro_restore_stage").apply { deleteRecursively(); mkdirs() }
    c.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(stage, entry.name).canonicalFile
                require(target.toPath().startsWith(stage.canonicalFile.toPath())) { "Unsafe ZIP path" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    } ?: error("Cannot read selected backup")
    val manifest = File(stage, "manifest.sha256.txt")
    require(manifest.exists()) { "Backup manifest missing" }
    val lines = manifest.readLines()
    require(lines.firstOrNull() == "PASSPORTPRO_BACKUP_V1") { "Not a supported PassportPro backup" }
    val entries = lines.drop(1).filter { it.contains("|") }.associate {
        val parts = it.split("|", limit = 2); parts[0] to parts[1]
    }
    require(entries.isNotEmpty()) { "Backup is empty" }
    entries.forEach { pair ->
        val file = File(stage, pair.key).canonicalFile
        require(file.toPath().startsWith(stage.canonicalFile.toPath()) && file.exists() && digest(file) == pair.value) { "Integrity check failed: " + pair.key }
    }
    require(entries.keys.all { it == "databases/passportpro_customers.db" || it == "databases/passportpro_business.db" || it.startsWith("photos/captures/") || it.startsWith("photos/edited/") }) { "Unexpected backup content" }
    val safety = File(c.filesDir, "PassportPro/PreRestoreSafety").apply { mkdirs() }
    backupSources(c).forEach { it.file.copyTo(File(safety, it.path.replace("/", "_")), overwrite = true) }
    entries.keys.filter { it.startsWith("databases/") }.forEach { path ->
        val target = c.getDatabasePath(path.substringAfter("databases/"))
        target.parentFile?.mkdirs()
        File(stage, path).copyTo(target, overwrite = true)
        File(target.absolutePath + "-wal").delete()
        File(target.absolutePath + "-shm").delete()
    }
    val captures = File(c.filesDir, "PassportPro/Captures")
    val edited = File(c.getExternalFilesDir(null), "PassportPro/Edited")
    captures.deleteRecursively(); edited.deleteRecursively(); captures.mkdirs(); edited.mkdirs()
    entries.keys.filter { it.startsWith("photos/") }.forEach { path ->
        val isCapture = path.startsWith("photos/captures/")
        val prefix = if (isCapture) "photos/captures/" else "photos/edited/"
        val root = if (isCapture) captures else edited
        val target = File(root, path.removePrefix(prefix)).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe photo path" }
        target.parentFile?.mkdirs()
        File(stage, path).copyTo(target, overwrite = true)
    }
    stage.deleteRecursively()
    return entries.size
}
