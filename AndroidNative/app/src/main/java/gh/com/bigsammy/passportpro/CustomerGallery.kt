package gh.com.bigsammy.passportpro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Photo
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
        if(customers.isEmpty()) {
            Surface(color=Color(0xFF1B2433),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Text("No customers yet. Add your first customer above.",color=Color(0xFF9AA7B8),modifier=Modifier.padding(18.dp),fontSize=13.sp)
            }
        } else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f)) {
            items(customers,key={it.id}) { customer ->
                Surface(color=Color(0xFF1B2433),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(customer.name,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=16.sp)
                        if(customer.phone.isNotBlank()) Text(customer.phone,color=Color(0xFFE4B45D),fontSize=13.sp)
                        if(customer.notes.isNotBlank()) Text(customer.notes,color=Color(0xFF9AA7B8),fontSize=12.sp)
                        Text(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(customer.created)),color=Color(0xFF718096),fontSize=10.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val files = remember(refresh) {
        val roots = listOf(File(context.filesDir,"PassportPro/Captures"),File(context.getExternalFilesDir(null),"PassportPro/Edited"))
        roots.flatMap { root -> if(root.exists()) root.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("jpg","jpeg","png") }.toList() else emptyList() }
            .sortedByDescending { it.lastModified() }
    }
    Column(modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("PHOTO LIBRARY • PHASE 4",color=Color(0xFFE4B45D),fontSize=11.sp,fontWeight=FontWeight.Bold)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Studio gallery",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            TextButton(onClick={refresh++}) { Text("Refresh",color=Color(0xFFE4B45D)) }
        }
        Text("${files.size} photographs stored on this device",color=Color(0xFF9AA7B8),fontSize=12.sp)
        if(files.isEmpty()) {
            Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Icon(Icons.Default.Photo,null,tint=Color(0xFFE4B45D),modifier=Modifier.size(48.dp))
                Text("Your gallery is empty",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                Text("Captured and edited photos will appear here.",color=Color(0xFF9AA7B8),fontSize=12.sp)
            }
        } else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(files,key={it.absolutePath}) { file ->
                val bitmap = remember(file.absolutePath,file.lastModified()) { BitmapFactory.decodeFile(file.absolutePath) }
                Surface(color=Color(0xFF1B2433),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(bitmap!=null) Image(bitmap.asImageBitmap(),contentDescription="Saved passport photograph",modifier=Modifier.size(width=78.dp,height=92.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(file.name,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(file.parentFile?.name=="Edited") "Edited photo" else "Original capture",color=Color(0xFF9AA7B8),fontSize=11.sp)
                            Text(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date(file.lastModified())),color=Color(0xFF718096),fontSize=10.sp)
                            TextButton(onClick={
                                try {
                                    val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                                    val intent=Intent(Intent.ACTION_SEND).apply { type="image/jpeg"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                    context.startActivity(Intent.createChooser(intent,"Share passport photo"))
                                } catch(e:Exception) { android.widget.Toast.makeText(context,"Unable to share this photo: ${e.localizedMessage}",android.widget.Toast.LENGTH_LONG).show() }
                            }) { Icon(Icons.Default.Share,null,tint=Color(0xFFE4B45D)); Spacer(Modifier.width(5.dp)); Text("Share / WhatsApp",color=Color(0xFFE4B45D),fontSize=11.sp) }
                        }
                    }
                }
            }
        }
    }
}
