package gh.com.bigsammy.passportpro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
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
internal val Ink: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF101522) else Color(0xFFF5F6F8)
internal val Panel: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF1B2433) else Color(0xFFFFFFFF)
private val Gold: Color @Composable get() = BrandAccent
internal val Muted: Color @Composable get() = if (LocalPassportDark.current) Color(0xFF9AA7B8) else Color(0xFF667085)
internal val PrimaryText: Color @Composable get() = if (LocalPassportDark.current) Color(0xFFF7F8FA) else Color(0xFF182230)
private val Good = Color(0xFF21865A)

@Composable
internal fun studioOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PrimaryText,
    unfocusedTextColor = PrimaryText,
    disabledTextColor = Muted,
    errorTextColor = Color(0xFFB42318),
    cursorColor = if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF9A6418),
    focusedBorderColor = if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF9A6418),
    unfocusedBorderColor = if (LocalPassportDark.current) Color(0xFF667085) else Color(0xFF98A2B3),
    disabledBorderColor = if (LocalPassportDark.current) Color(0xFF475467) else Color(0xFFD0D5DD),
    focusedLabelColor = if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F),
    unfocusedLabelColor = Muted,
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel,
    disabledContainerColor = Panel
)

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
    val palette = passportColorScheme(darkMode)
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
                importedPath.onSuccess { editorPhotoPath = it; tab = "Editor" }
                    .onFailure { importError = it.localizedMessage ?: "Unable to import this image." }
            }
        }
    }
    CompositionLocalProvider(LocalPassportDark provides darkMode) {
    MaterialTheme(colorScheme = palette) {
        Scaffold(containerColor = Ink, topBar = {
            if (tab != "Welcome" && tab != "Capture") CenterAlignedTopAppBar(
                title = { Text("PASSPORTPRO STUDIO", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) },
                actions = {
                    IconButton(onClick = { tab = "Guide" }) { Icon(Icons.Default.MenuBook, contentDescription = "Open user guide", tint = Gold) }
                    IconButton(onClick = { tab = "Print" }) { Icon(Icons.Default.Print, contentDescription = "Open Print Designer", tint = Gold) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Panel, titleContentColor = palette.onSurface)
            )
        }, bottomBar = {
            if (tab != "Welcome" && tab != "Guide" && tab != "Capture") NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Capture" to Icons.Default.CameraAlt, "Customers" to Icons.Default.People, "Gallery" to Icons.Default.PhotoLibrary, "Business" to Icons.Default.AccountBalanceWallet).forEach { (label, icon) ->
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold, indicatorColor = Gold.copy(alpha = .14f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            when (tab) {
                "Welcome" -> WelcomeScreen(onContinue = { onboardingPrefs.edit().putBoolean("complete", true).apply(); tab = "Home" })
                "Guide" -> UserGuideScreen(Modifier.padding(padding), onBack = { tab = "Home" })
                "Home" -> Dashboard(Modifier.padding(padding), onCapture = { tab = "Capture" }, onNavigate = { tab = it })
                "Capture" -> CameraStudioScreen(Modifier.padding(padding), onClose = { tab = "Home" })
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
                "Gallery" -> GalleryScreen(Modifier.padding(padding), onEdit = { path -> editorPhotoPath = path; tab = "Editor" }, onPickDevice = { imagePicker.launch("image/*") })
                "Print" -> PrintDesignerScreen(Modifier.padding(padding))
                "Business" -> BusinessManagementScreen(Modifier.padding(padding))
                "Backup" -> BackupRestoreScreen(Modifier.padding(padding))
                "Control Centre" -> StudioControlCentreScreen(Modifier.padding(padding), appearance = appearance, onAppearanceChange = { appearance = it; appearancePrefs.edit().putString("appearance_mode", it).apply() })
                else -> Workspace(tab, Modifier.padding(padding))
            }
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
        StudioTool("Instant WhatsApp", "Capture and send in one step", Icons.Default.Send, "Capture"),
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
            Text("Studio", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Your photography business, beautifully organised.", color = Muted, fontSize = 12.sp)
        }
        Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("READY FOR YOUR NEXT CUSTOMER?", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Capture. Create. Deliver.", color = PrimaryText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Take the photo — PassportPro frames the head, cleans the background and opens WhatsApp or the share menu straight away.", color = Muted, fontSize = 13.sp)
                StudioButton("Open camera", onClick = onCapture, icon = Icons.Default.CameraAlt, modifier = Modifier.fillMaxWidth())
            }
        }
        ProFeatureToggleButtons(onInfo = { onNavigate("Control Centre") })
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
                                color = PrimaryText,
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
        Text(item.first, color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(item.second, color = Muted, fontSize = 15.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            pages.indices.forEach { index ->
                Box(Modifier.size(if (index == page) 22.dp else 7.dp, 7.dp).background(if (index == page) Gold else Muted.copy(alpha = .45f), RoundedCornerShape(8.dp)))
            }
        }
        Spacer(Modifier.height(30.dp))
        StudioButton(
            if (page == pages.lastIndex) "Enter PassportPro Studio" else "Continue",
            onClick = { if (page < pages.lastIndex) page++ else onContinue() },
            icon = if (page == pages.lastIndex) Icons.Default.Check else Icons.Default.ArrowForward,
            modifier = Modifier.fillMaxWidth()
        )
        if (page < pages.lastIndex) StudioButton("Skip introduction", onClick = onContinue, tone = ButtonTone.Quiet, compact = true)
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
                Text("Hints, help and getting started", color = PrimaryText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
        Surface(color = Gold.copy(alpha = .12f), shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("A SIMPLE WORKFLOW", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("Capture or import  →  Edit  →  Print  →  Deliver  →  Record the job  →  Back up", color = PrimaryText, fontSize = 13.sp)
            }
        }
        guides.forEach { guide ->
            Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(guide.third, contentDescription = null, tint = Gold, modifier = Modifier.size(25.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(guide.first, color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
        StudioButton("Back to studio home", onClick = onBack, icon = Icons.Default.Home, modifier = Modifier.fillMaxWidth())
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
        Text("Choose a photograph to edit", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Select an existing image from your phone, Gallery, Downloads or other available storage. You do not need to take a new picture first.",
            color = Muted, fontSize = 14.sp
        )
        StudioButton("Choose photo from device", onClick = onPickImage, icon = Icons.Default.PhotoLibrary, modifier = Modifier.fillMaxWidth())
        if (!error.isNullOrBlank()) Text(error, color = DangerText, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CameraStudioScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var faceCount by remember { mutableIntStateOf(0) }
    var captureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var fineTune by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var shutterFlash by remember { mutableStateOf(false) }
    var autoSend by remember { mutableStateOf(readAutoSend(context)) }
    var selectedSize by remember { mutableStateOf(readSelectedSize(context)) }
    var showSizePicker by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Position the face inside the guide") }
    // The size can also be changed on the result screen, so re-read it when the camera comes back.
    LaunchedEffect(capturedPath) { if (capturedPath == null) selectedSize = readSelectedSize(context) }
    val flashAlpha by animateFloatAsState(if (shutterFlash) 0.85f else 0f, animationSpec = tween(if (shutterFlash) 40 else 260), label = "shutter")
    LaunchedEffect(shutterFlash) { if (shutterFlash) { delay(70); shutterFlash = false } }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            faceDetector.close()
            cameraExecutor.shutdown()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val destination = File(context.cacheDir, "PassportPro_Import_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { input.copyTo(it) } }
                        ?: error("The selected image could not be opened.")
                    destination.absolutePath
                }
            }
            path.onSuccess { capturedPath = it; fineTune = false }
                .onFailure { message = it.localizedMessage ?: "Unable to import this image." }
        }
    }

    // The camera only runs while the viewfinder is visible, so processing after capture gets the full CPU.
    val cameraActive = hasPermission && capturedPath == null
    LaunchedEffect(cameraActive, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                captureUseCase = null
                if (!cameraActive) return@addListener
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    analyseFace(proxy, faceDetector) { count ->
                        faceCount = count
                        message = when (count) {
                            0 -> "Move the face into the guide"
                            1 -> "Looks good — hold still"
                            else -> "Only one person in the frame"
                        }
                    }
                }
                boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                boundCamera?.cameraControl?.enableTorch(flashOn)
                captureUseCase = imageCapture
            } catch (e: Exception) {
                message = "Camera could not start: ${e.localizedMessage ?: "check camera access"}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    BackHandler {
        when {
            fineTune -> fineTune = false
            capturedPath != null -> capturedPath = null
            else -> onClose()
        }
    }

    val currentPath = capturedPath
    if (currentPath != null) {
        if (fineTune) {
            Box(modifier.fillMaxSize().background(Ink)) {
                PhotoEditorScreen(photoPath = currentPath, onRetake = { fineTune = false }, onSaved = { fineTune = false; capturedPath = null })
            }
        } else {
            QuickFinishScreen(
                photoPath = currentPath,
                onRetake = { capturedPath = null },
                onFineTune = { fineTune = true },
                onNextCustomer = { capturedPath = null; message = "Ready for the next customer" },
                modifier = modifier
            )
        }
        return
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                val guide = if (faceCount == 1) Good else Color.White
                val frameRatio = selectedSize.ratio
                val headFraction = selectedSize.headFraction.takeIf { it > 0f } ?: 0.6f
                Canvas(Modifier.fillMaxSize()) {
                    // Frame drawn with the chosen photo shape; the head guide matches the automatic crop,
                    // so what you line up here is what you get after capture.
                    val ratio = frameRatio ?: (size.width / size.height)
                    var frameW = size.width * (if (frameRatio == null) 0.94f else 0.80f)
                    var frameH = frameW / ratio
                    val maxH = size.height * (if (frameRatio == null) 0.94f else 0.78f)
                    if (frameH > maxH) { frameH = maxH; frameW = frameH * ratio }
                    val left = (size.width - frameW) / 2f
                    val top = (size.height - frameH) / 2f
                    val radius = CornerRadius(18.dp.toPx())
                    if (frameRatio != null) {
                        val shade = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addRoundRect(RoundRect(left, top, left + frameW, top + frameH, radius))
                        }
                        drawPath(shade, Color.Black.copy(alpha = .55f))
                        drawRoundRect(guide, Offset(left, top), Size(frameW, frameH), radius, style = Stroke(2.dp.toPx()))
                    }
                    val ovalH = frameH * headFraction
                    val ovalW = (ovalH * 0.74f).coerceAtMost(frameW * 0.9f)
                    val ovalTop = top + frameH * (1f - headFraction) * 0.42f
                    drawOval(
                        guide.copy(alpha = .9f),
                        topLeft = Offset(left + (frameW - ovalW) / 2f, ovalTop),
                        size = Size(ovalW, ovalH),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
            } else {
                Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.NoPhotography, null, tint = Accent, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Camera access needed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Allow the camera to take passport photos. You can also import a photo below.", color = Color(0xFFB9C2D0), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    StudioButton("Allow camera", onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, icon = Icons.Default.CameraAlt)
                }
            }

            // Top controls
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CameraRoundButton(Icons.Default.Close, "Close camera", onClick = onClose)
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { autoSend = autoSend.next(); writeAutoSend(context, autoSend) },
                    color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(50)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = if (autoSend == AutoSend.OFF) Color(0xFFB9C2D0) else Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(autoSend.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                CameraRoundButton(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Toggle light", tint = if (flashOn) Accent else Color.White) {
                    flashOn = !flashOn
                    boundCamera?.cameraControl?.enableTorch(flashOn)
                }
            }
            if (hasPermission) {
                Surface(color = Color.Black.copy(alpha = .6f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = if (faceCount == 1) Good else Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(message, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // Bottom controls
        Box(Modifier.fillMaxWidth().background(Color.Black).padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Surface(onClick = { showSizePicker = true }, color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Crop, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("${selectedSize.label} · ${selectedSize.detail}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = "Change photo size", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CameraRoundButton(Icons.Default.PhotoLibrary, "Import a photo", size = 52) { importer.launch("image/*") }
            Box(
                Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                    .background(if (capturing) Color(0xFF8A93A3) else if (faceCount == 1) Accent else Color.White)
                    .clickable(enabled = hasPermission && !capturing && captureUseCase != null) {
                        val capture = captureUseCase ?: return@clickable
                        capturing = true
                        shutterFlash = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val folder = File(context.filesDir, "PassportPro/Captures").apply { mkdirs() }
                        val file = File(folder, "PassportPro_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                capturing = false
                                fineTune = false
                                capturedPath = file.absolutePath
                            }
                            override fun onError(exception: ImageCaptureException) {
                                capturing = false
                                message = "Capture failed: ${exception.message ?: "please try again"}"
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                if (capturing) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = Color.White)
            }
            CameraRoundButton(Icons.Default.Cameraswitch, "Switch camera", size = 52) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }
        }
    }
    if (showSizePicker) {
        SizePickerSheet(
            selectedKey = selectedSize.key,
            onSelect = { selectedSize = readSelectedSize(context) },
            onDismiss = { showSizePicker = false }
        )
    }
}

@Composable
private fun CameraRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: Int = 44,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size((size * 0.48f).dp)) }
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
        Text(tab, color = PrimaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("PassportPro Studio • Professional camera", color = Gold, fontSize = 12.sp)
    }
}
