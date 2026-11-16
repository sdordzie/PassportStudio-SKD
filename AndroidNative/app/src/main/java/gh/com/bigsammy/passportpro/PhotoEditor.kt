package gh.com.bigsammy.passportpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as Pixel
import android.graphics.Bitmap.CompressFormat
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EditorSnapshot(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val crop: String,
    val rotation: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val perspectiveEnabled: Boolean,
    val perspectiveCorners: List<Offset>
)

private fun fitPreview(source: Bitmap, maxEdge: Int = 1100): Bitmap {
    val largest = maxOf(source.width, source.height)
    if (largest <= maxEdge) return source
    val scale = maxEdge.toFloat() / largest
    return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1), true)
}

private fun cropForPreset(source: Bitmap, preset: String, customRatio: Float? = null): Bitmap {
    val ratio = customRatio ?: when (preset) {
        "35 × 45 mm" -> 35f / 45f
        "Square" -> 1f
        "4 × 6" -> 2f / 3f
        "Portrait" -> minOf(source.width.toFloat() / source.height, 0.75f)
        "Landscape" -> maxOf(source.width.toFloat() / source.height, 1.3333333f)
        else -> source.width.toFloat() / source.height
    }
    val sourceRatio = source.width.toFloat() / source.height
    val w: Int
    val h: Int
    if (sourceRatio > ratio) {
        h = source.height
        w = (h * ratio).toInt().coerceIn(1, source.width)
    } else {
        w = source.width
        h = (w / ratio).toInt().coerceIn(1, source.height)
    }
    return Bitmap.createBitmap(source, (source.width - w) / 2, (source.height - h) / 2, w, h)
}

private fun transformPhoto(source: Bitmap, rotation: Int, flipHorizontal: Boolean, flipVertical: Boolean): Bitmap {
    val normalized = ((rotation % 360) + 360) % 360
    if (normalized == 0 && !flipHorizontal && !flipVertical) return source
    val matrix = Matrix().apply {
        postRotate(normalized.toFloat())
        postScale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f)
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun adjustPhoto(source: Bitmap, brightness: Float, contrast: Float, saturation: Float): Bitmap {
    if (brightness == 0f && contrast == 1f && saturation == 1f) return source
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        fun channel(v: Int) = (contrast * (v - 128) + 128 + brightness * 255).toInt().coerceIn(0, 255)
        val r = channel(Pixel.red(p))
        val g = channel(Pixel.green(p))
        val b = channel(Pixel.blue(p))
        val grey = .299f * r + .587f * g + .114f * b
        pixels[i] = Pixel.rgb(
            (grey + (r - grey) * saturation).toInt().coerceIn(0, 255),
            (grey + (g - grey) * saturation).toInt().coerceIn(0, 255),
            (grey + (b - grey) * saturation).toInt().coerceIn(0, 255)
        )
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

private fun decodeUprightPhoto(path: String): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    return try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    } catch (_: Exception) { bitmap }
}


private fun isValidPerspectiveQuad(points: List<Offset>): Boolean {
    if (points.size != 4) return false
    var sign = 0f
    for (i in 0..3) {
        val a = points[i]
        val b = points[(i + 1) % 4]
        val c = points[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (kotlin.math.abs(cross) < 0.0005f) return false
        if (sign == 0f) sign = kotlin.math.sign(cross)
        else if (cross * sign <= 0f) return false
    }
    var twiceArea = 0f
    for (i in 0..3) {
        val a = points[i]
        val b = points[(i + 1) % 4]
        twiceArea += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(twiceArea) > 0.02f
}

private fun perspectiveCorrect(source: Bitmap, corners: List<Offset>): Bitmap {
    if (corners.size != 4) return source
    val src = FloatArray(8)
    val dst = floatArrayOf(0f, 0f, source.width.toFloat(), 0f, source.width.toFloat(), source.height.toFloat(), 0f, source.height.toFloat())
    corners.forEachIndexed { index, point ->
        src[index * 2] = point.x.coerceIn(0f, 1f) * source.width
        src[index * 2 + 1] = point.y.coerceIn(0f, 1f) * source.height
    }
    val matrix = Matrix()
    if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return source
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(output).drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
    return output
}

private fun refineSegmentationMask(raw: FloatArray, width: Int, height: Int, radius: Int, edgeBias: Float): FloatArray {
    if (width <= 0 || height <= 0 || raw.size != width * height) return raw
    val horizontal = FloatArray(raw.size)
    val weights = when (radius.coerceIn(0, 3)) {
        0 -> floatArrayOf(1f)
        1 -> floatArrayOf(0.25f, 0.5f, 0.25f)
        2 -> floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
        else -> floatArrayOf(0.03125f, 0.109375f, 0.21875f, 0.28125f, 0.21875f, 0.109375f, 0.03125f)
    }
    val reach = weights.size / 2
    for (y in 0 until height) for (x in 0 until width) {
        var sum = 0f; var total = 0f
        for (k in -reach..reach) {
            val xx = (x + k).coerceIn(0, width - 1)
            val weight = weights[k + reach]
            sum += raw[y * width + xx] * weight; total += weight
        }
        horizontal[y * width + x] = sum / total
    }
    val smooth = FloatArray(raw.size)
    for (y in 0 until height) for (x in 0 until width) {
        var sum = 0f; var total = 0f
        for (k in -reach..reach) {
            val yy = (y + k).coerceIn(0, height - 1)
            val weight = weights[k + reach]
            sum += horizontal[yy * width + x] * weight; total += weight
        }
        val original = raw[y * width + x]
        val blurred = sum / total
        // Preserve high-frequency mask detail (hair strands and narrow edges) while
        // allowing flat regions to benefit from smoothing.
        val localDetail = (kotlin.math.abs(original - blurred) * 5f).coerceIn(0f, 1f)
        val edgeConfidence = (kotlin.math.abs(original - 0.5f) * 2f).coerceIn(0f, 1f)
        val preserve = (edgeBias * (0.35f + 0.65f * edgeConfidence) * localDetail).coerceIn(0f, 1f)
        smooth[y * width + x] = (blurred * (1f - preserve) + original * preserve).coerceIn(0f, 1f)
    }
    return smooth
}

private fun sampleMaskBilinear(mask: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
    val px = x.coerceIn(0f, (width - 1).toFloat())
    val py = y.coerceIn(0f, (height - 1).toFloat())
    val x0 = px.toInt(); val y0 = py.toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1); val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = px - x0; val fy = py - y0
    val a = mask[y0 * width + x0] * (1f - fx) + mask[y0 * width + x1] * fx
    val b = mask[y1 * width + x0] * (1f - fx) + mask[y1 * width + x1] * fx
    return (a * (1f - fy) + b * fy).coerceIn(0f, 1f)
}

private fun smoothAlpha(value: Float, softness: Float): Float {
    val low = (0.5f - softness.coerceIn(0.02f, 0.48f)).coerceIn(0f, 0.49f)
    val high = 1f - low
    val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun PhotoEditorScreen(photoPath: String, onRetake: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(photoPath) {
        decodeUprightPhoto(photoPath)?.copy(Bitmap.Config.ARGB_8888, false)?.let { fitPreview(it, 2200) }
    }
    var editedBase by remember(photoPath) { mutableStateOf(original) }
    var preview by remember(photoPath) { mutableStateOf(original?.let { fitPreview(it, 1000) }) }
    var perspectiveGuide by remember(photoPath) { mutableStateOf(original?.let { fitPreview(it, 1000) }) }
    var background by remember { mutableIntStateOf(Pixel.WHITE) }
    var maskSoftness by remember { mutableFloatStateOf(0.18f) }
    var maskDetail by remember { mutableFloatStateOf(0.72f) }
    var maskRadius by remember { mutableIntStateOf(2) }
    val editorDefaults = remember {
        context.getSharedPreferences("passportpro_editor_defaults", android.content.Context.MODE_PRIVATE)
    }
    val hasSavedCustomDefault = remember {
        editorDefaults.getBoolean("use_custom_dimensions", false)
    }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var crop by remember { mutableStateOf(if (hasSavedCustomDefault) "Custom" else "Original") }
    var perspectiveCorners by remember { mutableStateOf(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))) }
    var perspectiveEnabled by remember { mutableStateOf(false) }
    var rotation by remember(photoPath) { mutableIntStateOf(0) }
    var flipHorizontal by remember(photoPath) { mutableStateOf(false) }
    var flipVertical by remember(photoPath) { mutableStateOf(false) }
    var customWidth by remember { mutableStateOf(editorDefaults.getString("width", "600") ?: "600") }
    var customHeight by remember { mutableStateOf(editorDefaults.getString("height", "800") ?: "800") }
    var customUnit by remember { mutableStateOf(editorDefaults.getString("unit", "px") ?: "px") }
    var outputDpi by remember { mutableIntStateOf(editorDefaults.getInt("dpi", 300)) }
    var customDefaultSaved by remember { mutableStateOf(hasSavedCustomDefault) }
    var busy by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(listOf<EditorSnapshot>()) }
    var redoStack by remember { mutableStateOf(listOf<EditorSnapshot>()) }
    var status by remember { mutableStateOf("Your photograph is ready. Changes appear in the preview.") }

    fun currentSnapshot() = EditorSnapshot(brightness, contrast, saturation, crop, rotation, flipHorizontal, flipVertical, perspectiveEnabled, perspectiveCorners)
    fun recordUndo() {
        val state = currentSnapshot()
        if (undoStack.lastOrNull() != state) undoStack = (undoStack + state).takeLast(40)
        redoStack = emptyList()
    }
    fun restoreSnapshot(state: EditorSnapshot) {
        brightness = state.brightness
        contrast = state.contrast
        saturation = state.saturation
        crop = state.crop
        rotation = state.rotation
        flipHorizontal = state.flipHorizontal
        flipVertical = state.flipVertical
        perspectiveEnabled = state.perspectiveEnabled
        perspectiveCorners = state.perspectiveCorners
    }

    val customRatio = remember(customWidth, customHeight) {
        val w = customWidth.toFloatOrNull()?.takeIf { it > 0f }
        val h = customHeight.toFloatOrNull()?.takeIf { it > 0f }
        if (w == null || h == null) null else w / h
    }
    // Draw perspective handles over the uncorrected oriented source so their positions stay stable.
    LaunchedEffect(editedBase, rotation, flipHorizontal, flipVertical) {
        val base = editedBase
        if (base != null) {
            perspectiveGuide = withContext(Dispatchers.Default) {
                fitPreview(transformPhoto(base, rotation, flipHorizontal, flipVertical), 1000)
            }
        }
    }

    // Keep the preview visible and responsive; all pixel work runs away from the UI thread.
    LaunchedEffect(editedBase, brightness, contrast, saturation, crop, customRatio, perspectiveEnabled, perspectiveCorners, rotation, flipHorizontal, flipVertical) {
        val base = editedBase
        if (base != null) {
            preview = withContext(Dispatchers.Default) {
                val oriented = transformPhoto(base, rotation, flipHorizontal, flipVertical)
                val small = fitPreview(oriented, 1000)
                val perspective = if (perspectiveEnabled) perspectiveCorrect(small, perspectiveCorners) else small
                val cropped = cropForPreset(perspective, crop, if (crop == "Custom") customRatio else null)
                adjustPhoto(cropped, brightness, contrast, saturation)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PHOTO EDITOR", color=Color(0xFFE4B45D), fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.2.sp)
                Text("Live editing preview", color=PrimaryText, fontSize=21.sp, fontWeight=FontWeight.Bold)
            }
            Surface(color=Color(0xFF1B2433), shape=RoundedCornerShape(10.dp)) {
                Text("ON DEVICE", color=Color(0xFF64D6A0), modifier=Modifier.padding(horizontal=10.dp, vertical=7.dp), fontSize=9.sp, fontWeight=FontWeight.Bold)
            }
        }

        // Dedicated preview gets guaranteed screen space; controls scroll independently below.
        Box(
            Modifier.weight(1.15f).fillMaxWidth().heightIn(min=170.dp)
                .background(Color(0xFF090D14), RoundedCornerShape(14.dp)),
            contentAlignment=Alignment.Center
        ) {
            val displayPhoto = if (showOriginal) original else if (perspectiveEnabled) perspectiveGuide ?: preview else preview
            if (displayPhoto != null) {
                Image(displayPhoto.asImageBitmap(), if (showOriginal) "Original photograph for before and after comparison" else if (perspectiveEnabled) "Uncorrected photograph for perspective adjustment" else "Live passport photo editing preview",
                    Modifier.fillMaxSize().padding(5.dp), contentScale=ContentScale.Fit)
                if (perspectiveEnabled && !showOriginal) {
                    Canvas(Modifier.fillMaxSize().pointerInput(displayPhoto, perspectiveCorners) {
                        var activeCorner = -1
                        detectDragGestures(
                            onDragStart = { point ->
                                recordUndo()
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val ratio = displayPhoto.width.toFloat() / displayPhoto.height
                                val boxRatio = w / h
                                val dw = if (ratio > boxRatio) w else h * ratio
                                val dh = if (ratio > boxRatio) w / ratio else h
                                val left = (w - dw) / 2f
                                val top = (h - dh) / 2f
                                activeCorner = perspectiveCorners.indices.minByOrNull { i ->
                                    val px = left + perspectiveCorners[i].x * dw
                                    val py = top + perspectiveCorners[i].y * dh
                                    (px - point.x) * (px - point.x) + (py - point.y) * (py - point.y)
                                } ?: -1
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                if (activeCorner >= 0) {
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val ratio = displayPhoto.width.toFloat() / displayPhoto.height
                                    val boxRatio = w / h
                                    val dw = if (ratio > boxRatio) w else h * ratio
                                    val dh = if (ratio > boxRatio) w / ratio else h
                                    val left = (w - dw) / 2f
                                    val top = (h - dh) / 2f
                                    val updated = perspectiveCorners.toMutableList()
                                    val old = updated[activeCorner]
                                    updated[activeCorner] = Offset(
                                        (old.x + drag.x / dw).coerceIn(0f, 1f),
                                        (old.y + drag.y / dh).coerceIn(0f, 1f)
                                    )
                                    // Prevent crossed, concave or collapsed corner selections.
                                    if (isValidPerspectiveQuad(updated)) perspectiveCorners = updated
                                }
                            }
                        )
                    }) {
                        val ratio = displayPhoto.width.toFloat() / displayPhoto.height
                        val boxRatio = size.width / size.height
                        val dw = if (ratio > boxRatio) size.width else size.height * ratio
                        val dh = if (ratio > boxRatio) size.width / ratio else size.height
                        val left = (size.width - dw) / 2f
                        val top = (size.height - dh) / 2f
                        val pts = perspectiveCorners.map { Offset(left + it.x * dw, top + it.y * dh) }
                        for (i in pts.indices) {
                            drawLine(Color(0xFFE4B45D), pts[i], pts[(i + 1) % pts.size], strokeWidth = 3.dp.toPx())
                            drawCircle(Color(0xFF101522), 11.dp.toPx(), pts[i])
                            drawCircle(Color(0xFFE4B45D), 7.dp.toPx(), pts[i])
                        }
                    }
                }
            } else {
                Text("Unable to load photograph", color=Color.White)
            }
            if (busy) {
                Surface(color=Color(0xDD101522), shape=RoundedCornerShape(12.dp), modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                    Row(Modifier.padding(horizontal=12.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth=2.dp, color=Color(0xFFE4B45D))
                        Spacer(Modifier.width(8.dp))
                        Text(status, color=Color.White, fontSize=11.sp)
                    }
                }
            }
        }

        Column(
            Modifier.weight(1.25f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(colors=ButtonDefaults.outlinedButtonColors(contentColor=if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F), disabledContentColor=if (LocalPassportDark.current) Color(0xFFB9C2D0) else Color(0xFF596579)), border=androidx.compose.foundation.BorderStroke(1.5.dp, if (LocalPassportDark.current) Color(0xFF78869A) else Color(0xFF667386)), onClick={
                    if (undoStack.isNotEmpty()) {
                        redoStack = (redoStack + currentSnapshot()).takeLast(40)
                        restoreSnapshot(undoStack.last())
                        undoStack = undoStack.dropLast(1)
                    }
                }, enabled=!busy, modifier=Modifier.weight(1f), colors=ButtonDefaults.outlinedButtonColors(contentColor=if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F), disabledContentColor=if (LocalPassportDark.current) Color(0xFFB9C2D0) else Color(0xFF596579)), border=BorderStroke(1.dp, if (LocalPassportDark.current) Color(0xFF657184) else Color(0xFF7C8798))) { Text("↶ Undo", color=if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F), fontWeight=FontWeight.Bold) }
                OutlinedButton(onClick={
                    if (redoStack.isNotEmpty()) {
                        undoStack = (undoStack + currentSnapshot()).takeLast(40)
                        restoreSnapshot(redoStack.last())
                        redoStack = redoStack.dropLast(1)
                    }
                }, enabled=!busy, modifier=Modifier.weight(1f), colors=ButtonDefaults.outlinedButtonColors(contentColor=if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F), disabledContentColor=if (LocalPassportDark.current) Color(0xFFB9C2D0) else Color(0xFF596579)), border=BorderStroke(1.dp, if (LocalPassportDark.current) Color(0xFF657184) else Color(0xFF7C8798))) { Text("Redo ↷", color=if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF80520F), fontWeight=FontWeight.Bold) }
                FilterChip(selected=showOriginal, onClick={showOriginal=!showOriginal}, label={Text(if(showOriginal) "Show edited" else "Before / after")})
            }
            Text("MANUAL PERSPECTIVE", color=Muted, fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=perspectiveEnabled, onClick={recordUndo(); perspectiveEnabled = !perspectiveEnabled}, label={Text(if (perspectiveEnabled) "Adjust corners: ON" else "Adjust corners: OFF")})
                Text("Drag the four gold corner handles to straighten a tilted photo.", color=Muted, fontSize=10.sp, modifier=Modifier.weight(1f))
            }
            if (perspectiveEnabled) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={recordUndo(); perspectiveCorners=listOf(Offset(0f,0f),Offset(1f,0f),Offset(1f,1f),Offset(0f,1f))}) { Text("Reset corners") }
                    Text("The selected area is corrected to a straight rectangle.", color=Muted, fontSize=10.sp, modifier=Modifier.weight(1f))
                }
            }
            Text("TRANSFORM", color=Muted, fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                OutlinedButton(onClick={ recordUndo(); rotation=(rotation+270)%360 }, modifier=Modifier.weight(1f)) { Text("↶ 90°") }
                OutlinedButton(onClick={ recordUndo(); rotation=(rotation+90)%360 }, modifier=Modifier.weight(1f)) { Text("90° ↷") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                FilterChip(selected=flipHorizontal, onClick={recordUndo(); flipHorizontal=!flipHorizontal}, label={Text("Mirror ↔")})
                FilterChip(selected=flipVertical, onClick={recordUndo(); flipVertical=!flipVertical}, label={Text("Flip ↕")})
                Text("Rotation: $rotation°", color=Muted, fontSize=10.sp, modifier=Modifier.align(Alignment.CenterVertically))
            }
            Text("AI BACKGROUND • EDGE REFINEMENT", color=Muted, fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("White" to Pixel.WHITE, "Blue" to Pixel.rgb(35,91,170), "Red" to Pixel.rgb(190,35,48), "Grey" to Pixel.LTGRAY).forEach { (label, color) ->
                    FilterChip(selected=background==color, onClick={background=color}, label={Text(label)})
                }
            }
            Text("Edge softness ${(maskSoftness*100).toInt()}%", color=Muted, fontSize=10.sp)
            Slider(value=maskSoftness,onValueChange={maskSoftness=it},valueRange=0.04f..0.42f)
            Text("Fine-detail preservation ${(maskDetail*100).toInt()}%", color=Muted, fontSize=10.sp)
            Slider(value=maskDetail,onValueChange={maskDetail=it},valueRange=0f..1f)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Mask smoothing",color=Muted,fontSize=10.sp)
                listOf(0,1,2,3).forEach { level -> FilterChip(selected=maskRadius==level,onClick={maskRadius=level},label={Text(listOf("Off","Low","Medium","High")[level],fontSize=9.sp)}) }
            }
            Button(
                onClick = {
                    val source = editedBase ?: return@Button
                    busy = true
                    status = "AI is analysing your photograph…"
                    val selectedBackground = background
                    val selectedSoftness = maskSoftness
                    val selectedDetail = maskDetail
                    val selectedRadius = maskRadius
                    val detector = Segmentation.getClient(
                        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
                    )
                    detector.process(InputImage.fromBitmap(source, 0))
                        .addOnSuccessListener { mask ->
                            scope.launch {
                                try {
                                    status = "Replacing background…"
                                    val result = withContext(Dispatchers.Default) {
                                        val mw = mask.width
                                        val mh = mask.height
                                        val buffer = mask.buffer.order(ByteOrder.nativeOrder()).apply { rewind() }
                                        val rawMask = FloatArray(mw * mh) { buffer.getFloat().coerceIn(0f, 1f) }
                                        val confidence = refineSegmentationMask(rawMask,mw,mh,selectedRadius,selectedDetail)
                                        val input = IntArray(source.width * source.height)
                                        source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
                                        val output = IntArray(input.size)
                                        val br = Pixel.red(selectedBackground)
                                        val bg = Pixel.green(selectedBackground)
                                        val bb = Pixel.blue(selectedBackground)
                                        for (y in 0 until source.height) {
                                            val my = (y.toLong() * mh / source.height).toInt().coerceAtMost(mh - 1)
                                            for (x in 0 until source.width) {
                                                // Sample at pixel centres to avoid a half-pixel mask shift around the silhouette.
                                                val maskX = ((x + 0.5f) * mw / source.width) - 0.5f
                                                val maskY = ((y + 0.5f) * mh / source.height) - 0.5f
                                                val sampled = sampleMaskBilinear(confidence,mw,mh,maskX,maskY)
                                                val alpha = smoothAlpha(sampled,selectedSoftness)
                                                val p = input[y * source.width + x]
                                                output[y * source.width + x] = Pixel.rgb(
                                                    (Pixel.red(p) * alpha + br * (1f-alpha)).toInt().coerceIn(0,255),
                                                    (Pixel.green(p) * alpha + bg * (1f-alpha)).toInt().coerceIn(0,255),
                                                    (Pixel.blue(p) * alpha + bb * (1f-alpha)).toInt().coerceIn(0,255)
                                                )
                                            }
                                        }
                                        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                                            it.setPixels(output, 0, source.width, 0, 0, source.width, source.height)
                                        }
                                    }
                                    editedBase = result
                                    status = "Background replaced with refined edges. Preview updated."
                                } catch (e: Exception) {
                                    status = "Background replacement failed: ${e.localizedMessage ?: "try another photo"}"
                                } finally {
                                    busy = false
                                    detector.close()
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            status = "AI could not process this photo: ${e.localizedMessage ?: "please try again"}"
                            busy = false
                            detector.close()
                        }
                },
                enabled=!busy && editedBase!=null,
                modifier=Modifier.fillMaxWidth(),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))
            ) { Text(if(busy) "PROCESSING…" else "REMOVE / REPLACE BACKGROUND WITH AI", fontWeight=FontWeight.Bold) }

            Text("CROP FORMAT", color=Muted, fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("Original","Portrait","Landscape","35 × 45 mm","Square","4 × 6","Custom").forEach { value ->
                    FilterChip(selected=crop==value,onClick={recordUndo(); crop=value},label={Text(value,fontSize=10.sp)})
                }
            }
            if (crop == "Custom") {
                Text("CUSTOM CROP DIMENSIONS", color=Muted, fontSize=10.sp, fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=customWidth, onValueChange={ customWidth=it.filter(Char::isDigit).take(6) }, label={Text("Width")}, modifier=Modifier.weight(1f), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(value=customHeight, onValueChange={ customHeight=it.filter(Char::isDigit).take(6) }, label={Text("Height")}, modifier=Modifier.weight(1f), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("px","mm","cm","in").forEach { unit -> FilterChip(selected=customUnit==unit,onClick={customUnit=unit},label={Text(unit)}) }
                }
                if (customUnit != "px") {
                    Text("Output resolution: " + outputDpi + " DPI", color=Muted, fontSize=10.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        listOf(150,300,600).forEach { dpi -> FilterChip(selected=outputDpi==dpi,onClick={outputDpi=dpi},label={Text("$dpi DPI")}) }
                    }
                }
                Text("Preview follows the width-to-height ratio. Physical units convert to pixels using the selected DPI.", color=Muted, fontSize=10.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val valid = customWidth.toFloatOrNull()?.let { it > 0f } == true &&
                                customHeight.toFloatOrNull()?.let { it > 0f } == true
                            if (valid) {
                                editorDefaults.edit()
                                    .putString("width", customWidth)
                                    .putString("height", customHeight)
                                    .putString("unit", customUnit)
                                    .putInt("dpi", outputDpi)
                                    .putBoolean("use_custom_dimensions", true)
                                    .apply()
                                customDefaultSaved = true
                                status = "Default dimensions saved. They will be applied automatically to future editor photos."
                            } else {
                                status = "Enter valid width and height before saving defaults."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (customDefaultSaved) "Update saved default" else "Save as default") }
                    if (customDefaultSaved) {
                        TextButton(onClick = {
                            editorDefaults.edit().putBoolean("use_custom_dimensions", false).apply()
                            customDefaultSaved = false
                            status = "Automatic custom dimensions turned off. Saved values are retained."
                        }) { Text("Turn off") }
                    }
                }
                if (customDefaultSaved) {
                    Text("Automatic default is ON: " + customWidth + " × " + customHeight + " " + customUnit + (if (customUnit == "px") "" else " • " + outputDpi + " DPI"), color=Color(0xFF64D6A0), fontSize=10.sp)
                }
            }
            Text("Brightness ${(brightness*100).toInt()}%",color=Muted,fontSize=10.sp)
            Slider(value=brightness,onValueChange={recordUndo(); brightness=it},valueRange=-.35f.. .35f)
            Text("Contrast ${(contrast*100).toInt()}%",color=Muted,fontSize=10.sp)
            Slider(value=contrast,onValueChange={recordUndo(); contrast=it},valueRange=.5f..1.8f)
            Text("Saturation ${(saturation*100).toInt()}%",color=Muted,fontSize=10.sp)
            Slider(value=saturation,onValueChange={recordUndo(); saturation=it},valueRange=0f..2f)
            Text(status,color=Muted,fontSize=10.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick=onRetake,enabled=!busy,modifier=Modifier.weight(1f)) { Text("Retake") }
                OutlinedButton(onClick={
                    recordUndo(); editedBase=original; brightness=0f; contrast=1f; saturation=1f; crop="Original"; rotation=0; flipHorizontal=false; flipVertical=false; perspectiveEnabled=false; perspectiveCorners=listOf(Offset(0f,0f),Offset(1f,0f),Offset(1f,1f),Offset(0f,1f))
                    customWidth=editorDefaults.getString("width", "600") ?: "600"
                    customHeight=editorDefaults.getString("height", "800") ?: "800"
                    customUnit=editorDefaults.getString("unit", "px") ?: "px"
                    outputDpi=editorDefaults.getInt("dpi", 300)
                    crop=if (editorDefaults.getBoolean("use_custom_dimensions", false)) "Custom" else "Original"
                    showOriginal=false; status="Edits reset to the saved studio defaults."
                },enabled=!busy,modifier=Modifier.weight(1f)) { Text("Reset") }
                Button(
                    onClick={
                        val base=editedBase ?: return@Button
                        busy=true
                        status="Preparing full-quality photograph…"
                        scope.launch {
                            try {
                                val finalBitmap=withContext(Dispatchers.Default) {
                                    val oriented=transformPhoto(base,rotation,flipHorizontal,flipVertical)
                                    val perspective=if(perspectiveEnabled) perspectiveCorrect(oriented,perspectiveCorners) else oriented
                                    val cropped=cropForPreset(perspective,crop, if(crop=="Custom") customRatio else null)
                                    val adjusted=adjustPhoto(cropped,brightness,contrast,saturation)
                                    if (crop=="Custom" && customRatio!=null) {
                                        val factor=if(customUnit=="mm") outputDpi/25.4f else if(customUnit=="cm") outputDpi/2.54f else if(customUnit=="in") outputDpi.toFloat() else 1f
                                        val targetW=(customWidth.toFloat()*factor).toInt().coerceIn(1,12000)
                                        val targetH=(customHeight.toFloat()*factor).toInt().coerceIn(1,12000)
                                        Bitmap.createScaledBitmap(adjusted,targetW,targetH,true)
                                    } else adjusted
                                }
                                val file=withContext(Dispatchers.IO) {
                                    val dir=File(context.getExternalFilesDir(null),"PassportPro/Edited").apply { mkdirs() }
                                    val name="PassportPro_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.jpg"
                                    val output=File(dir,name)
                                    FileOutputStream(output).use { stream ->
                                        finalBitmap.compress(CompressFormat.JPEG,92,stream)
                                        stream.flush()
                                    }
                                    output
                                }
                                status="Saved successfully: ${file.name}"
                                onSaved()
                            } catch(e:Exception) {
                                status="Save failed: ${e.localizedMessage ?: "please try again"}"
                                busy=false
                            }
                        }
                    },
                    enabled=!busy && editedBase!=null,
                    modifier=Modifier.weight(1f),
                    colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))
                ) { Text(if(busy) "Saving…" else "Save",fontWeight=FontWeight.Bold) }
            }
        }
    }
}
