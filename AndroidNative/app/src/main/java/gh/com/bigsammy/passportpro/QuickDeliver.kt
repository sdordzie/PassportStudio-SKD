package gh.com.bigsammy.passportpro

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color as Pixel

/*
 * Instant delivery: capture → automatic face crop → AI background → share.
 * Everything runs on the device. The source photo is down-sampled before any
 * pixel work so the whole pipeline finishes in about a second on mid-range phones.
 */

internal val Accent = Color(0xFFE4B45D)
internal val WhatsAppGreen = Color(0xFF25D366)
private val Success = Color(0xFF3DDC97)

internal const val QUICK_PREFS = "passportpro_quick_delivery"

/** What happens automatically after the shutter: nothing, the share menu, or WhatsApp. */
internal enum class AutoSend(val key: String, val label: String) {
    OFF("off", "Auto-send off"),
    MENU("menu", "Auto-send: Share menu"),
    WHATSAPP("whatsapp", "Auto-send: WhatsApp");

    fun next(): AutoSend = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(key: String?): AutoSend = entries.firstOrNull { it.key == key } ?: MENU
    }
}

internal fun readAutoSend(context: Context): AutoSend =
    AutoSend.from(context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).getString("auto_send", AutoSend.MENU.key))

internal fun writeAutoSend(context: Context, value: AutoSend) {
    context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).edit().putString("auto_send", value.key).apply()
}

internal data class DeliverySize(
    val key: String,
    val label: String,
    val detail: String,
    /** width ÷ height, or null to keep the whole photo */
    val ratio: Float?,
    val outW: Int,
    val outH: Int,
    /** share of the photo height taken by the head (crown to chin) */
    val headFraction: Float
)

internal data class CustomSizeSpec(val width: Float, val height: Float, val unit: String, val dpi: Int) {
    fun pixels(): Pair<Int, Int> {
        val factor = when (unit) { "mm" -> dpi / 25.4f; "cm" -> dpi / 2.54f; "in" -> dpi.toFloat(); else -> 1f }
        return (width * factor).toInt().coerceIn(50, 6000) to (height * factor).toInt().coerceIn(50, 6000)
    }
    fun label(): String {
        fun clean(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
        return "${clean(width)} × ${clean(height)} $unit"
    }
}

private const val EDITOR_PREFS = "passportpro_editor_defaults"

/** The custom size shared by the camera and the Photo Editor ("Set as default" in the editor). */
internal fun readCustomSize(context: Context): CustomSizeSpec? {
    val prefs = context.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean("use_custom_dimensions", false)) return null
    val w = prefs.getString("width", null)?.toFloatOrNull() ?: return null
    val h = prefs.getString("height", null)?.toFloatOrNull() ?: return null
    if (w <= 0f || h <= 0f) return null
    return CustomSizeSpec(w, h, prefs.getString("unit", "mm") ?: "mm", prefs.getInt("dpi", 300))
}

internal fun writeCustomSize(context: Context, spec: CustomSizeSpec) {
    fun clean(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    context.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE).edit()
        .putString("width", clean(spec.width))
        .putString("height", clean(spec.height))
        .putString("unit", spec.unit)
        .putInt("dpi", spec.dpi)
        .putBoolean("use_custom_dimensions", true)
        .apply()
}

internal fun deliverySizes(context: Context): List<DeliverySize> {
    val sizes = mutableListOf(
        DeliverySize("passport", "Passport", "35 × 45 mm", 35f / 45f, 827, 1063, 0.74f),
        DeliverySize("visa", "Visa", "2 × 2 in", 1f, 1200, 1200, 0.60f),
        DeliverySize("id", "ID photo", "30 × 40 mm", 30f / 40f, 709, 945, 0.70f)
    )
    readCustomSize(context)?.let { spec ->
        val (pw, ph) = spec.pixels()
        sizes += DeliverySize("custom", "Custom", spec.label(), pw.toFloat() / ph, pw, ph, 0.72f)
    }
    sizes += DeliverySize("full", "Full photo", "No crop", null, 0, 0, 0f)
    return sizes
}

/** The size chosen before capture; remembered between customers. */
internal fun readSelectedSize(context: Context): DeliverySize {
    val sizes = deliverySizes(context)
    val key = context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).getString("size", null)
        ?.let { if (it == "studio") "custom" else it }
    return sizes.firstOrNull { it.key == key } ?: sizes.first()
}

internal fun writeSelectedSize(context: Context, key: String) {
    context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).edit().putString("size", key).apply()
}

internal data class DeliveryBackground(val key: String, val label: String, val color: Int?)

internal val deliveryBackgrounds = listOf(
    DeliveryBackground("white", "White", Pixel.WHITE),
    DeliveryBackground("blue", "Blue", Pixel.rgb(35, 91, 170)),
    DeliveryBackground("sky", "Light blue", Pixel.rgb(205, 228, 248)),
    DeliveryBackground("red", "Red", Pixel.rgb(190, 35, 48)),
    DeliveryBackground("grey", "Grey", Pixel.LTGRAY),
    DeliveryBackground("original", "Original", null)
)

// ---------------------------------------------------------------- image pipeline

/** Decodes at a reduced size (fast) and applies the EXIF orientation. */
internal fun decodeForDelivery(path: String, maxEdge: Int = 2400): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Finds the largest face. Runs on a small copy for speed and maps the box back. Call off the main thread. */
internal fun detectMainFace(source: Bitmap): Rect? {
    val largest = maxOf(source.width, source.height)
    val scale = if (largest > 900) 900f / largest else 1f
    val small = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
    )
    return try {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(small, 0)))
        val box = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox ?: return null
        Rect((box.left / scale).toInt(), (box.top / scale).toInt(), (box.right / scale).toInt(), (box.bottom / scale).toInt())
    } catch (_: Exception) {
        null
    } finally {
        detector.close()
    }
}

/** Crops to the chosen format with the head placed to passport proportions, then scales to the output size. */
internal fun cropForDelivery(source: Bitmap, face: Rect?, size: DeliverySize): Bitmap {
    val ratio = size.ratio
    if (ratio == null) {
        val largest = maxOf(source.width, source.height)
        if (largest <= 1800) return source
        val s = 1800f / largest
        return Bitmap.createScaledBitmap(source, (source.width * s).toInt(), (source.height * s).toInt(), true)
    }
    var cropH: Float
    val centreX: Float
    var top: Float
    if (face != null && face.height() > 0) {
        // ML Kit's face box runs roughly from the eyebrows to the chin; the whole head is about 1.38× taller.
        val head = face.height() * 1.38f
        cropH = head / size.headFraction
        centreX = face.exactCenterX()
        val crown = face.bottom - head
        top = crown - cropH * (1f - size.headFraction) * 0.42f
    } else {
        cropH = source.height.toFloat()
        centreX = source.width / 2f
        top = 0f
    }
    var cropW = cropH * ratio
    val fit = minOf(1f, source.width / cropW, source.height / cropH)
    if (fit < 1f) {
        val oldH = cropH
        cropW *= fit
        cropH *= fit
        top += (oldH - cropH) / 2f
    }
    val left = (centreX - cropW / 2f).coerceIn(0f, (source.width - cropW).coerceAtLeast(0f))
    top = top.coerceIn(0f, (source.height - cropH).coerceAtLeast(0f))
    val w = cropW.toInt().coerceIn(1, source.width - left.toInt())
    val h = cropH.toInt().coerceIn(1, source.height - top.toInt())
    val cropped = Bitmap.createBitmap(source, left.toInt(), top.toInt(), w, h)
    return Bitmap.createScaledBitmap(cropped, size.outW, size.outH, true)
}

internal class PersonMask(val values: FloatArray, val width: Int, val height: Int)

/** On-device selfie segmentation. Call off the main thread. */
internal fun computePersonMask(base: Bitmap): PersonMask {
    val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
    )
    try {
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(base, 0)))
        val mw = mask.width
        val mh = mask.height
        val buffer = mask.buffer.order(ByteOrder.nativeOrder()).apply { rewind() }
        val raw = FloatArray(mw * mh) { buffer.getFloat().coerceIn(0f, 1f) }
        return PersonMask(refineSegmentationMask(raw, mw, mh, 2, 0.72f), mw, mh)
    } finally {
        segmenter.close()
    }
}

internal fun replaceBackground(base: Bitmap, mask: PersonMask, color: Int, softness: Float = 0.18f): Bitmap {
    val w = base.width
    val h = base.height
    val pixels = IntArray(w * h)
    base.getPixels(pixels, 0, w, 0, 0, w, h)
    val br = Pixel.red(color); val bg = Pixel.green(color); val bb = Pixel.blue(color)
    val sx = mask.width.toFloat() / w
    val sy = mask.height.toFloat() / h
    for (y in 0 until h) {
        val my = (y + 0.5f) * sy - 0.5f
        val row = y * w
        for (x in 0 until w) {
            val alpha = smoothAlpha(sampleMaskBilinear(mask.values, mask.width, mask.height, (x + 0.5f) * sx - 0.5f, my), softness)
            if (alpha >= 0.999f) continue
            val p = pixels[row + x]
            val inv = 1f - alpha
            pixels[row + x] = Pixel.rgb(
                (Pixel.red(p) * alpha + br * inv).toInt(),
                (Pixel.green(p) * alpha + bg * inv).toInt(),
                (Pixel.blue(p) * alpha + bb * inv).toInt()
            )
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
}

// ---------------------------------------------------------------- saving & sharing

private fun studioEditedFolder(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "PassportPro/Edited").apply { mkdirs() }

/**
 * Writes the JPEG to the Studio Gallery and (optionally) the phone's Gallery.
 * Returns the Studio file, which is what gets shared.
 */
internal suspend fun saveFinishedPhoto(context: Context, bitmap: Bitmap, quality: Int = 95, alsoDeviceGallery: Boolean = true): File =
    withContext(Dispatchers.IO) {
        val name = "PassportPro_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val output = File(studioEditedFolder(context), name)
        FileOutputStream(output).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw IllegalStateException("JPEG encoding failed")
        }
        if (alsoDeviceGallery) runCatching { copyToDeviceGallery(context, output) }
        output
    }

private fun copyToDeviceGallery(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DESCRIPTION, "Edited with PassportPro Studio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PassportPro Studio/Edited")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    try {
        resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Could not open the Gallery entry")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

/**
 * Opens WhatsApp (or WhatsApp Business) straight away, or the Android share menu.
 * Returns a message for the user when WhatsApp is not installed, otherwise null.
 */
internal fun sharePhoto(context: Context, file: File, viaWhatsApp: Boolean): String? {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    fun sendIntent() = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (viaWhatsApp) {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                context.startActivity(sendIntent().setPackage(pkg))
                return null
            } catch (_: ActivityNotFoundException) { }
        }
    }
    context.startActivity(Intent.createChooser(sendIntent(), "Send passport photo"))
    return if (viaWhatsApp) "WhatsApp is not installed — choose another app." else null
}

// ---------------------------------------------------------------- screen

/**
 * Shown straight after the shutter. Crops, cleans the background and (by default)
 * opens the share menu immediately — no trip to the Gallery.
 */
@Composable
internal fun QuickFinishScreen(
    photoPath: String,
    onRetake: () -> Unit,
    onFineTune: () -> Unit,
    onNextCustomer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE) }
    var sizes by remember { mutableStateOf(deliverySizes(context)) }
    var sizeKey by remember { mutableStateOf(readSelectedSize(context).key) }
    var showSizePicker by remember { mutableStateOf(false) }
    var backgroundKey by remember { mutableStateOf(prefs.getString("background", "white") ?: "white") }
    var autoSend by remember { mutableStateOf(readAutoSend(context)) }
    var brightnessDraft by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }

    var source by remember(photoPath) { mutableStateOf<Bitmap?>(null) }
    var face by remember(photoPath) { mutableStateOf<Rect?>(null) }
    var base by remember(photoPath) { mutableStateOf<Bitmap?>(null) }
    var mask by remember(photoPath) { mutableStateOf<Pair<Bitmap, PersonMask>?>(null) }
    var result by remember(photoPath) { mutableStateOf<Bitmap?>(null) }
    var stage by remember(photoPath) { mutableStateOf("Preparing photo…") }
    var error by remember(photoPath) { mutableStateOf<String?>(null) }
    var exported by remember(photoPath) { mutableStateOf<Pair<Bitmap, File>?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sentCount by remember(photoPath) { mutableIntStateOf(0) }
    var autoDone by remember(photoPath) { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val size = sizes.firstOrNull { it.key == sizeKey } ?: sizes.first()
    val background = deliveryBackgrounds.firstOrNull { it.key == backgroundKey } ?: deliveryBackgrounds.first()
    val working = error == null && (result == null || stage.isNotEmpty())

    // 1. Decode + find the face (once per photo).
    LaunchedEffect(photoPath) {
        stage = "Finding the face…"
        val loaded = withContext(Dispatchers.Default) {
            val bmp = decodeForDelivery(photoPath) ?: return@withContext null
            bmp to detectMainFace(bmp)
        }
        if (loaded == null) { error = "This photo could not be opened."; stage = ""; return@LaunchedEffect }
        face = loaded.second
        source = loaded.first
    }

    // 2. Crop to the chosen size.
    LaunchedEffect(source, sizeKey) {
        val src = source ?: return@LaunchedEffect
        stage = if (face == null && size.ratio != null) "No face found — centring the crop…" else "Framing the head…"
        base = withContext(Dispatchers.Default) { cropForDelivery(src, face, size) }
    }

    // 3. Background + brightness.
    LaunchedEffect(base, backgroundKey, brightness) {
        val b = base ?: return@LaunchedEffect
        val colour = background.color
        val output = withContext(Dispatchers.Default) {
            val cleaned = if (colour == null) b else {
                val personMask = mask?.takeIf { it.first === b }?.second ?: run {
                    withContext(Dispatchers.Main) { stage = "Cleaning the background…" }
                    computePersonMask(b).also { m -> withContext(Dispatchers.Main) { mask = b to m } }
                }
                replaceBackground(b, personMask, colour)
            }
            adjustPhoto(cleaned, brightness, 1f, 1f)
        }
        result = output
        stage = ""
    }

    fun send(viaWhatsApp: Boolean) {
        val photo = result ?: return
        if (sending) return
        sending = true
        scope.launch {
            try {
                val file = exported?.takeIf { it.first === photo }?.second
                    ?: saveFinishedPhoto(context, photo).also { exported = photo to it }
                notice = sharePhoto(context, file, viaWhatsApp)
                sentCount++
            } catch (e: Exception) {
                notice = "Could not send: ${e.localizedMessage ?: "please try again"}"
            } finally {
                sending = false
            }
        }
    }

    fun saveOnly() {
        val photo = result ?: return
        if (exported?.first === photo) { notice = "Already saved to your Studio Gallery and phone Gallery."; return }
        scope.launch {
            try {
                val file = saveFinishedPhoto(context, photo)
                exported = photo to file
                notice = "Saved to your Studio Gallery and phone Gallery."
            } catch (e: Exception) {
                notice = "Could not save: ${e.localizedMessage ?: "please try again"}"
            }
        }
    }

    // 4. Auto-send as soon as the first finished photo is ready.
    LaunchedEffect(result, stage) {
        if (!autoDone && result != null && stage.isEmpty() && autoSend != AutoSend.OFF) {
            autoDone = true
            send(autoSend == AutoSend.WHATSAPP)
        }
    }

    Column(
        modifier.fillMaxSize().background(Ink).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRetake) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to camera", tint = BrandAccent) }
            Column(Modifier.weight(1f)) {
                Text("READY TO DELIVER", color = BrandAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(
                    when {
                        error != null -> "Something went wrong"
                        working -> "Finishing your photo"
                        sentCount > 0 -> "Photo sent"
                        else -> "Your photo is ready"
                    },
                    color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            StudioButton("Fine-tune", onClick = onFineTune, enabled = !working, icon = Icons.Default.Tune, tone = ButtonTone.Quiet, compact = true)
        }

        // Preview
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF0A0E16)),
            contentAlignment = Alignment.Center
        ) {
            val shown = result ?: base
            if (shown != null) {
                Image(
                    shown.asImageBitmap(),
                    contentDescription = "Finished passport photo",
                    modifier = Modifier.fillMaxSize().padding(18.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit
                )
            } else if (error == null) {
                CircularProgressIndicator(color = BrandAccent)
            }
            if (error != null) {
                Text(error!!, color = Color(0xFFFF8A80), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            if (working && error == null) {
                Surface(color = Color(0xE6101522), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(9.dp))
                        Text(stage.ifEmpty { "Working…" }, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (!working && sentCount > 0) {
                Surface(color = Color(0xE6101522), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sent • saved in Studio Gallery", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // Options
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Size")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sizes.forEach { option ->
                    OptionPill(selected = option.key == sizeKey, title = option.label, subtitle = option.detail) {
                        sizeKey = option.key
                        writeSelectedSize(context, option.key)
                    }
                }
                OptionPill(selected = false, title = "Custom…", subtitle = "Set your size") { showSizePicker = true }
            }
            SectionLabel("Background")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                deliveryBackgrounds.forEach { option ->
                    ColourSwatch(option, selected = option.key == backgroundKey) {
                        backgroundKey = option.key
                        prefs.edit().putString("background", option.key).apply()
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                Slider(
                    value = brightnessDraft,
                    onValueChange = { brightnessDraft = it },
                    onValueChangeFinished = { brightness = brightnessDraft },
                    valueRange = -0.25f..0.25f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = BrandAccent, activeTrackColor = BrandAccent)
                )
                Text("${(brightnessDraft * 100).toInt()}%", color = Muted, fontSize = 11.sp, modifier = Modifier.width(38.dp))
            }
            Surface(
                onClick = { autoSend = autoSend.next(); writeAutoSend(context, autoSend) },
                color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = BrandAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(autoSend.label, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("What happens right after you take a photo. Tap to change.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }

        notice?.let {
            Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }

        // Actions
        ButtonPair {
            StudioButton("WhatsApp", onClick = { send(viaWhatsApp = true) }, enabled = !working, busy = sending, icon = Icons.Default.Send, tone = ButtonTone.WhatsApp, modifier = Modifier.weight(1f))
            StudioButton("Share", onClick = { send(viaWhatsApp = false) }, enabled = !working, busy = sending, icon = Icons.Default.Share, modifier = Modifier.weight(1f))
        }
        ButtonPair {
            StudioButton("Retake", onClick = onRetake, icon = Icons.Default.Replay, tone = ButtonTone.Secondary, compact = true, modifier = Modifier.weight(1f))
            StudioButton("Save", onClick = { saveOnly() }, enabled = !working, icon = Icons.Default.Download, tone = ButtonTone.Secondary, compact = true, modifier = Modifier.weight(1f))
            StudioButton("Next", onClick = onNextCustomer, icon = Icons.Default.AddAPhoto, tone = ButtonTone.Secondary, compact = true, modifier = Modifier.weight(1f))
        }
    }

    if (showSizePicker) {
        SizePickerSheet(
            selectedKey = sizeKey,
            onSelect = { key -> sizes = deliverySizes(context); sizeKey = key },
            onDismiss = { showSizePicker = false }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
}

@Composable
private fun OptionPill(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) BrandAccent.copy(alpha = .14f) else Panel,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) BrandAccent else Muted.copy(alpha = .3f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(title, color = if (selected) BrandAccent else PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ColourSwatch(option: DeliveryBackground, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.size(40.dp)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) BrandAccent else Muted.copy(alpha = .4f)), CircleShape)
                .padding(if (selected) 5.dp else 2.dp)
                .clip(CircleShape)
                .background(option.color?.let { Color(it) } ?: Color(0xFF3A4352)),
            contentAlignment = Alignment.Center
        ) {
            if (option.color == null) Icon(Icons.Default.HideImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            else if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = if (option.key == "white" || option.key == "sky" || option.key == "grey") Color(0xFF101522) else Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(option.label, color = if (selected) BrandAccent else Muted, fontSize = 10.sp)
    }
}
