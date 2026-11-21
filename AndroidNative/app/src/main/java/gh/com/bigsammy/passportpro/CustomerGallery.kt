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
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
        Text("CUSTOMER MANAGEMENT", color=BrandAccent, fontSize=11.sp, fontWeight=FontWeight.Bold)
        Text("Customer records", color=PrimaryText, fontSize=24.sp, fontWeight=FontWeight.Bold)
        Text("Save customer contacts and notes locally on this device.", color=Muted, fontSize=12.sp)
        OutlinedTextField(value=name,onValueChange={name=it},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth(), colors = studioOutlinedTextFieldColors())
        OutlinedTextField(value=phone,onValueChange={phone=it},label={Text("Phone / WhatsApp number")},singleLine=true,modifier=Modifier.fillMaxWidth(), colors = studioOutlinedTextFieldColors())
        OutlinedTextField(value=notes,onValueChange={notes=it},label={Text("Notes (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2, colors = studioOutlinedTextFieldColors())
        StudioButton("Save customer", onClick={
            if(name.isNotBlank()) { db.add(name,phone,notes); name=""; phone=""; notes=""; customers=db.all() }
        }, enabled=name.isNotBlank(), icon=Icons.Default.PersonAdd, modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {
            SectionHeading("Saved customers")
            Spacer(Modifier.weight(1f))
            Text("${customers.size} records",color=BrandAccent,fontSize=11.sp)
        }
        if(customers.isNotEmpty()) {
            SelectionBar(
                active = selectionMode,
                selectedCount = selectedCustomerIds.size,
                allSelected = selectedCustomerIds.size == customers.size,
                startLabel = "Select customers",
                onToggleMode = { selectionMode = !selectionMode; selectedCustomerIds = emptySet() },
                onToggleAll = { selectedCustomerIds = if (selectedCustomerIds.size == customers.size) emptySet() else customers.map { it.id }.toSet() },
                onDelete = { confirmDeleteCustomers = true }
            )
        }
        if(customers.isEmpty()) {
            Surface(color=Panel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Text("No customers yet. Add your first customer above.",color=Muted,modifier=Modifier.padding(18.dp),fontSize=13.sp)
            }
        } else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f)) {
            items(customers,key={it.id}) { customer ->
                val picked = customer.id in selectedCustomerIds
                Surface(
                    onClick={ selectedCustomerIds = if (picked) selectedCustomerIds - customer.id else selectedCustomerIds + customer.id },
                    enabled=selectionMode,
                    color=if(picked) BrandAccent.copy(alpha=.14f) else Panel,
                    border=if(picked) BorderStroke(1.5.dp, BrandAccent) else null,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(customer.name,color=PrimaryText,fontWeight=FontWeight.SemiBold,fontSize=16.sp)
                            if(customer.phone.isNotBlank()) Text(customer.phone,color=BrandAccent,fontSize=13.sp)
                            if(customer.notes.isNotBlank()) Text(customer.notes,color=Muted,fontSize=12.sp)
                            Text(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(customer.created)),color=Muted,fontSize=10.sp)
                        }
                        if(selectionMode) Checkbox(checked=picked, onCheckedChange=null)
                    }
                }
            }
        }
    }
    if(confirmDeleteCustomers) AlertDialog(
        onDismissRequest={confirmDeleteCustomers=false},
        title={Text("Delete selected customers?")},
        text={Text("Permanently delete "+selectedCustomerIds.size+" selected customer records? This cannot be undone.")},
        confirmButton={StudioButton("Delete", tone=ButtonTone.Danger, compact=true, icon=Icons.Default.Delete, onClick={
            val count=db.delete(selectedCustomerIds)
            customers=db.all(); selectedCustomerIds=emptySet(); selectionMode=false; confirmDeleteCustomers=false
            android.widget.Toast.makeText(context,count.toString()+" customer record(s) deleted",android.widget.Toast.LENGTH_SHORT).show()
        })},
        dismissButton={StudioButton("Cancel", tone=ButtonTone.Secondary, compact=true, onClick={confirmDeleteCustomers=false})}
    )

}

@Composable
internal fun GalleryScreen(modifier: Modifier = Modifier, onEdit: (String) -> Unit = {}, onPickDevice: () -> Unit = {}) {
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
        Text("PHOTO LIBRARY • STUDIO", color = BrandAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Studio gallery", color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("${files.size} photographs • Stored securely on this device", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = BrandAccent) }
        }
        StudioButton("Edit a photo from this phone", onClick = onPickDevice, icon = Icons.Default.AddPhotoAlternate, tone = ButtonTone.Secondary, compact = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) { Text("CAPTURED  ${files.count { it.parentFile?.name != "Edited" }}", color = PrimaryText, modifier = Modifier.padding(10.dp), fontSize = 10.sp) }
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) { Text("EDITED  ${files.count { it.parentFile?.name == "Edited" }}", color = BrandAccent, modifier = Modifier.padding(10.dp), fontSize = 10.sp) }
        }
        if (files.isNotEmpty()) {
            SelectionBar(
                active = selectionMode,
                selectedCount = selectedPaths.size,
                allSelected = selectedPaths.size == files.size,
                startLabel = "Select photos",
                onToggleMode = { selectionMode = !selectionMode; selectedPaths = emptySet() },
                onToggleAll = { selectedPaths = if (selectedPaths.size == files.size) emptySet() else files.map { it.absolutePath }.toSet() },
                onDelete = { confirmDelete = true }
            )
        }
        if (files.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Photo, null, tint = BrandAccent, modifier = Modifier.size(56.dp))
                Text("Your studio is waiting for its first photo", color = PrimaryText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Captured and edited photographs will appear here automatically.", color = Muted, fontSize = 12.sp)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 104.dp), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    val isSelected = file.absolutePath in selectedPaths
                    val thumb = remember(file.absolutePath, file.lastModified()) {
                        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                    Surface(color = if (isSelected) BrandAccent.copy(alpha = .16f) else Panel, shape = RoundedCornerShape(13.dp), border = if (isSelected) BorderStroke(2.dp, BrandAccent) else null,
                        onClick = {
                            if (selectionMode) toggleSelection(file) else selectedFile = file
                        }) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box {
                                if (thumb != null) Image(thumb.asImageBitmap(), "Photo thumbnail", Modifier.fillMaxWidth().height(142.dp), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxWidth().height(142.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Photo, null, tint = Muted) }
                                if (selectionMode) Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = if (isSelected) "Selected" else "Not selected",
                                    tint = if (isSelected) Color(0xFFE4B45D) else Color.White,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(50))
                                )
                            }
                            Text(if (file.parentFile?.name == "Edited") "EDITED PHOTO" else "ORIGINAL CAPTURE", color = BrandAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified())), color = Muted, fontSize = 9.sp)
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
                StudioButton("Delete", tone = ButtonTone.Danger, compact = true, icon = Icons.Default.Delete, onClick = {
                    val targets = files.filter { it.absolutePath in selectedPaths }
                    val deleted = targets.count { it.exists() && it.delete() }
                    selectedPaths = emptySet()
                    selectionMode = false
                    confirmDelete = false
                    refresh++
                    android.widget.Toast.makeText(context, "$deleted photograph(s) deleted", android.widget.Toast.LENGTH_SHORT).show()
                })
            },
            dismissButton = { StudioButton("Cancel", tone = ButtonTone.Secondary, compact = true, onClick = { confirmDelete = false }) }
        )
    }
    if (selectedFile != null) {
        Dialog(onDismissRequest = { selectedFile = null }) {
            Surface(color=Ink,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(selectedFile?.parentFile?.name=="Edited") "EDITED PHOTOGRAPH" else "ORIGINAL CAPTURE",color=BrandAccent,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text(selectedFile?.name ?: "",color=PrimaryText,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                        }
                        IconButton(onClick={selectedFile=null}) { Icon(Icons.Default.Close,"Close",tint=PrimaryText) }
                    }
                    if(selectedBitmap!=null) Image(selectedBitmap.asImageBitmap(),"Full photograph preview",Modifier.fillMaxWidth().heightIn(max=480.dp),contentScale=ContentScale.Fit)
                    Text(selectedFile?.let { SimpleDateFormat("dd MMMM yyyy, HH:mm",Locale.getDefault()).format(Date(it.lastModified())) } ?: "",color=Muted,fontSize=11.sp)
                    ButtonPair {
                        StudioButton("WhatsApp", tone = ButtonTone.WhatsApp, icon = Icons.Default.Send, compact = true, modifier = Modifier.weight(1f), onClick = {
                            selectedFile?.let { file -> runCatching { sharePhoto(context, file, viaWhatsApp = true) }.onSuccess { msg -> msg?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() } } }
                        })
                        StudioButton("Share", icon = Icons.Default.Share, compact = true, modifier = Modifier.weight(1f), onClick = {
                            selectedFile?.let { file ->
                                runCatching { sharePhoto(context, file, viaWhatsApp = false) }
                                    .onFailure { e -> android.widget.Toast.makeText(context, "Unable to share photo: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        })
                    }
                    ButtonPair {
                        StudioButton("Edit", tone = ButtonTone.Secondary, icon = Icons.Default.AutoFixHigh, compact = true, modifier = Modifier.weight(1f), onClick = { selectedFile?.let { onEdit(it.absolutePath) } })
                        StudioButton("Delete", tone = ButtonTone.Secondary, icon = Icons.Default.Delete, compact = true, modifier = Modifier.weight(1f), onClick = {
                            selectedFile?.let { file ->
                                if (file.exists() && file.delete()) {
                                    selectedFile = null
                                    refresh++
                                    android.widget.Toast.makeText(context, "Photograph deleted", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Unable to delete photograph", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

/** Shared "select / select all / delete" bar for lists, laid out so nothing wraps on small phones. */
@Composable
internal fun SelectionBar(
    active: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    startLabel: String,
    onToggleMode: () -> Unit,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit
) {
    if (!active) {
        StudioButton(startLabel, onClick = onToggleMode, icon = Icons.Default.Checklist, tone = ButtonTone.Quiet, compact = true)
        return
    }
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleMode) { Icon(Icons.Default.Close, contentDescription = "Cancel selection", tint = PrimaryText) }
            Text("$selectedCount selected", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            StudioButton(if (allSelected) "None" else "All", onClick = onToggleAll, tone = ButtonTone.Quiet, compact = true)
            StudioButton("Delete", onClick = onDelete, enabled = selectedCount > 0, icon = Icons.Default.Delete, tone = ButtonTone.Danger, compact = true)
        }
    }
}
