package com.bigsammy.sammessagescheduler
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bigsammy.sammessagescheduler.data.*
import com.bigsammy.sammessagescheduler.scheduling.MessageScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
private val Purple=Color(0xFF6750A4); private val Navy=Color(0xFF18243A); private val Pale=Color(0xFFF5F3FA)
class MainActivity: ComponentActivity() {
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
  val dao=AppDatabase.get(this).messageDao()
  setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Purple,background=Pale)) {
   val messages by dao.observeAll().collectAsStateWithLifecycle(initialValue=emptyList())
   SchedulerScreen(messages,
    save={r,b,c,t-> lifecycleScope.launch { val id=dao.insert(MessageEntity(recipient=r,body=b,channel=c,scheduledAt=t)); MessageScheduler.schedule(this@MainActivity,id,t) } },
    remove={id->lifecycleScope.launch { MessageScheduler.cancel(this@MainActivity,id); dao.delete(id) }},
    complete={m->lifecycleScope.launch { dao.update(m.copy(status="COMPLETED")) }})
  } }
 }
}
@Composable private fun SchedulerScreen(messages: List<MessageEntity>,save:(String,String,String,Long)->Unit,remove:(Long)->Unit,complete:(MessageEntity)->Unit) {
 var page by remember { mutableStateOf("Home") }; var compose by remember { mutableStateOf(false) }
 Scaffold(containerColor=Pale,topBar={ Row(Modifier.fillMaxWidth().background(Color.White).padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
  Icon(Icons.Default.Forum,null,tint=Purple,modifier=Modifier.size(30.dp)); Spacer(Modifier.width(10.dp))
  Column(Modifier.weight(1f)){ Text("SAM Message Scheduler",fontWeight=FontWeight.Bold,color=Navy,fontSize=17.sp); Text("Plan it. Remember it. Send it.",fontSize=11.sp,color=Color.Gray) }
  Icon(Icons.Default.Schedule,null,tint=Purple)
 } },bottomBar={ NavigationBar(containerColor=Color.White) {
  listOf("Home","Scheduled","Calendar","Settings").forEach { name -> NavigationBarItem(selected=page==name,onClick={page=name},icon={Icon(when(name){"Home"->Icons.Default.Home;"Scheduled"->Icons.Default.Schedule;"Calendar"->Icons.Default.CalendarMonth;else->Icons.Default.Settings},null)},label={Text(name,fontSize=10.sp)}) }
 } },floatingActionButton={ if(page!="Settings") FloatingActionButton(onClick={compose=true},containerColor=Purple){Icon(Icons.Default.Add,null,tint=Color.White)} }) { pad ->
  when(page) {
   "Home" -> Column(Modifier.padding(pad).fillMaxSize().padding(18.dp)) {
    Text("Good day 👋",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Navy); Text("Your messages, organised and on time.",color=Color.Gray)
    Row(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
     Stat("Scheduled",messages.count{it.status=="SCHEDULED"}.toString(),Modifier.weight(1f)); Stat("Completed",messages.count{it.status=="COMPLETED"}.toString(),Modifier.weight(1f))
    }
    Card(colors=CardDefaults.cardColors(containerColor=Navy),shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(20.dp)) {
     Text("Make every message count",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Color.White)
     Text("Prepare an SMS or WhatsApp reminder in seconds.",color=Color.White.copy(alpha=.8f),modifier=Modifier.padding(top=6.dp))
     Button(onClick={compose=true},modifier=Modifier.padding(top=14.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Navy)){Icon(Icons.Default.Add,null);Text(" Schedule message")}
    } }
    Text("UPCOMING MESSAGES",fontWeight=FontWeight.Bold,color=Navy,modifier=Modifier.padding(top=22.dp,bottom=8.dp))
    messages.filter{it.status=="SCHEDULED"}.sortedBy{it.scheduledAt}.take(5).forEach { MessageCard(it) }
   }
   "Scheduled" -> LazyColumn(Modifier.padding(pad).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    item { Text("Your messages",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Navy) }
    items(messages,key={it.id}) { m -> Card(colors=CardDefaults.cardColors(containerColor=Color.White)) { Column(Modifier.padding(12.dp)) {
     MessageCard(m)
     Row(Modifier.align(Alignment.End)) { if(m.status=="SCHEDULED") TextButton(onClick={complete(m)}){Text("Mark done")}; TextButton(onClick={remove(m.id)){Text("Cancel",color=Color.Red)} }
    } } }
   }
   "Calendar" -> LazyColumn(Modifier.padding(pad).padding(18.dp)) {
    item { Text("Schedule calendar",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Navy) }
    messages.sortedBy{it.scheduledAt}.groupBy{SimpleDateFormat("EEE, dd MMM yyyy",Locale.getDefault()).format(Date(it.scheduledAt))}.forEach { (day,list) ->
     item { Text(day,color=Purple,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp)) }
     items(list) { MessageCard(it) }
    }
   }
   else -> Column(Modifier.padding(pad).padding(18.dp)) {
    Text("Settings",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Navy)
    listOf("Notifications: allow reminders in Android settings.","SMS: Phase 1 opens a reminder for manual sending.","WhatsApp: reminder and confirmation workflow.","Privacy: scheduled messages are stored on this device.").forEach { Text(it,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp)) }
   }
  }
 }
 if(compose) Composer(onClose={compose=false},onSave={r,b,c,t->save(r,b,c,t);compose=false})
}
@Composable private fun Stat(title:String,value:String,modifier:Modifier) { Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(16.dp)){Text(value,fontSize=27.sp,fontWeight=FontWeight.Bold,color=Navy);Text(title,color=Color.Gray)}} }
@Composable private fun MessageCard(m:MessageEntity) { Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
 Icon(if(m.channel=="SMS") Icons.Default.Sms else Icons.Default.Chat,null,tint=Purple,modifier=Modifier.size(30.dp)); Spacer(Modifier.width(10.dp))
 Column(Modifier.weight(1f)){Text(m.recipient,fontWeight=FontWeight.SemiBold,color=Navy);Text(m.body,maxLines=2,fontSize=12.sp,color=Color.Gray);Text("${m.channel} • "+SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(m.scheduledAt)),fontSize=11.sp,color=Purple)}
 Text(m.status.lowercase(),fontSize=10.sp,color=Color.Gray)
} }
@Composable private fun Composer(onClose:()->Unit,onSave:(String,String,String,Long)->Unit) {
 var recipient by remember{mutableStateOf("")}; var body by remember{mutableStateOf("")}; var channel by remember{mutableStateOf("SMS")}
 var date by remember{mutableStateOf("")}; var time by remember{mutableStateOf("09:00")}; var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=onClose,title={Text("Schedule a message")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(channel=="SMS",{channel="SMS"},{Text("SMS")});FilterChip(channel=="WhatsApp",{channel="WhatsApp"},{Text("WhatsApp")})}
  OutlinedTextField(recipient,{recipient=it},label={Text("Recipient / phone number")},singleLine=true)
  OutlinedTextField(body,{body=it},label={Text("Message")},minLines=3)
  OutlinedTextField(date,{date=it},label={Text("Date (YYYY-MM-DD)")},singleLine=true)
  OutlinedTextField(time,{time=it},label={Text("Time (HH:mm)")},singleLine=true)
  if(error.isNotBlank()) Text(error,color=Color.Red)
  Text("SAM will remind you to send the message. WhatsApp may require confirmation.",fontSize=11.sp,color=Color.Gray)
 }},confirmButton={Button(onClick={try {
  val at=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).apply{isLenient=false}.parse("$date $time")!!.time
  if(recipient.isBlank()||body.isBlank()) error="Enter recipient and message." else if(at<=System.currentTimeMillis()) error="Choose a future time." else onSave(recipient,body,channel,at)
 }catch(_:Exception){error="Enter a valid future date and time."}}){Text("Save schedule")}},dismissButton={TextButton(onClick=onClose){Text("Close")}})
}