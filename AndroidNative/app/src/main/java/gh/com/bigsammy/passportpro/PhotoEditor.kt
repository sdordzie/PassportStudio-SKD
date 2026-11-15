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

@Composable
internal fun PhotoEditorScreen(photoPath: String, onRetake: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(photoPath) {
        decodeUprightPhoto(photoPath)?.copy(Bitmap.Config.ARGB_8888, false)?.let { fitPreview(it, 2200) }
    }
    var editedBase by remember(photoPath) { mutableStateOf(original) }
    var preview by remember(photoPath) { mutableStateOf(original?.let { fitPreview(it, 1000) }) }
    var background by remember { mutableIntStateOf(Pixel.WHITE) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var crop by remember { mutableStateOf("Original") }
    var customWidth by remember { mutableStateOf("600") }
    var customHeight by remember { mutableStateOf("800") }
    var customUnit by remember { mutableStateOf("px") }
    var outputDpi by remember { mutableIntStateOf(300) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your photograph is ready. Changes appear in the preview.") }

    val customRatio = remember(customWidth, customHeight) {
        val w = customWidth.toFloatOrNull()?.takeIf { it > 0f }
        val h = customHeight.toFloatOrNull()?.takeIf { it > 0f }
        if (w == null || h == null) null else w / h
    }
    // Keep the preview visible and responsive; all pixel work runs away from the UI thread.
    LaunchedEffect(editedBase, brightness, contrast, saturation, crop, customRatio) {
        val base = editedBase
        if (base != null) {
            preview = withContext(Dispatchers.Default) {
                val small = fitPreview(base, 1000)
                val cropped = cropForPreset(small, crop, if (crop == "Custom") customRatio else null)
                adjustPhoto(cropped, brightness, contrast, saturation)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PHOTO EDITOR", color=Color(0xFFE4B45D), fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.2.sp)
                Text("Live editing preview", color=Color.White, fontSize=21.sp, fontWeight=FontWeight.Bold)
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
            if (preview != null) {
                Image(preview!!.asImageBitmap(), "Live passport photo editing preview",
                    Modifier.fillMaxSize().padding(5.dp), contentScale=ContentScale.Fit)
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
            Text("AI BACKGROUND", color=Color(0xFF9AA7B8), fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("White" to Pixel.WHITE, "Blue" to Pixel.rgb(35,91,170), "Red" to Pixel.rgb(190,35,48), "Grey" to Pixel.LTGRAY).forEach { (label, color) ->
                    FilterChip(selected=background==color, onClick={background=color}, label={Text(label)})
                }
            }
            Button(
                onClick = {
                    val source = editedBase ?: return@Button
                    busy = true
                    status = "AI is analysing your photograph…"
                    val selectedBackground = background
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
                                        val confidence = FloatArray(mw * mh) { buffer.getFloat().coerceIn(0f, 1f) }
                                        val input = IntArray(source.width * source.height)
                                        source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
                                        val output = IntArray(input.size)
                                        val br = Pixel.red(selectedBackground)
                                        val bg = Pixel.green(selectedBackground)
                                        val bb = Pixel.blue(selectedBackground)
                                        for (y in 0 until source.height) {
                                            val my = (y.toLong() * mh / source.height).toInt().coerceAtMost(mh - 1)
                                            for (x in 0 until source.width) {
                                                val mx = (x.toLong() * mw / source.width).toInt().coerceAtMost(mw - 1)
                                                val alpha = confidence[my * mw + mx]
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
                                    status = "Background replaced. Your preview has been updated."
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

            Text("CROP FORMAT", color=Color(0xFF9AA7B8), fontSize=10.sp, fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("Original","Portrait","Landscape","35 × 45 mm","Square","4 × 6","Custom").forEach { value ->
                    FilterChip(selected=crop==value,onClick={crop=value},label={Text(value,fontSize=10.sp)})
                }
            }
            if (crop == "Custom") {
                Text("CUSTOM CROP DIMENSIONS", color=Color(0xFF9AA7B8), fontSize=10.sp, fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=customWidth, onValueChange={ customWidth=it.filter(Char::isDigit).take(6) }, label={Text("Width")}, modifier=Modifier.weight(1f), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(value=customHeight, onValueChange={ customHeight=it.filter(Char::isDigit).take(6) }, label={Text("Height")}, modifier=Modifier.weight(1f), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("px","mm","cm","in").forEach { unit -> FilterChip(selected=customUnit==unit,onClick={customUnit=unit},label={Text(unit)}) }
                }
                if (customUnit != "px") {
                    Text("Output resolution: " + outputDpi + " DPI", color=Color(0xFF9AA7B8), fontSize=10.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        listOf(150,300,600).forEach { dpi -> FilterChip(selected=outputDpi==dpi,onClick={outputDpi=dpi},label={Text("$dpi DPI")}) }
                    }
                }
                Text("Preview follows the width-to-height ratio. Physical units convert to pixels using the selected DPI.", color=Color(0xFF9AA7B8), fontSize=10.sp)
            }
            Text("Brightness ${(brightness*100).toInt()}%",color=Color(0xFF9AA7B8),fontSize=10.sp)
            Slider(value=brightness,onValueChange={brightness=it},valueRange=-.35f.. .35f)
            Text("Contrast ${(contrast*100).toInt()}%",color=Color(0xFF9AA7B8),fontSize=10.sp)
            Slider(value=contrast,onValueChange={contrast=it},valueRange=.5f..1.8f)
            Text("Saturation ${(saturation*100).toInt()}%",color=Color(0xFF9AA7B8),fontSize=10.sp)
            Slider(value=saturation,onValueChange={saturation=it},valueRange=0f..2f)
            Text(status,color=Color(0xFF9AA7B8),fontSize=10.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick=onRetake,enabled=!busy,modifier=Modifier.weight(1f)) { Text("Retake") }
                OutlinedButton(onClick={
                    editedBase=original; brightness=0f; contrast=1f; saturation=1f; crop="Original"
                    customWidth="600"; customHeight="800"; customUnit="px"; outputDpi=300
                    status="Edits reset."
                },enabled=!busy,modifier=Modifier.weight(1f)) { Text("Reset") }
                Button(
                    onClick={
                        val base=editedBase ?: return@Button
                        busy=true
                        status="Preparing full-quality photograph…"
                        scope.launch {
                            try {
                                val finalBitmap=withContext(Dispatchers.Default) {
                                    val cropped=cropForPreset(base,crop, if(crop=="Custom") customRatio else null)
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
