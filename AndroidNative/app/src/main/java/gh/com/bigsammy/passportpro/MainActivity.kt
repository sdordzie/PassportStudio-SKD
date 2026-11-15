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

private val Ink = Color(0xFF101522)
private val Panel = Color(0xFF1B2433)
private val Gold = Color(0xFFE4B45D)
private val Muted = Color(0xFF9AA7B8)
private val Good = Color(0xFF64D6A0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PassportProApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportProApp() {
    var tab by remember { mutableStateOf("Home") }
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Ink, topBar = {
            CenterAlignedTopAppBar(
                title = { Text("PASSPORTPRO STUDIO", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) },
                actions = {
                    IconButton(onClick = { tab = "Print" }) { Icon(Icons.Default.Print, contentDescription = "Open Print Designer", tint = Gold) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink)
            )
        }, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Capture" to Icons.Default.CameraAlt, "Customers" to Icons.Default.People, "Gallery" to Icons.Default.PhotoLibrary, "Business" to Icons.Default.AccountBalanceWallet).forEach { (label, icon) ->
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold, indicatorColor = Gold.copy(alpha = .14f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            when (tab) {
                "Home" -> Dashboard(Modifier.padding(padding), onCapture = { tab = "Capture" }, onNavigate = { tab = it })
                "Capture" -> CameraStudioScreen(Modifier.padding(padding))
                "Customers" -> CustomersScreen(Modifier.padding(padding))
                "Gallery" -> GalleryScreen(Modifier.padding(padding))
                "Print" -> PrintDesignerScreen(Modifier.padding(padding))
                "Business" -> BusinessManagementScreen(Modifier.padding(padding))
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
        StudioTool("Photo Editor", "AI and manual editing", Icons.Default.AutoFixHigh, "Capture"),
        StudioTool("WhatsApp Delivery", "Individual and group sharing", Icons.Default.Send, "Gallery"),
        StudioTool("Print Designer", "Passport sheets and PDF", Icons.Default.Print, "Print"),
        StudioTool("Customers", "Profiles and photo history", Icons.Default.People, "Customers"),
        StudioTool("Business Reports", "Sales and job summaries", Icons.Default.BarChart, "Business")
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
                Surface(onClick = { onNavigate(tool.destination) }, color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(tool.icon, null, tint = Gold, modifier = Modifier.size(25.dp))
                        Text(tool.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(tool.detail, color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Text("PHASE 2 • CAMERA STUDIO", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                Text("PHASE 2", color = Gold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        "Customers" -> "Customer profiles, contacts and job history will be delivered in the business management phase."
        "Gallery" -> "Captured photographs are saved in the app's private PassportPro/Captures folder. A browsable gallery is planned for a later phase."
        else -> "Studio settings, editing, printing, reports and backup tools will be added in later phases."
    }
    Column(modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoCamera, null, tint = Gold, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(tab, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("PassportPro Studio • Phase 2 camera", color = Gold, fontSize = 12.sp)
    }
}
