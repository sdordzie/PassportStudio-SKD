package gh.com.bigsammy.passportpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101522)
private val Panel = Color(0xFF1B2433)
private val Gold = Color(0xFFE4B45D)
private val Muted = Color(0xFF9AA7B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PassportProApp() }
    }
}

@Composable
private fun PassportProApp() {
    var tab by remember { mutableStateOf("Home") }
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Capture" to Icons.Default.CameraAlt, "Customers" to Icons.Default.People, "Gallery" to Icons.Default.PhotoLibrary, "More" to Icons.Default.Menu).forEach { (label, icon) ->
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold, indicatorColor = Gold.copy(alpha = .14f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            if (tab == "Home") Dashboard(Modifier.padding(padding)) { tab = "Capture" } else Workspace(tab, Modifier.padding(padding))
        }
    }
}

private data class StudioTool(val title: String, val detail: String, val icon: ImageVector)

@Composable
private fun Dashboard(modifier: Modifier, onCapture: () -> Unit) {
    val tools = listOf(
        StudioTool("New Session", "Start a customer photo job", Icons.Default.AddAPhoto),
        StudioTool("Photo Editor", "AI and manual editing", Icons.Default.AutoFixHigh),
        StudioTool("WhatsApp Delivery", "Individual and group sharing", Icons.Default.Send),
        StudioTool("Print Designer", "Passport sheets and PDF", Icons.Default.Print),
        StudioTool("Customers", "Profiles and photo history", Icons.Default.People),
        StudioTool("Business Reports", "Sales and job summaries", Icons.Default.BarChart)
    )
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("PASSPORTPRO", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Studio", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Your photography business, beautifully organised.", color = Muted, fontSize = 12.sp)
        }
        Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("READY FOR YOUR NEXT CUSTOMER?", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Capture. Create. Deliver.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Capture professional ID photos and prepare them for editing, printing and WhatsApp delivery.", color = Muted, fontSize = 13.sp)
                Button(onClick = onCapture, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("OPEN CAMERA STUDIO", fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("STUDIO WORKSPACE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tools) { tool ->
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(tool.icon, null, tint = Gold, modifier = Modifier.size(25.dp))
                        Text(tool.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(tool.detail, color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Text("PHASE 1 • FOUNDATION", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Workspace(tab: String, modifier: Modifier) {
    val detail = when (tab) {
        "Capture" -> "Live camera capture, face positioning and photo review will be delivered in Phase 2."
        "Customers" -> "Customer profiles, contacts and job history will be delivered in the business management phase."
        "Gallery" -> "Captured and edited photographs will appear here when the photo workflow is implemented."
        else -> "Studio settings, printing, reports and backup tools will be added in later phases."
    }
    Column(modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoCamera, null, tint = Gold, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(tab, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("PassportPro Studio • Phase 1 foundation", color = Gold, fontSize = 12.sp)
    }
}
