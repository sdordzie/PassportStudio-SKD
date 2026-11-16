package gh.com.bigsammy.passportpro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
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

private val BizGold: Color @Composable get() = if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF9A6418)
private val BizPanel: Color @Composable get() = Panel
private val BizMuted: Color @Composable get() = Muted
@Composable
private fun businessChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = if (LocalPassportDark.current) Color(0xFF273246) else Color(0xFFFFFFFF),
    labelColor = if (LocalPassportDark.current) Color(0xFFB9C4D3) else Color(0xFF465366),
    selectedContainerColor = if (LocalPassportDark.current) Color(0xFF493A25) else Color(0xFFE9DDF6),
    selectedLabelColor = if (LocalPassportDark.current) Color(0xFFFFD58A) else Color(0xFF33214B)
)
private val workflowStages = listOf("REGISTERED", "EDITING", "READY TO PRINT", "PRINTED", "DELIVERED")

private data class StudioExpense(val id: Long, val category: String, val description: String, val amountPesewas: Long, val method: String, val created: Long)
private data class CashbookLine(val method: String, val count: Int, val amountPesewas: Long)
private data class StudioPayment(val id: Long, val amountPesewas: Long, val method: String, val reference: String, val created: Long)

private data class StudioJob(
    val id: Long, val customer: String, val phone: String, val service: String,
    val totalPesewas: Long, val paidPesewas: Long, val created: Long,
    val workflow: String, val dueDate: String
) {
    val balancePesewas get() = (totalPesewas - paidPesewas).coerceAtLeast(0)
    val paymentStatus get() = when { balancePesewas == 0L -> "PAID"; paidPesewas > 0L -> "PARTIAL"; else -> "UNPAID" }
}
private class BusinessDatabase(context: Context) : SQLiteOpenHelper(context, "passportpro_business.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', service TEXT NOT NULL, total_pesewas INTEGER NOT NULL, paid_pesewas INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, workflow_status TEXT NOT NULL DEFAULT 'REGISTERED', due_date TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE payments (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, amount_pesewas INTEGER NOT NULL, method TEXT NOT NULL DEFAULT 'Cash', reference TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE job_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, action TEXT NOT NULL, details TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, description TEXT NOT NULL, amount_pesewas INTEGER NOT NULL, method TEXT NOT NULL, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN workflow_status TEXT NOT NULL DEFAULT 'REGISTERED'")
            db.execSQL("ALTER TABLE jobs ADD COLUMN due_date TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) db.execSQL("CREATE TABLE IF NOT EXISTS payments (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, amount_pesewas INTEGER NOT NULL, method TEXT NOT NULL DEFAULT 'Cash', reference TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
        if (oldVersion < 4) db.execSQL("CREATE TABLE IF NOT EXISTS job_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, action TEXT NOT NULL, details TEXT NOT NULL, created INTEGER NOT NULL)")
        if (oldVersion < 5) db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, description TEXT NOT NULL, amount_pesewas INTEGER NOT NULL, method TEXT NOT NULL, created INTEGER NOT NULL)")
    }
    fun add(customer: String, phone: String, service: String, total: Long, paid: Long, due: String) {
        val v = ContentValues().apply {
            put("customer", customer.trim()); put("phone", phone.trim()); put("service", service.trim())
            put("total_pesewas", total); put("paid_pesewas", paid.coerceIn(0, total))
            put("created", System.currentTimeMillis()); put("workflow_status", "REGISTERED"); put("due_date", due.trim())
        }
        val id = writableDatabase.insert("jobs", null, v)
        if (id > 0L && paid > 0L) addPayment(id, paid, "Initial payment", "Recorded when job was created")
    }
    fun addPayment(jobId: Long, amount: Long, method: String, reference: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val c = db.rawQuery("SELECT total_pesewas,paid_pesewas FROM jobs WHERE id=?", arrayOf(jobId.toString()))
            val remaining = c.use { if (it.moveToFirst()) (it.getLong(0) - it.getLong(1)).coerceAtLeast(0) else -1L }
            if (remaining <= 0L || amount <= 0L || amount > remaining) return false
            val values = ContentValues().apply {
                put("job_id", jobId); put("amount_pesewas", amount); put("method", method)
                put("reference", reference.trim()); put("created", System.currentTimeMillis())
            }
            if (db.insert("payments", null, values) < 0L) return false
            db.execSQL("UPDATE jobs SET paid_pesewas=paid_pesewas+? WHERE id=?", arrayOf(amount, jobId))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun payments(jobId: Long): List<StudioPayment> {
        val rows = mutableListOf<StudioPayment>()
        readableDatabase.rawQuery("SELECT id,amount_pesewas,method,reference,created FROM payments WHERE job_id=? ORDER BY created DESC", arrayOf(jobId.toString())).use { c ->
            while (c.moveToNext()) rows.add(StudioPayment(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4)))
        }
        return rows
    }
    fun updateJob(job: StudioJob, customer: String, phone: String, service: String, total: Long, due: String): Boolean {
        if (customer.isBlank() || service.isBlank() || total < job.paidPesewas) return false
        val values = ContentValues().apply {
            put("customer", customer.trim()); put("phone", phone.trim()); put("service", service.trim())
            put("total_pesewas", total); put("due_date", due.trim())
        }
        if (writableDatabase.update("jobs", values, "id=?", arrayOf(job.id.toString())) != 1) return false
        writableDatabase.insert("job_audit", null, ContentValues().apply {
            put("job_id", job.id); put("action", "JOB UPDATED")
            put("details", "Job details edited; revised total " + money(total))
            put("created", System.currentTimeMillis())
        })
        return true
    }
    fun updateWorkflow(id: Long, stage: String) {
        writableDatabase.update("jobs", ContentValues().apply { put("workflow_status", stage) }, "id=?", arrayOf(id.toString()))
    }
    fun delete(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("payments", "job_id IN ($marks)", ids.map { it.toString() }.toTypedArray())
            val count = db.delete("jobs", "id IN ($marks)", ids.map { it.toString() }.toTypedArray())
            db.setTransactionSuccessful()
            return count
        } finally { db.endTransaction() }
    }
    fun addExpense(category: String, description: String, amount: Long, method: String): Boolean {
        if (category.isBlank() || amount <= 0L) return false
        val v = ContentValues().apply { put("category", category); put("description", description); put("amount_pesewas", amount); put("method", method); put("created", System.currentTimeMillis()) }
        return writableDatabase.insert("expenses", null, v) > 0L
    }
    fun expenses(start: Long, end: Long): List<StudioExpense> {
        val rows = mutableListOf<StudioExpense>()
        readableDatabase.rawQuery("SELECT id,category,description,amount_pesewas,method,created FROM expenses WHERE created>=? AND created<? ORDER BY created DESC", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) rows.add(StudioExpense(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getLong(5)))
        }
        return rows
    }
    fun expenseTotals(start: Long, end: Long): List<CashbookLine> {
        val rows = mutableListOf<CashbookLine>()
        readableDatabase.rawQuery("SELECT category,COUNT(*),SUM(amount_pesewas) FROM expenses WHERE created>=? AND created<? GROUP BY category", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) rows.add(CashbookLine(c.getString(0), c.getInt(1), c.getLong(2)))
        }
        return rows
    }
    fun cashbook(start: Long, end: Long): List<CashbookLine> {
        val rows = mutableListOf<CashbookLine>()
        readableDatabase.rawQuery("SELECT method,COUNT(*),SUM(amount_pesewas) FROM payments WHERE created>=? AND created<? GROUP BY method ORDER BY method", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) rows.add(CashbookLine(c.getString(0), c.getInt(1), c.getLong(2)))
        }
        return rows
    }
    fun paymentsInRange(start: Long, end: Long): List<StudioPayment> {
        val rows = mutableListOf<StudioPayment>()
        readableDatabase.rawQuery("SELECT id,amount_pesewas,method,reference,created FROM payments WHERE created>=? AND created<? ORDER BY created", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) rows.add(StudioPayment(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4)))
        }
        return rows
    }
    fun all(): List<StudioJob> {
        val rows = mutableListOf<StudioJob>()
        readableDatabase.rawQuery("SELECT id,customer,phone,service,total_pesewas,paid_pesewas,created,workflow_status,due_date FROM jobs ORDER BY created DESC", null).use { c ->
            while (c.moveToNext()) rows.add(StudioJob(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getString(7), c.getString(8)))
        }
        return rows
    }
}

private fun createProfessionalReceipt(context: Context, job: StudioJob, payments: List<StudioPayment>): File {
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val canvas = page.canvas
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(28,38,55); textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    canvas.drawText("PASSPORTPRO STUDIO", 42f, 58f, paint)
    paint.textSize = 12f; paint.typeface = Typeface.DEFAULT
    canvas.drawText("CUSTOMER PAYMENT RECEIPT", 42f, 82f, paint)
    paint.color = android.graphics.Color.rgb(190,135,45); canvas.drawRect(42f,98f,553f,101f,paint)
    paint.color = android.graphics.Color.DKGRAY
    var y = 135f
    val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val fields = listOf("Receipt number" to "PP-${job.id}", "Issued" to fmt.format(Date()), "Customer" to job.customer,
        "Phone" to job.phone.ifBlank { "Not provided" }, "Service" to job.service, "Workflow" to job.workflow,
        "Total amount" to money(job.totalPesewas), "Total received" to money(job.paidPesewas),
        "Outstanding balance" to money(job.balancePesewas), "Payment status" to job.paymentStatus)
    fields.forEach { (label,value) ->
        paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD); canvas.drawText(label,48f,y,paint)
        paint.typeface=Typeface.DEFAULT; canvas.drawText(value.take(55),245f,y,paint); y+=27f
    }
    y+=14f; paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD); canvas.drawText("PAYMENT HISTORY",48f,y,paint); y+=24f
    paint.typeface=Typeface.DEFAULT
    if (payments.isEmpty()) { canvas.drawText("No payment transactions recorded.",48f,y,paint); y+=20f }
    payments.take(18).forEach { payment ->
        canvas.drawText("${fmt.format(Date(payment.created))} • ${payment.method} • ${money(payment.amountPesewas)}".take(78),48f,y,paint); y+=20f
        if(payment.reference.isNotBlank()){canvas.drawText("Reference: ${payment.reference}".take(78),60f,y,paint);y+=18f}
    }
    paint.color=android.graphics.Color.rgb(28,38,55)
    canvas.drawText("Thank you for your patronage.",48f,790f,paint)
    pdf.finishPage(page)
    val dir=File(context.cacheDir,"receipts").apply{mkdirs()}
    val file=File(dir,"PassportPro_Receipt_PP-${job.id}.pdf")
    FileOutputStream(file).use{pdf.writeTo(it)}
    pdf.close()
    return file
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
    var paymentJob by remember { mutableStateOf<StudioJob?>(null) }
    var paymentAmount by remember { mutableStateOf("") }
    var paymentMethod by remember { mutableStateOf("Cash") }
    var paymentReference by remember { mutableStateOf("") }
    var paymentMessage by remember { mutableStateOf("") }
    var editJob by remember { mutableStateOf<StudioJob?>(null) }
    var editCustomer by remember { mutableStateOf("") }
    var editPhone by remember { mutableStateOf("") }
    var editService by remember { mutableStateOf("") }
    var editTotal by remember { mutableStateOf("") }
    var editDue by remember { mutableStateOf("") }
    var editMessage by remember { mutableStateOf("") }
    var expenseCategory by remember { mutableStateOf("Supplies") }
    var expenseDescription by remember { mutableStateOf("") }
    var expenseAmount by remember { mutableStateOf("") }
    var expenseMethod by remember { mutableStateOf("Cash") }
    var expenseMessage by remember { mutableStateOf("") }
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
    val periodExpenses = db.expenses(start, now.timeInMillis + 1)
    val expenseTotal = periodExpenses.sumOf { it.amountPesewas }
    val operatingCashflow = collected - expenseTotal
    val expenseBreakdown = db.expenseTotals(start, now.timeInMillis + 1)
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
        Text("BUSINESS • EXPENSES & FINANCIAL CONTROL", color = BizGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Studio operations", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Track each job from registration to delivery, review customer history and monitor business performance.", color = BizMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("TODAY'S JOBS", todayJobs.size.toString(), Modifier.weight(1f))
            MetricCard("TODAY'S SALES", money(todayJobs.sumOf { it.totalPesewas }), Modifier.weight(1f))
        }
        val cashbookLines = db.cashbook(todayStart, System.currentTimeMillis()+1)
        val cashbookTotal = cashbookLines.sumOf { it.amountPesewas }
        Surface(color=BizPanel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text("TODAY'S CASHBOOK",color=BizGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(money(cashbookTotal),color=PrimaryText,fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("${cashbookLines.sumOf{it.count}} payment transaction(s)",color=BizMuted,fontSize=10.sp)
                cashbookLines.forEach { Text("${it.method}: ${it.count} transaction(s) • ${money(it.amountPesewas)}",color=PrimaryText,fontSize=11.sp) }
                if(cashbookLines.isEmpty()) Text("No payments have been recorded today.",color=BizMuted,fontSize=11.sp)
                OutlinedButton(onClick={
                    val rows=db.paymentsInRange(todayStart,System.currentTimeMillis()+1)
                    val report=buildString {
                        append("PASSPORTPRO STUDIO — DAILY CASHBOOK\n")
                        append(SimpleDateFormat("dd MMM yyyy",Locale.getDefault()).format(Date())).append("\n\n")
                        cashbookLines.forEach { append(it.method).append(": ").append(it.count).append(" payment(s), ").append(money(it.amountPesewas)).append("\n") }
                        append("\nTOTAL RECEIVED: ").append(money(cashbookTotal)).append("\n\nTRANSACTIONS\n")
                        rows.forEach { append("#").append(it.id).append(" • ").append(it.method).append(" • ").append(money(it.amountPesewas)).append(" • ").append(SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(it.created))).append("\n") }
                    }
                    val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"Daily cashbook");putExtra(Intent.EXTRA_TEXT,report)}
                    context.startActivity(Intent.createChooser(send,"Share daily cashbook"))
                }) { Text("SHARE DAILY CASHBOOK") }
            }
        }
        Surface(color = BizPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("EXPENSE MANAGEMENT", color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Supplies", "Transport", "Rent", "Utilities").forEach { c -> FilterChip(selected = expenseCategory == c, onClick = { expenseCategory = c }, label = { Text(c, fontSize = 9.sp) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Equipment", "Maintenance", "Marketing", "Other").forEach { c -> FilterChip(selected = expenseCategory == c, onClick = { expenseCategory = c }, label = { Text(c, fontSize = 9.sp) }) }
                }
                OutlinedTextField(expenseDescription, { expenseDescription = it }, label = { Text("Description / supplier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(expenseAmount, { expenseAmount = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Amount (GH₵)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Cash", "Mobile Money", "Bank", "Other").forEach { m -> FilterChip(selected = expenseMethod == m, onClick = { expenseMethod = m }, label = { Text(m, fontSize = 9.sp) }) }
                }
                Button(onClick = {
                    val amount = ((expenseAmount.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    if (amount <= 0L) expenseMessage = "Enter a valid amount."
                    else if (db.addExpense(expenseCategory, expenseDescription, amount, expenseMethod)) { expenseAmount = ""; expenseDescription = ""; expenseMessage = "Expense recorded." }
                    else expenseMessage = "Could not save expense."
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BizGold, contentColor = Color(0xFF101522))) { Text("SAVE EXPENSE") }
                if (expenseMessage.isNotBlank()) Text(expenseMessage, color = BizGold, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("PERIOD EXPENSES", money(expenseTotal), Modifier.weight(1f))
            MetricCard("NET CASH MOVEMENT", money(operatingCashflow), Modifier.weight(1f))
        }
        Text("EXPENSE BREAKDOWN • " + period, color = BizGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        expenseBreakdown.forEach { Text(it.method + " • " + it.count + " item(s) • " + money(it.amountPesewas), color = PrimaryText, fontSize = 11.sp) }
        periodExpenses.take(10).forEach { e -> Text(e.category + " • " + e.description + " • " + money(e.amountPesewas), color = BizMuted, fontSize = 11.sp) }
        Text("Net cash movement is recorded customer payments less recorded expenses, not accounting profit.", color = BizMuted, fontSize = 10.sp)
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
            val paymentsCsv = buildString {
                append("Transaction ID,Job ID,Customer,Phone,Service,Payment Date,Method,Reference,Amount (GHS)\\n")
                jobs.forEach { job ->
                    db.payments(job.id).forEach { payment ->
                        val fields = listOf(payment.id.toString(), job.id.toString(), job.customer, job.phone, job.service,
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(payment.created)),
                            payment.method, payment.reference, String.format(Locale.US, "%.2f", payment.amountPesewas / 100.0))
                        append(fields.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\\n")
                    }
                }
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "PassportPro Studio payment ledger")
                putExtra(Intent.EXTRA_TEXT, paymentsCsv)
            }
            context.startActivity(Intent.createChooser(send, "Export payment ledger"))
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BizPanel, contentColor = BizGold)) {
            Text("EXPORT PAYMENT LEDGER AS CSV", fontWeight = FontWeight.Bold)
        }
        Button(onClick = {
            val csv = buildString {
                append("Job ID,Customer,Phone,Service,Workflow,Created,Due Date,Total (GHS),Paid (GHS),Balance (GHS),Payment Status\n")
                jobs.forEach { job ->
                    val fields = listOf(job.id.toString(), job.customer, job.phone, job.service, job.workflow,
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(job.created)), job.dueDate,
                        String.format(Locale.US, "%.2f", job.totalPesewas / 100.0),
                        String.format(Locale.US, "%.2f", job.paidPesewas),
                        String.format(Locale.US, "%.2f", job.balancePesewas), job.paymentStatus)
                    append(fields.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\n")
                }
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "PassportPro Studio jobs export")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            context.startActivity(Intent.createChooser(send, "Export job records"))
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BizPanel, contentColor = BizGold)) {
            Text("EXPORT ALL JOBS AS CSV", fontWeight = FontWeight.Bold)
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
                        Text(job.customer, color = PrimaryText, fontWeight = FontWeight.SemiBold)
                        Text("${job.service} • ${job.workflow}", color = BizMuted, fontSize = 11.sp)
                        Text("Paid ${money(job.paidPesewas)} • Balance ${money(job.balancePesewas)}", color = PrimaryText, fontSize = 11.sp)
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
                    TextButton(onClick = { selectedIds = if (selectedIds.size == jobs.size) emptySet() else jobs.map { it.id }.toSet() }) { Text(if (selectedIds.size == jobs.size) "Deselect all" else "Select all", color = PrimaryText) }
                    Text("${selectedIds.size} selected", color = BizMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { confirmDelete = true }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB93838))) { Text("Delete") }
                }
            }
        }
        jobs.forEach { job ->
            var menuOpen by remember(job.id) { mutableStateOf(false) }
            Surface(color = if (job.id in selectedIds) if (LocalPassportDark.current) Color(0xFF34445C) else Color(0xFFE8D8B8) else BizPanel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (selectionMode) TextButton(onClick = { selectedIds = if (job.id in selectedIds) selectedIds - job.id else selectedIds + job.id }) { Text(if (job.id in selectedIds) "✓ Selected" else "Select job", color = BizGold) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.customer, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text(job.paymentStatus, fontSize = 9.sp) })
                    }
                    Text(job.service, color = BizMuted, fontSize = 12.sp)
                    Text("Workflow: ${job.workflow}", color = BizGold, fontSize = 11.sp)
                    if (job.dueDate.isNotBlank()) Text("Expected delivery: ${job.dueDate}", color = BizMuted, fontSize = 10.sp)
                    Text("Total ${money(job.totalPesewas)} • Paid ${money(job.paidPesewas)} • Balance ${money(job.balancePesewas)}", color = PrimaryText, fontSize = 11.sp)
                    Text(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(job.created)), color = BizMuted, fontSize = 10.sp)
                    if (!selectionMode) {
                        if (job.balancePesewas > 0L) TextButton(onClick = { paymentJob = job; paymentAmount = ""; paymentReference = ""; paymentMethod = "Cash"; paymentMessage = "" }) { Text("RECORD PAYMENT", color = BizGold, fontSize = 11.sp) }
                        val ledger = db.payments(job.id)
                        Text("PAYMENT LEDGER • ${ledger.size} transaction(s)", color = BizMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        ledger.take(3).forEach { payment ->
                            val paymentDate = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(payment.created))
                            Text("${money(payment.amountPesewas)} • ${payment.method} • $paymentDate" + if (payment.reference.isNotBlank()) " • ${payment.reference}" else "", color = PrimaryText, fontSize = 10.sp)
                        }
                        TextButton(onClick = {
                            editJob = job
                            editCustomer = job.customer
                            editPhone = job.phone
                            editService = job.service
                            editTotal = String.format(Locale.US, "%.2f", job.totalPesewas / 100.0)
                            editDue = job.dueDate
                            editMessage = ""
                        }) { Text("EDIT JOB DETAILS", color = BizGold, fontSize = 10.sp) }
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("UPDATE JOB STAGE ▾", color = BizGold, fontSize = 10.sp) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                workflowStages.forEach { stage ->
                                    DropdownMenuItem(text = { Text(stage) }, onClick = { db.updateWorkflow(job.id, stage); jobs = db.all(); menuOpen = false })
                                }
                            }
                        }
                        TextButton(onClick = {
                            try {
                                val file=createProfessionalReceipt(context,job,db.payments(job.id).reversed())
                                val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                                val send=Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_SUBJECT,"PassportPro receipt PP-${job.id}");putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                                context.startActivity(Intent.createChooser(send,"Share PDF customer receipt"))
                            } catch(e:Exception) { message="Could not create receipt PDF: ${e.localizedMessage ?: "Please try again."}" }
                        }) { Icon(Icons.Default.ReceiptLong,null,tint=BizGold);Spacer(Modifier.width(5.dp));Text("CREATE / SHARE PDF RECEIPT",color=BizGold,fontSize=11.sp) }
                    }
                }
            }
        }
        if (editJob != null) {
            val target = editJob!!
            AlertDialog(onDismissRequest = { editJob = null }, title = { Text("Edit job details") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(editCustomer, { editCustomer = it }, label = { Text("Customer name") }, singleLine = true)
                    OutlinedTextField(editPhone, { editPhone = it }, label = { Text("Phone") }, singleLine = true)
                    OutlinedTextField(editService, { editService = it }, label = { Text("Service") }, singleLine = true)
                    OutlinedTextField(editTotal, { editTotal = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Total (GH₵)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(editDue, { editDue = it }, label = { Text("Due date") }, singleLine = true)
                    Text("Total cannot be lower than payments already received (" + money(target.paidPesewas) + ").", color = BizMuted, fontSize = 10.sp)
                    if (editMessage.isNotBlank()) Text(editMessage, color = Color(0xFFE18A75), fontSize = 11.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    val amount = ((editTotal.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    if (amount < target.paidPesewas) editMessage = "Total cannot be less than the amount already paid."
                    else if (db.updateJob(target, editCustomer, editPhone, editService, amount, editDue)) {
                        jobs = db.all()
                        editJob = null
                        message = "Job updated successfully."
                    } else editMessage = "Enter a valid customer, service and total."
                }) { Text("Save changes", color = BizGold) } },
                dismissButton = { TextButton(onClick = { editJob = null }) { Text("Cancel") } })
        }
        if (paymentJob != null) {
            val target = paymentJob!!
            AlertDialog(
                onDismissRequest = { paymentJob = null },
                title = { Text("Record customer payment") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.customer} • Outstanding: ${money(target.balancePesewas)}")
                    OutlinedTextField(paymentAmount, { paymentAmount = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Amount received (GH₵)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    Text("Payment method", color = BizMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Cash", "Mobile Money", "Bank", "Other").forEach { method ->
                            FilterChip(selected = paymentMethod == method, onClick = { paymentMethod = method }, label = { Text(method, fontSize = 9.sp) })
                        }
                    }
                    OutlinedTextField(paymentReference, { paymentReference = it }, label = { Text("Reference / note (optional)") }, singleLine = true)
                    if (paymentMessage.isNotBlank()) Text(paymentMessage, color = BizGold, fontSize = 11.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    val amount = ((paymentAmount.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    if (amount <= 0L) paymentMessage = "Enter a valid payment amount."
                    else if (amount > target.balancePesewas) paymentMessage = "Payment exceeds the outstanding balance."
                    else if (db.addPayment(target.id, amount, paymentMethod, paymentReference)) {
                        jobs = db.all(); paymentJob = null; message = "Payment recorded successfully."
                    } else paymentMessage = "Payment could not be saved. Refresh and try again."
                }) { Text("Save payment", color = BizGold) } },
                dismissButton = { TextButton(onClick = { paymentJob = null }) { Text("Cancel") } }
            )
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
