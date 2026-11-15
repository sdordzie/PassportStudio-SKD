package gh.com.bigsammy.passportpro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class StudioCustomer(val id: Long, val name: String, val phone: String, val notes: String, val created: Long)

private class CustomerDatabase(context: Context) : SQLiteOpenHelper(context, "passportpro_customers.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun all(): List<StudioCustomer> {
        val result = mutableListOf<StudioCustomer>()
        readableDatabase.rawQuery("SELECT id,name,phone,notes,created FROM customers ORDER BY created DESC", null).use { cursor ->
            while (cursor.moveToNext()) result.add(StudioCustomer(cursor.getLong(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4)))
        }
        return result
    }
    fun delete(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return writableDatabase.delete("customers", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }
    fun add(name: String, phone: String, notes: String) {
        val values = ContentValues().apply { put("name",name.trim()); put("phone",phone.trim()); put("notes",notes.trim()); put("created",System.currentTimeMillis()) }
        writableDatabase.insert("customers",null,values)
    }
}

@Composable
internal fun CustomersScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { CustomerDatabase(context) }
    var customers by remember { mutableStateOf(db.all()) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedCustomerIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmDeleteCustomers by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CUSTOMER MANAGEMENT • PHASE 4", color=Color(0xFFE4B45D), fontSize=11.sp, fontWeight=FontWeight.Bold)
        Text("Customer records", color=Color.White, fontSize=24.sp, fontWeight=FontWeight.Bold)
        Text("Save customer contacts and notes locally on this device.", color=Color(0xFF9AA7B8), fontSize=12.sp)
        OutlinedTextField(value=name,onValueChange={name=it},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=phone,onValueChange={phone=it},label={Text("Phone / WhatsApp number")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=notes,onValueChange={notes=it},label={Text("Notes (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2)
        Button(onClick={
            if(name.isNotBlank()) { db.add(name,phone,notes); name=""; phone=""; notes=""; customers=db.all() }
        },enabled=name.isNotBlank(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))) {
            Icon(Icons.Default.PersonAdd,null); Spacer(Modifier.width(8.dp)); Text("SAVE CUSTOMER",fontWeight=FontWeight.Bold)
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("SAVED CUSTOMERS",color=Color(0xFF9AA7B8),fontSize=11.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.weight(1f)); Text("${customers.size} records",color=Color(0xFFE4B45D),fontSize=11.sp)
        }
        if(customers.isNotEmpty()) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={selectionMode=!selectionMode; selectedCustomerIds=emptySet()}) { Text(if(selectionMode) "Cancel selection" else "Select customers",color=Color(0xFFE4B45D)) }
                if(selectionMode) {
                    TextButton(onClick={selectedCustomerIds=if(selectedCustomerIds.size==customers.size) emptySet() else customers.map { it.id }.toSet()}) { Text(if(selectedCustomerIds.size==customers.size) "Deselect all" else "Select all",color=Color.White) }
                    Text(selectedCustomerIds.size.toString()+" selected",color=Color(0xFF9AA7B8),fontSize=11.sp,modifier=Modifier.weight(1f))
                    Button(onClick={confirmDeleteCustomers=true},enabled=selectedCustomerIds.isNotEmpty(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB93838))) { Text("Delete") }
                }
            }
        }
        if(customers.isEmpty()) {
            Surface(color=Color(0xFF1B2433),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Text("No customers yet. Add your first customer above.",color=Color(0xFF9AA7B8),modifier=Modifier.padding(18.dp),fontSize=13.sp)
            }
        } else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f)) {
            items(customers,key={it.id}) { customer ->
                Surface(color=if(customer.id in selectedCustomerIds) Color(0xFF34445C) else Color(0xFF1B2433),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(selectionMode) TextButton(onClick={selectedCustomerIds=if(customer.id in selectedCustomerIds) selectedCustomerIds-customer.id else selectedCustomerIds+customer.id}) { Text(if(customer.id in selectedCustomerIds) "✓ Selected" else "Select customer",color=Color(0xFFE4B45D)) }
                        Text(customer.name,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=16.sp)
                        if(customer.phone.isNotBlank()) Text(customer.phone,color=Color(0xFFE4B45D),fontSize=13.sp)
                        if(customer.notes.isNotBlank()) Text(customer.notes,color=Color(0xFF9AA7B8),fontSize=12.sp)
                        Text(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(customer.created)),color=Color(0xFF718096),fontSize=10.sp)
                    }
                }
            }
        }
    }
    if(confirmDeleteCustomers) AlertDialog(
        onDismissRequest={confirmDeleteCustomers=false},
        title={Text("Delete selected customers?")},
        text={Text("Permanently delete "+selectedCustomerIds.size+" selected customer records? This cannot be undone.")},
        confirmButton={TextButton(onClick={
            val count=db.delete(selectedCustomerIds)
            customers=db.all(); selectedCustomerIds=emptySet(); selectionMode=false; confirmDeleteCustomers=false
            android.widget.Toast.makeText(context,count.toString()+" customer record(s) deleted",android.widget.Toast.LENGTH_SHORT).show()
        }) { Text("Delete permanently",color=Color(0xFFB93838)) }},
        dismissButton={TextButton(onClick={confirmDeleteCustomers=false}) { Text("Cancel") }}
    )

}

@Composable
internal fun GalleryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val files = remember(refresh) {
        listOf(File(context.filesDir, "PassportPro/Captures"), File(context.getExternalFilesDir(null), "PassportPro/Edited"))
            .flatMap { root -> if (root.exists()) root.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png") }.toList() else emptyList() }
            .sortedByDescending { it.lastModified() }
    }
    val selectedBitmap = remember(selectedFile?.absolutePath, selectedFile?.lastModified()) {
        selectedFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    fun toggleSelection(file: File) {
        val path = file.absolutePath
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PHOTO LIBRARY • STUDIO", color = Color(0xFFE4B45D), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Studio gallery", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("${files.size} photographs • Stored securely on this device", color = Color(0xFF9AA7B8), fontSize = 11.sp)
            }
            TextButton(onClick = { refresh++ }) { Text("Refresh", color = Color(0xFFE4B45D)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = Color(0xFF1B2433), shape = RoundedCornerShape(12.dp)) { Text("CAPTURED  ${files.count { it.parentFile?.name != "Edited" }}", color = Color.White, modifier = Modifier.padding(10.dp), fontSize = 10.sp) }
            Surface(color = Color(0xFF1B2433), shape = RoundedCornerShape(12.dp)) { Text("EDITED  ${files.count { it.parentFile?.name == "Edited" }}", color = Color(0xFFE4B45D), modifier = Modifier.padding(10.dp), fontSize = 10.sp) }
        }
        if (files.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    selectionMode = !selectionMode
                    selectedPaths = emptySet()
                }) { Text(if (selectionMode) "Cancel selection" else "Select photos", color = Color(0xFFE4B45D)) }
                if (selectionMode) {
                    TextButton(onClick = {
                        selectedPaths = if (selectedPaths.size == files.size) emptySet() else files.map { it.absolutePath }.toSet()
                    }) { Text(if (selectedPaths.size == files.size) "Deselect all" else "Select all", color = Color.White) }
                    Text("${selectedPaths.size} selected", color = Color(0xFF9AA7B8), fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { confirmDelete = true }, enabled = selectedPaths.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB93838))) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete")
                    }
                }
            }
        }
        if (files.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Photo, null, tint = Color(0xFFE4B45D), modifier = Modifier.size(56.dp))
                Text("Your studio is waiting for its first photo", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Captured and edited photographs will appear here automatically.", color = Color(0xFF9AA7B8), fontSize = 12.sp)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 104.dp), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    val isSelected = file.absolutePath in selectedPaths
                    val thumb = remember(file.absolutePath, file.lastModified()) {
                        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                    Surface(color = if (isSelected) Color(0xFF34445C) else Color(0xFF1B2433), shape = RoundedCornerShape(13.dp),
                        onClick = {
                            if (selectionMode) toggleSelection(file) else selectedFile = file
                        }) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box {
                                if (thumb != null) Image(thumb.asImageBitmap(), "Photo thumbnail", Modifier.fillMaxWidth().height(142.dp), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxWidth().height(142.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Photo, null, tint = Color(0xFF9AA7B8)) }
                                if (selectionMode) Surface(color = if (isSelected) Color(0xFFE4B45D) else Color(0xCC101522), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                                    Text(if (isSelected) "✓" else " ", color = Color(0xFF101522), modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(if (file.parentFile?.name == "Edited") "EDITED PHOTO" else "ORIGINAL CAPTURE", color = Color(0xFFE4B45D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified())), color = Color(0xFF9AA7B8), fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete selected photographs?") },
            text = { Text("This will permanently delete ${selectedPaths.size} selected file(s) from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val targets = files.filter { it.absolutePath in selectedPaths }
                    val deleted = targets.count { it.exists() && it.delete() }
                    selectedPaths = emptySet()
                    selectionMode = false
                    confirmDelete = false
                    refresh++
                    android.widget.Toast.makeText(context, "$deleted photograph(s) deleted", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Delete permanently", color = Color(0xFFB93838)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    if (selectedFile != null) {
        Dialog(onDismissRequest = { selectedFile = null }) {
            Surface(color=Color(0xFF101522),shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(selectedFile?.parentFile?.name=="Edited") "EDITED PHOTOGRAPH" else "ORIGINAL CAPTURE",color=Color(0xFFE4B45D),fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text(selectedFile?.name ?: "",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                        }
                        IconButton(onClick={selectedFile=null}) { Icon(Icons.Default.Close,"Close",tint=Color.White) }
                    }
                    if(selectedBitmap!=null) Image(selectedBitmap.asImageBitmap(),"Full photograph preview",Modifier.fillMaxWidth().heightIn(max=480.dp),contentScale=ContentScale.Fit)
                    Text(selectedFile?.let { SimpleDateFormat("dd MMMM yyyy, HH:mm",Locale.getDefault()).format(Date(it.lastModified())) } ?: "",color=Color(0xFF9AA7B8),fontSize=11.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={
                            selectedFile?.let { file ->
                                try {
                                    val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                                    val intent=Intent(Intent.ACTION_SEND).apply { type="image/*"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                    context.startActivity(Intent.createChooser(intent,"Share photograph"))
                                } catch(e:Exception) { android.widget.Toast.makeText(context,"Unable to share photo: ${e.localizedMessage}",android.widget.Toast.LENGTH_LONG).show() }
                            }
                        },modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))) {
                            Icon(Icons.Default.Share,null); Spacer(Modifier.width(5.dp)); Text("Share")
                        }
                        OutlinedButton(onClick={
                            selectedFile?.let { file ->
                                if (file.exists() && file.delete()) {
                                    selectedFile=null; refresh++
                                    android.widget.Toast.makeText(context,"Photograph deleted",android.widget.Toast.LENGTH_SHORT).show()
                                } else android.widget.Toast.makeText(context,"Unable to delete photograph",android.widget.Toast.LENGTH_LONG).show()
                            }
                        },modifier=Modifier.weight(1f)) { Icon(Icons.Default.Delete,null); Text("Delete") }
                    }
                }
            }
        }
    }
}
