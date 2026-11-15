package gh.com.bigsammy.passportpro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
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

private data class StudioJob(val id: Long, val customer: String, val phone: String, val service: String, val totalPesewas: Long, val paidPesewas: Long, val created: Long) {
    val balancePesewas get() = (totalPesewas - paidPesewas).coerceAtLeast(0)
    val status get() = when { balancePesewas == 0L -> "PAID"; paidPesewas > 0L -> "PARTIAL"; else -> "UNPAID" }
}
private class BusinessDatabase(context: Context) : SQLiteOpenHelper(context, "passportpro_business.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', service TEXT NOT NULL, total_pesewas INTEGER NOT NULL, paid_pesewas INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun add(customer: String, phone: String, service: String, total: Long, paid: Long) {
        val values = ContentValues().apply { put("customer",customer.trim()); put("phone",phone.trim()); put("service",service.trim()); put("total_pesewas",total); put("paid_pesewas",paid.coerceIn(0,total)); put("created",System.currentTimeMillis()) }
        writableDatabase.insert("jobs",null,values)
    }
    fun delete(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return writableDatabase.delete("jobs", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }
    fun all(): List<StudioJob> {
        val rows=mutableListOf<StudioJob>()
        readableDatabase.rawQuery("SELECT id,customer,phone,service,total_pesewas,paid_pesewas,created FROM jobs ORDER BY created DESC",null).use { c ->
            while(c.moveToNext()) rows.add(StudioJob(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getLong(6)))
        }
        return rows
    }
}
private fun money(pesewas: Long) = "GH₵ " + String.format(Locale.US,"%.2f",pesewas/100.0)

@Composable
internal fun BusinessManagementScreen(modifier: Modifier = Modifier) {
    val context= LocalContext.current
    val db= remember { BusinessDatabase(context) }
    var jobs by remember { mutableStateOf(db.all()) }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var service by remember { mutableStateOf("Passport photograph") }
    var total by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("0") }
    var message by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedJobIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmDeleteJobs by remember { mutableStateOf(false) }
    val totalPesewas=((total.toDoubleOrNull() ?: 0.0)*100).toLong().coerceAtLeast(0)
    val paidPesewas=((paid.toDoubleOrNull() ?: 0.0)*100).toLong().coerceAtLeast(0)
    val calendar=Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY,0); calendar.set(Calendar.MINUTE,0); calendar.set(Calendar.SECOND,0); calendar.set(Calendar.MILLISECOND,0)
    val todayJobs=jobs.filter { it.created >= calendar.timeInMillis }
    val todaySales=todayJobs.sumOf { it.totalPesewas }
    val received=jobs.sumOf { it.paidPesewas }
    val outstanding=jobs.sumOf { it.balancePesewas }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("PHASE 6 • STUDIO BUSINESS",color=BizGold,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.2.sp)
        Text("Jobs & payments",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("Record customer orders, track payments and issue shareable receipts. Records stay on this device.",color=BizMuted,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MetricCard("TODAY'S JOBS",todayJobs.size.toString(),Modifier.weight(1f))
            MetricCard("TODAY'S SALES",money(todaySales),Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MetricCard("RECEIVED",money(received),Modifier.weight(1f))
            MetricCard("OUTSTANDING",money(outstanding),Modifier.weight(1f))
        }
        Surface(color=BizPanel,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("REGISTER A CUSTOMER JOB",color=BizGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                OutlinedTextField(customer,{customer=it},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(phone,{phone=it},label={Text("Phone / WhatsApp (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
                OutlinedTextField(service,{service=it},label={Text("Service / job description")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(total,{total=it.filter { ch -> ch.isDigit() || ch=='.' }},label={Text("Total (GH₵)")},singleLine=true,modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    OutlinedTextField(paid,{paid=it.filter { ch -> ch.isDigit() || ch=='.' }},label={Text("Amount paid")},singleLine=true,modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                }
                Text("Balance: " + money((totalPesewas-paidPesewas).coerceAtLeast(0)),color=BizMuted,fontSize=12.sp)
                Button(onClick={
                    if(customer.isBlank() || service.isBlank() || totalPesewas<=0L) message="Enter a customer, service and valid total amount."
                    else if(paidPesewas>totalPesewas) message="Amount paid cannot exceed the job total."
                    else { db.add(customer,phone,service,totalPesewas,paidPesewas); jobs=db.all(); customer=""; phone=""; service="Passport photograph"; total=""; paid="0"; message="Job saved successfully." }
                },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=BizGold,contentColor=Color(0xFF101522))) {
                    Icon(Icons.Default.Add,null); Spacer(Modifier.width(7.dp)); Text("SAVE CUSTOMER JOB",fontWeight=FontWeight.Bold)
                }
                if(message.isNotBlank()) Text(message,color=BizGold,fontSize=11.sp)
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("RECENT JOBS",color=BizMuted,fontSize=11.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.weight(1f)); Text(jobs.size.toString()+" records",color=BizGold,fontSize=11.sp)
        }
        if (jobs.isNotEmpty()) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ selectionMode=!selectionMode; selectedJobIds=emptySet() }) { Text(if(selectionMode) "Cancel selection" else "Select jobs",color=BizGold) }
                if(selectionMode) {
                    TextButton(onClick={ selectedJobIds=if(selectedJobIds.size==jobs.size) emptySet() else jobs.map { it.id }.toSet() }) { Text(if(selectedJobIds.size==jobs.size) "Deselect all" else "Select all",color=Color.White) }
                    Text(selectedJobIds.size.toString()+" selected",color=BizMuted,fontSize=11.sp,modifier=Modifier.weight(1f))
                    Button(onClick={confirmDeleteJobs=true},enabled=selectedJobIds.isNotEmpty(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB93838))) { Text("Delete") }
                }
            }
        }
        if(jobs.isEmpty()) {
            Surface(color=BizPanel,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) { Text("No jobs recorded yet. Add the first customer job above.",color=BizMuted,modifier=Modifier.padding(16.dp),fontSize=12.sp) }
        } else {
            jobs.forEach { job ->
                Surface(color=if(job.id in selectedJobIds) Color(0xFF34445C) else BizPanel,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        if(selectionMode) TextButton(onClick={ selectedJobIds=if(job.id in selectedJobIds) selectedJobIds-job.id else selectedJobIds+job.id }) { Text(if(job.id in selectedJobIds) "✓ Selected" else "Select job",color=BizGold) }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(job.customer,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=15.sp,modifier=Modifier.weight(1f))
                            AssistChip(onClick={},label={Text(job.status,fontSize=9.sp)},colors=AssistChipDefaults.assistChipColors(labelColor=if(job.status=="PAID") Color(0xFF64D6A0) else BizGold))
                        }
                        Text(job.service,color=BizMuted,fontSize=12.sp)
                        Text("Total "+money(job.totalPesewas)+" • Paid "+money(job.paidPesewas)+" • Balance "+money(job.balancePesewas),color=Color.White,fontSize=11.sp)
                        Text(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(job.created)),color=BizMuted,fontSize=10.sp)
                        if(!selectionMode) TextButton(onClick={
                            val date=SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(job.created))
                            val receipt="PASSPORTPRO STUDIO\nCUSTOMER RECEIPT\n\nReceipt No: PP-"+job.id+"\nDate: "+date+"\nCustomer: "+job.customer+"\nPhone: "+job.phone+"\nService: "+job.service+"\nTotal: "+money(job.totalPesewas)+"\nAmount paid: "+money(job.paidPesewas)+"\nBalance: "+money(job.balancePesewas)+"\nStatus: "+job.status+"\n\nThank you for your patronage."
                            val send=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_SUBJECT,"PassportPro receipt PP-"+job.id); putExtra(Intent.EXTRA_TEXT,receipt) }
                            context.startActivity(Intent.createChooser(send,"Share customer receipt"))
                        }) { Icon(Icons.Default.ReceiptLong,null,tint=BizGold); Spacer(Modifier.width(5.dp)); Text("SHARE RECEIPT",color=BizGold,fontSize=11.sp) }
                    }
                }
            }
        }
        if(confirmDeleteJobs) AlertDialog(
            onDismissRequest={confirmDeleteJobs=false},
            title={Text("Delete selected jobs?")},
            text={Text("Permanently delete "+selectedJobIds.size+" selected job records? This action cannot be undone.")},
            confirmButton={TextButton(onClick={
                val count=db.delete(selectedJobIds)
                jobs=db.all(); selectedJobIds=emptySet(); selectionMode=false; confirmDeleteJobs=false
                message=count.toString()+" job record(s) deleted."
            }) { Text("Delete permanently",color=Color(0xFFB93838)) }},
            dismissButton={TextButton(onClick={confirmDeleteJobs=false}) { Text("Cancel") }}
        )
        Text("Offline storage • Ghana cedi (GH₵) • Receipts shared through your phone's share menu",color=BizMuted,fontSize=10.sp)
    }
}

@Composable
private fun MetricCard(label:String,value:String,modifier:Modifier=Modifier) {
    Surface(color=BizPanel,shape=RoundedCornerShape(14.dp),modifier=modifier) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(label,color=BizMuted,fontSize=9.sp,fontWeight=FontWeight.Bold)
            Text(value,color=BizGold,fontSize=15.sp,fontWeight=FontWeight.Bold)
        }
    }
}
