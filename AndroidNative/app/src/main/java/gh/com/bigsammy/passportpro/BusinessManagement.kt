package gh.com.bigsammy.passportpro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BizGold = Color(0xFFE4B45D)
private val BizPanel = Color(0xFF1B2433)
private val BizMuted = Color(0xFF9AA7B8)
private val workflowStages = listOf("REGISTERED", "EDITING", "READY TO PRINT", "PRINTED", "DELIVERED")

private data class StudioJob(
    val id: Long, val customer: String, val phone: String, val service: String,
    val totalPesewas: Long, val paidPesewas: Long, val created: Long,
    val workflow: String, val dueDate: String
) {
    val balancePesewas get() = (totalPesewas - paidPesewas).coerceAtLeast(0)
    val paymentStatus get() = when { balancePesewas == 0L -> "PAID"; paidPesewas > 0L -> "PARTIAL"; else -> "UNPAID" }
}
private class BusinessDatabase(context: Context) : SQLiteOpenHelper(context, "passportpro_business.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', service TEXT NOT NULL, total_pesewas INTEGER NOT NULL, paid_pesewas INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, workflow_status TEXT NOT NULL DEFAULT 'REGISTERED', due_date TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN workflow_status TEXT NOT NULL DEFAULT 'REGISTERED'")
            db.execSQL("ALTER TABLE jobs ADD COLUMN due_date TEXT NOT NULL DEFAULT ''")
        }
    }
    fun add(customer: String, phone: String, service: String, total: Long, paid: Long, due: String) {
        val v = ContentValues().apply {
            put("customer", customer.trim()); put("phone", phone.trim()); put("service", service.trim())
            put("total_pesewas", total); put("paid_pesewas", paid.coerceIn(0, total))
            put("created", System.currentTimeMillis()); put("workflow_status", "REGISTERED"); put("due_date", due.trim())
        }
        writableDatabase.insert("jobs", null, v)
    }
    fun updateWorkflow(id: Long, stage: String) {
        writableDatabase.update("jobs", ContentValues().apply { put("workflow_status", stage) }, "id=?", arrayOf(id.toString()))
    }
    fun delete(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return writableDatabase.delete("jobs", "id IN ($marks)", ids.map { it.toString() }.toTypedArray())
    }
    fun all(): List<StudioJob> {
        val rows = mutableListOf<StudioJob>()
        readableDatabase.rawQuery("SELECT id,customer,phone,service,total_pesewas,paid_pesewas,created,workflow_status,due_date FROM jobs ORDER BY created DESC", null).use { c ->
            while (c.moveToNext()) rows.add(StudioJob(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getString(7), c.getString(8)))
        }
        return rows
    }
}
private fun money(p: Long) = "GH₵ " + String.format(Locale.US, "%.2f", p / 100.0)

@Composable
internal fun BusinessManagementScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { BusinessDatabase(context) }
    var jobs by remember { mutableStateOf(db.all()) }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var service by remember { mutableStateOf("Passport photograph") }
    var total by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("0") }
    var dueDate by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var searchHistory by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("This month") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val totalPesewas = ((total.toDoubleOrNull() ?: 0.0) * 100).toLong().coerceAtLeast(0)
    val paidPesewas = ((paid.toDoubleOrNull() ?: 0.0) * 100).toLong().coerceAtLeast(0)
    val now = Calendar.getInstance()
    val start = Calendar.getInstance().apply {
        when (period) {
            "Today" -> { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            "This week" -> { set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            "This month" -> { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            else -> timeInMillis = 0L
        }
    }.timeInMillis
    val periodJobs = jobs.filter { it.created >= start && it.created <= now.timeInMillis }
    val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
    val todayJobs = jobs.filter { it.created >= todayStart }
    val collected = periodJobs.sumOf { it.paidPesewas }
    val sales = periodJobs.sumOf { it.totalPesewas }
    val balance = periodJobs.sumOf { it.balancePesewas }
    val history = jobs.filter {
        searchHistory.isNotBlank() && (it.customer.contains(searchHistory, true) || it.phone.contains(searchHistory, true))
    }
    val reportText = buildString {
        append("PASSPORTPRO STUDIO — BUSINESS REPORT\n")
        append("Period: $period\nGenerated: ").append(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())).append("\n\n")
        append("Jobs: ").append(periodJobs.size).append("\nSales value: ").append(money(sales))
        append("\nPayments received: ").append(money(collected)).append("\nOutstanding: ").append(money(balance))
        append("\n\nWORKFLOW\n")
        workflowStages.forEach { stage -> append(stage).append(": ").append(periodJobs.count { it.workflow == stage }).append("\n") }
        append("\nPayment summary is based on recorded job payments.")
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PHASE 11 • PROFESSIONAL STUDIO OPERATIONS", color = BizGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Studio operations", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Track each job from registration to delivery, review customer history and monitor business performance.", color = BizMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("TODAY'S JOBS", todayJobs.size.toString(), Modifier.weight(1f))
            MetricCard("TODAY'S SALES", money(todayJobs.sumOf { it.totalPesewas }), Modifier.weight(1f))
        }
        Text("BUSINESS ANALYTICS", color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Today", "This week", "This month", "All time").forEach { option ->
                FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option, fontSize = 10.sp) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("JOBS", periodJobs.size.toString(), Modifier.weight(1f))
            MetricCard("SALES VALUE", money(sales), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("RECEIVED", money(collected), Modifier.weight(1f))
            MetricCard("OUTSTANDING", money(balance), Modifier.weight(1f))
        }
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "PassportPro $period business report"); putExtra(Intent.EXTRA_TEXT, reportText) }
            context.startActivity(Intent.createChooser(send, "Share business report"))
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BizGold, contentColor = Color(0xFF101522))) {
            Icon(Icons.Default.ReceiptLong, null); Spacer(Modifier.width(7.dp)); Text("SHARE BUSINESS REPORT", fontWeight = FontWeight.Bold)
        }

        Surface(color = BizPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("REGISTER A CUSTOMER JOB", color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(customer, { customer = it }, label = { Text("Customer name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone / WhatsApp (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(service, { service = it }, label = { Text("Service / job description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dueDate, { dueDate = it }, label = { Text("Expected delivery date (optional, e.g. 20 Nov 2026)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(total, { total = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Total (GH₵)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(paid, { paid = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Amount paid") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("Balance: " + money((totalPesewas - paidPesewas).coerceAtLeast(0)), color = BizMuted, fontSize = 12.sp)
                Button(onClick = {
                    if (customer.isBlank() || service.isBlank() || totalPesewas <= 0L) message = "Enter a customer, service and valid total amount."
                    else if (paidPesewas > totalPesewas) message = "Amount paid cannot exceed the job total."
                    else {
                        db.add(customer, phone, service, totalPesewas, paidPesewas, dueDate)
                        jobs = db.all(); customer = ""; phone = ""; service = "Passport photograph"; total = ""; paid = "0"; dueDate = ""; message = "Job saved successfully."
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BizGold, contentColor = Color(0xFF101522))) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(7.dp)); Text("SAVE CUSTOMER JOB", fontWeight = FontWeight.Bold)
                }
                if (message.isNotBlank()) Text(message, color = BizGold, fontSize = 11.sp)
            }
        }

        Text("CUSTOMER JOB HISTORY", color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(searchHistory, { searchHistory = it }, label = { Text("Search by customer name or phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (searchHistory.isNotBlank()) {
            Text("${history.size} matching job(s)", color = BizMuted, fontSize = 11.sp)
            history.forEach { job ->
                Surface(color = BizPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(job.customer, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${job.service} • ${job.workflow}", color = BizMuted, fontSize = 11.sp)
                        Text("Paid ${money(job.paidPesewas)} • Balance ${money(job.balancePesewas)}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        } else Text("Enter a name or phone number to review a customer's previous jobs.", color = BizMuted, fontSize = 11.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ALL JOBS & WORKFLOW", color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f)); Text("${jobs.size} records", color = BizMuted, fontSize = 11.sp)
        }
        if (jobs.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectionMode = !selectionMode; selectedIds = emptySet() }) { Text(if (selectionMode) "Cancel selection" else "Select jobs", color = BizGold) }
                if (selectionMode) {
                    TextButton(onClick = { selectedIds = if (selectedIds.size == jobs.size) emptySet() else jobs.map { it.id }.toSet() }) { Text(if (selectedIds.size == jobs.size) "Deselect all" else "Select all", color = Color.White) }
                    Text("${selectedIds.size} selected", color = BizMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { confirmDelete = true }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB93838))) { Text("Delete") }
                }
            }
        }
        jobs.forEach { job ->
            var menuOpen by remember(job.id) { mutableStateOf(false) }
            Surface(color = if (job.id in selectedIds) Color(0xFF34445C) else BizPanel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (selectionMode) TextButton(onClick = { selectedIds = if (job.id in selectedIds) selectedIds - job.id else selectedIds + job.id }) { Text(if (job.id in selectedIds) "✓ Selected" else "Select job", color = BizGold) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.customer, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text(job.paymentStatus, fontSize = 9.sp) })
                    }
                    Text(job.service, color = BizMuted, fontSize = 12.sp)
                    Text("Workflow: ${job.workflow}", color = BizGold, fontSize = 11.sp)
                    if (job.dueDate.isNotBlank()) Text("Expected delivery: ${job.dueDate}", color = BizMuted, fontSize = 10.sp)
                    Text("Total ${money(job.totalPesewas)} • Paid ${money(job.paidPesewas)} • Balance ${money(job.balancePesewas)}", color = Color.White, fontSize = 11.sp)
                    Text(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(job.created)), color = BizMuted, fontSize = 10.sp)
                    if (!selectionMode) {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("UPDATE JOB STAGE ▾", color = BizGold, fontSize = 10.sp) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                workflowStages.forEach { stage ->
                                    DropdownMenuItem(text = { Text(stage) }, onClick = { db.updateWorkflow(job.id, stage); jobs = db.all(); menuOpen = false })
                                }
                            }
                        }
                        TextButton(onClick = {
                            val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(job.created))
                            val receipt = "PASSPORTPRO STUDIO\nCUSTOMER RECEIPT\n\nReceipt No: PP-${job.id}\nDate: $date\nCustomer: ${job.customer}\nPhone: ${job.phone}\nService: ${job.service}\nWorkflow: ${job.workflow}\nTotal: ${money(job.totalPesewas)}\nAmount paid: ${money(job.paidPesewas)}\nBalance: ${money(job.balancePesewas)}\nPayment status: ${job.paymentStatus}\n\nThank you for your patronage."
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "PassportPro receipt PP-${job.id}"); putExtra(Intent.EXTRA_TEXT, receipt) }
                            context.startActivity(Intent.createChooser(send, "Share customer receipt"))
                        }) { Icon(Icons.Default.ReceiptLong, null, tint = BizGold); Spacer(Modifier.width(5.dp)); Text("SHARE RECEIPT", color = BizGold, fontSize = 11.sp) }
                    }
                }
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete selected jobs?") },
            text = { Text("Permanently delete ${selectedIds.size} selected job records? This action cannot be undone.") },
            confirmButton = { TextButton(onClick = { val count = db.delete(selectedIds); jobs = db.all(); selectedIds = emptySet(); selectionMode = false; confirmDelete = false; message = "$count job record(s) deleted." }) { Text("Delete permanently", color = Color(0xFFB93838)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        Text("Offline storage • Ghana cedi (GH₵) • Business reports and receipts shared through your phone", color = BizMuted, fontSize = 10.sp)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = BizPanel, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = BizMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = BizGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
