package gh.com.bigsammy.passportpro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalPassportDark = compositionLocalOf { true }
private val Ink: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF101522) else Color(0xFFF5F6F8)
private val Panel: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF1B2433) else Color(0xFFFFFFFF)
private val Gold = Color(0xFFB77B24)
private val Muted: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF9AA7B8) else Color(0xFF667085)
private val Good = Color(0xFF21865A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PassportProApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportProApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onboardingPrefs = remember { context.getSharedPreferences("passportpro_onboarding", android.content.Context.MODE_PRIVATE) }
    val appearancePrefs = remember { context.getSharedPreferences("passportpro_studio_settings", android.content.Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(appearancePrefs.getString("appearance_mode", "System default") ?: "System default") }
    val systemDark = isSystemInDarkTheme()
    val darkMode = when (appearance) { "Light" -> false; "Dark" -> true; else -> systemDark }
    val palette = if (darkMode) darkColorScheme(primary = Color(0xFFE4B45D), background = Color(0xFF101522), surface = Color(0xFF1B2433), onBackground = Color.White, onSurface = Color.White) else lightColorScheme(primary = Color(0xFF9A6418), background = Color(0xFFF5F6F8), surface = Color.White, onBackground = Color(0xFF182230), onSurface = Color(0xFF182230), secondary = Color(0xFF765016))
    var tab by remember { mutableStateOf(if (onboardingPrefs.getBoolean("complete", false)) "Home" else "Welcome") }
    var editorPhotoPath by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importError = null
            scope.launch {
                val importedPath = withContext(Dispatchers.IO) {
                    runCatching {
                        val destination = File(context.cacheDir, "PassportPro_Import_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("The selected image could not be opened.")
                        destination.absolutePath
                    }
                }
                importedPath.onSuccess { editorPhotoPath = it }
                    .onFailure { importError = it.localizedMessage ?: "Unable to import this image." }
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Ink, topBar = {
            if (tab != "Welcome") CenterAlignedTopAppBar(
                title = { Text("PASSPORTPRO STUDIO", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) },
                actions = {
                    IconButton(onClick = { tab = "Guide" }) { Icon(Icons.Default.MenuBook, contentDescription = "Open user guide", tint = Gold) }
                    IconButton(onClick = { tab = "Print" }) { Icon(Icons.Default.Print, contentDescription = "Open Print Designer", tint = Gold) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Panel, titleContentColor = palette.onSurface)
            )
        }, bottomBar = {
            if (tab != "Welcome" && tab != "Guide") NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Capture" to Icons.Default.CameraAlt, "Customers" to Icons.Default.People, "Gallery" to Icons.Default.PhotoLibrary, "Business" to Icons.Default.AccountBalanceWallet).forEach { (label, icon) ->
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold, indicatorColor = Gold.copy(alpha = .14f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            when (tab) {
                "Welcome" -> WelcomeScreen(onContinue = { onboardingPrefs.edit().putBoolean("complete", true).apply(); tab = "Home" })
                "Guide" -> UserGuideScreen(Modifier.padding(padding), onBack = { tab = "Home" })
                "Home" -> Dashboard(Modifier.padding(padding), onCapture = { tab = "Capture" }, onNavigate = { tab = it })
                "Capture" -> CameraStudioScreen(Modifier.padding(padding))
                "Editor" -> {
                    val selectedPath = editorPhotoPath
                    if (selectedPath != null) {
                        Box(Modifier.padding(padding).fillMaxSize()) {
                            PhotoEditorScreen(
                                photoPath = selectedPath,
                                onRetake = { editorPhotoPath = null },
                                onSaved = { editorPhotoPath = null; tab = "Home" }
                            )
                        }
                    } else {
                        PhotoEditorEntryScreen(
                            modifier = Modifier.padding(padding),
                            error = importError,
                            onPickImage = { imagePicker.launch("image/*") },
                            onBack = { tab = "Home" }
                        )
                    }
                }
                "Customers" -> CustomersScreen(Modifier.padding(padding))
                "Gallery" -> GalleryScreen(Modifier.padding(padding))
                "Print" -> PrintDesignerScreen(Modifier.padding(padding))
                "Business" -> BusinessManagementScreen(Modifier.padding(padding))
                "Backup" -> BackupRestoreScreen(Modifier.padding(padding))
                "Control Centre" -> StudioControlCentreScreen(Modifier.padding(padding), appearance = appearance, onAppearanceChange = { appearance = it; appearancePrefs.edit().putString("appearance_mode", it).apply() })
                else -> Workspace(tab, Modifier.padding(padding))
            }
        }
    }
}

private data class StudioTool(val title: String, val detail: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val destination: String)

@Composable
private fun Dashboard(modifier: Modifier, onCapture: () -> Unit, onNavigate: (String) -> Unit) {
    val tools = listOf(
        StudioTool("New Session", "Start a customer photo job", Icons.Default.AddAPhoto, "Capture"),
        StudioTool("Photo Editor", "Edit a photo from your device or studio", Icons.Default.AutoFixHigh, "Editor"),
        StudioTool("WhatsApp Delivery", "Individual and group sharing", Icons.Default.Send, "Gallery"),
        StudioTool("Print Designer", "Passport sheets and PDF", Icons.Default.Print, "Print"),
        StudioTool("Customers", "Profiles and photo history", Icons.Default.People, "Customers"),
        StudioTool("Business Reports", "Sales and job summaries", Icons.Default.BarChart, "Business"),
        StudioTool("Backup & Restore", "Protect photos and business records", Icons.Default.Security, "Backup"),
        StudioTool("Studio Control Centre", "Branding, print, privacy and diagnostics", Icons.Default.Settings, "Control Centre"),
        StudioTool("Hints & User Guide", "Learn the studio workflow step by step", Icons.Default.MenuBook, "Guide")
    )
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
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
        Surface(onClick = { onNavigate("Guide") }, color = Gold.copy(alpha = .12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Gold)
                Column(Modifier.weight(1f)) {
                    Text("NEW TO PASSPORTPRO?", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Open the quick guide, helpful hints and workflow tips.", color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Gold)
            }
        }
        Text("STUDIO WORKSPACE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        // Full-height workspace tiles inside the dashboard scroll area.
        // Fixed minimum card height prevents the bottom navigation from clipping tile content.
        tools.chunked(2).forEach { toolRow ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                toolRow.forEach { tool ->
                    Surface(
                        onClick = { onNavigate(tool.destination) },
                        color = Panel,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 132.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(tool.icon, contentDescription = null, tint = Gold, modifier = Modifier.size(27.dp))
                            Text(
                                tool.title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )
                            Text(
                                tool.detail,
                                color = Muted,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
                if (toolRow.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("PROFESSIONAL CAMERA STUDIO", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}


@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf(
        Triple("Welcome to PassportPro Studio", "Your complete offline-ready workspace for passport photography, photo editing, printing and studio business management.", Icons.Default.WavingHand),
        Triple("Capture or import", "Take a new photograph with live face guidance, or open Photo Editor to choose an existing image from your phone.", Icons.Default.PhotoCamera),
        Triple("Edit with precision", "Replace backgrounds, refine image quality, crop to the required dimensions, correct perspective and save your finished photo.", Icons.Default.AutoFixHigh),
        Triple("Print, deliver and manage", "Arrange photos on print sheets, export PDFs, share images, manage customers and jobs, record payments, track expenses and back up your studio.", Icons.Default.Print)
    )
    val item = pages[page]
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 26.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BIGSAMMY GRAPHICS", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(28.dp))
        Surface(color = Panel, shape = RoundedCornerShape(30.dp), modifier = Modifier.size(104.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(item.third, contentDescription = null, tint = Gold, modifier = Modifier.size(54.dp)) }
        }
        Spacer(Modifier.height(30.dp))
        Text(item.first, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(item.second, color = Muted, fontSize = 15.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            pages.indices.forEach { index ->
                Box(Modifier.size(if (index == page) 22.dp else 7.dp, 7.dp).background(if (index == page) Gold else Muted.copy(alpha = .45f), RoundedCornerShape(8.dp)))
            }
        }
        Spacer(Modifier.height(30.dp))
        Button(
            onClick = { if (page < pages.lastIndex) page++ else onContinue() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            shape = RoundedCornerShape(15.dp)
        ) { Text(if (page == pages.lastIndex) "ENTER PASSPORTPRO STUDIO" else "CONTINUE", fontWeight = FontWeight.Bold) }
        TextButton(onClick = onContinue) { Text(if (page == pages.lastIndex) "Get started" else "Skip introduction", color = Muted) }
        Text("Professional photo studio • Your files stay on your device", color = Muted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun UserGuideScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val guides = listOf(
        Triple("1. Start a photo session", "Open New Session. Allow camera access when asked, position one face inside the oval guide and capture. Use the camera switch and torch controls when needed.", Icons.Default.CameraAlt),
        Triple("2. Edit an existing photo", "Choose Photo Editor from Home, tap Choose Photo from Device and select an image from Gallery, Downloads or another storage location. You do not need to capture a new photo.", Icons.Default.PhotoLibrary),
        Triple("3. Refine the photograph", "Use crop presets or custom dimensions, adjust brightness and colour, replace the background, refine hair and edges, rotate or mirror, and use perspective correction when required. Save the finished image.", Icons.Default.AutoFixHigh),
        Triple("4. Prepare a print sheet", "Open Print Designer, select your photographs, choose a passport or print layout, set copies, margins, spacing and DPI, then export and share the PDF. Check your printer's paper and scaling settings before printing.", Icons.Default.Print),
        Triple("5. Deliver and manage jobs", "Use Gallery to preview and share photos. Register customers and jobs, update job progress, record payments, issue receipts and review business reports and the cashbook.", Icons.Default.People),
        Triple("6. Protect your studio data", "Open Backup & Restore to create a full or selective backup and restore it when needed. Keep a copy of important backups somewhere separate from your phone.", Icons.Default.Security)
    )
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Gold) }
            Column {
                Text("PASSPORTPRO QUICK GUIDE", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Hints, help and getting started", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
        Surface(color = Gold.copy(alpha = .12f), shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("A SIMPLE WORKFLOW", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("Capture or import  →  Edit  →  Print  →  Deliver  →  Record the job  →  Back up", color = Color.White, fontSize = 13.sp)
            }
        }
        guides.forEach { guide ->
            Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(guide.third, contentDescription = null, tint = Gold, modifier = Modifier.size(25.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(guide.first, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(guide.second, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("HELPFUL STUDIO HINTS", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("• Use good, even lighting and keep the camera at face level.\n• Keep the background plain and avoid harsh shadows.\n• Check the crop dimensions and print preview before exporting.\n• Use 300 DPI for a good balance of print quality and file size; use 600 DPI when higher detail is needed and supported.\n• Confirm the correct printer, paper size and scaling before printing.\n• Back up regularly, especially before changing phones or restoring records.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("BACK TO STUDIO HOME", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PhotoEditorEntryScreen(
    modifier: Modifier = Modifier,
    error: String?,
    onPickImage: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Gold) }
            Text("PHOTO EDITOR", color = Gold, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Gold, modifier = Modifier.size(64.dp))
        Text("Choose a photograph to edit", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Select an existing image from your phone, Gallery, Downloads or other available storage. You do not need to take a new picture first.",
            color = Muted, fontSize = 14.sp
        )
        Button(
            onClick = onPickImage,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("CHOOSE PHOTO FROM DEVICE", fontWeight = FontWeight.Bold)
        }
        if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CameraStudioScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var faceCount by remember { mutableIntStateOf(0) }
    var captureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var savedPhoto by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Position the face inside the guide") }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            faceDetector.close()
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(hasPermission, lensFacing) {
        if (hasPermission) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { proxy -> analyseFace(proxy, faceDetector) { count ->
                        faceCount = count
                        message = when {
                            count == 0 -> "No face detected — move into the frame"
                            count == 1 -> "One face detected — hold still"
                            else -> "$count faces detected — keep only one person in frame"
                        }
                    } }
                    provider.unbindAll()
                    boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                    captureUseCase = imageCapture
                } catch (e: Exception) {
                    message = "Camera could not start: ${e.localizedMessage ?: "check camera access"}"
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    val photoBitmap = remember(savedPhoto) {
        savedPhoto?.let { BitmapFactory.decodeFile(it) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CAMERA STUDIO", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Passport capture", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Text("LIVE CAPTURE", color = Gold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (savedPhoto != null && photoBitmap != null) {
            PhotoEditorScreen(photoPath = savedPhoto!!, onRetake = { savedPhoto = null }, onSaved = { savedPhoto = null; message = "Edited photograph saved successfully" })
        } else if (!hasPermission) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.NoPhotography, null, tint = Gold, modifier = Modifier.size(52.dp))
                Text("Camera permission required", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Allow camera access to use the live passport studio.", color = Muted, fontSize = 13.sp)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Grant camera access") }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val ovalWidth = size.width * 0.62f
                    val ovalHeight = size.height * 0.56f
                    val left = (size.width - ovalWidth) / 2f
                    val top = (size.height - ovalHeight) / 2f
                    drawOval(color = if (faceCount == 1) Good else Gold, topLeft = Offset(left, top), size = Size(ovalWidth, ovalHeight), style = Stroke(width = 3.dp.toPx()))
                    drawLine(Gold.copy(alpha = .55f), Offset(size.width * .15f, size.height * .78f), Offset(size.width * .85f, size.height * .78f), strokeWidth = 1.dp.toPx())
                }
                Surface(color = Ink.copy(alpha = .78f), shape = RoundedCornerShape(20.dp), modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) {
                    Text(message, color = if (faceCount == 1) Good else Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 12.sp)
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = {
                        flashOn = !flashOn
                        captureUseCase?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        boundCamera?.cameraControl?.enableTorch(flashOn)
                    }) { Icon(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, contentDescription = "Toggle flash") }
                    FilledTonalIconButton(onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
                    }
                    Button(
                        onClick = {
                            val capture = captureUseCase ?: return@Button
                            val folder = File(context.filesDir, "PassportPro/Captures").apply { mkdirs() }
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val file = File(folder, "PassportPro_$timestamp.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    savedPhoto = file.absolutePath
                                    message = "Photograph captured successfully"
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    message = "Capture failed: ${exception.message ?: "please try again"}"
                                }
                            })
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                        modifier = Modifier.size(66.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.Default.CameraAlt, contentDescription = "Capture photo", modifier = Modifier.size(30.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Face, tint = if (faceCount == 1) Good else Gold, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (faceCount == 1) "Face aligned in frame" else "Face guidance active", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("Saved locally • Offline", color = Good, fontSize = 11.sp)
            }
        }
    }
}

private fun analyseFace(proxy: ImageProxy, detector: FaceDetector, onResult: (Int) -> Unit) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    detector.process(input)
        .addOnSuccessListener { faces -> onResult(faces.size) }
        .addOnFailureListener { onResult(0) }
        .addOnCompleteListener { proxy.close() }
}

@Composable
private fun Workspace(tab: String, modifier: Modifier) {
    val detail = when (tab) {
        "Customers" -> "Manage customer profiles, contact details and photo job history."
        "Gallery" -> "Browse, review, select and share photographs saved in your studio."
        else -> "Manage your photography workflow, editing, printing, business records and studio settings."
    }
    Column(modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoCamera, null, tint = Gold, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(tab, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("PassportPro Studio • Professional camera", color = Gold, fontSize = 12.sp)
    }
}
