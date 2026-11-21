package gh.com.bigsammy.passportpro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as Pixel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.math.max
import kotlin.math.min

/*
 * Pro features. Every one is OFF by default, so the app behaves exactly as before
 * until the operator switches a feature on (Studio Control Centre, or "Pro tools"
 * on the Ready-to-deliver screen).
 */
internal object ProFeatures {
    private const val PREFS = "passportpro_pro_features"
    const val PHOTO_CHECKS = "photo_checks"
    const val DOCUMENT_LIBRARY = "document_library"
    const val HD_BACKGROUND = "hd_background"
    const val QUICK_PRINT = "quick_print"

    fun isOn(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

    fun set(context: Context, key: String, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
        if (key == HD_BACKGROUND && on) requestHdModel(context)
    }

    /** Asks Google Play services to download the HD model in the background (needs internet once). */
    fun requestHdModel(context: Context) {
        runCatching {
            val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
            ModuleInstall.getClient(context.applicationContext)
                .installModules(ModuleInstallRequest.newBuilder().addApi(client).build())
                .addOnCompleteListener { client.close() }
        }
    }
}

private data class ProFeatureInfo(val key: String, val title: String, val description: String, val icon: ImageVector)

private val proFeatureList = listOf(
    ProFeatureInfo(
        ProFeatures.PHOTO_CHECKS, "Automatic photo checks",
        "Checks head size, centring, tilt, eyes, expression, lighting, sharpness, background and glasses glare before you send. Auto-send pauses if a check fails.",
        Icons.Default.FactCheck
    ),
    ProFeatureInfo(
        ProFeatures.DOCUMENT_LIBRARY, "Document size library",
        "Adds ready-made sizes for Ghana passport, US passport/visa, UK passport, Schengen visa and Canada passport, with the right head size and background colour.",
        Icons.Default.Public
    ),
    ProFeatureInfo(
        ProFeatures.HD_BACKGROUND, "HD background removal",
        "Uses a higher-quality AI model with hair-edge refinement. The first time, Google Play services downloads the model (internet needed once).",
        Icons.Default.AutoAwesome
    ),
    ProFeatureInfo(
        ProFeatures.QUICK_PRINT, "Print straight from capture",
        "Adds a Print button after capture: lays out copies on 4 × 6 in or A4 with cut guides, then prints or shares the PDF.",
        Icons.Default.Print
    )
)

/** List of switches for the pro features. */
@Composable
internal fun ProFeatureSwitches(modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        proFeatureList.forEach { feature ->
            var on by remember(feature.key) { mutableStateOf(ProFeatures.isOn(context, feature.key)) }
            Surface(
                onClick = { on = !on; ProFeatures.set(context, feature.key, on); onChanged() },
                color = if (on) BrandAccent.copy(alpha = .10f) else Ink,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(feature.icon, contentDescription = null, tint = if (on) BrandAccent else Muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(feature.title, color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(feature.description, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = on, onCheckedChange = { on = it; ProFeatures.set(context, feature.key, it); onChanged() })
                }
            }
        }
        Text("All pro features are off unless you switch them on. The app works exactly as before when they are off.", color = Muted, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProToolsSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Pro tools", color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ProFeatureSwitches(onChanged = onChanged)
            StudioButton("Done", onClick = onDismiss, icon = Icons.Default.Check, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- HD background

/**
 * Higher-quality person mask: ML Kit Subject Segmentation (full-resolution confidence mask)
 * refined with a guided filter so hair strands follow the real image edges.
 * Returns null when the model is not available yet (e.g. still downloading).
 * Call off the main thread.
 */
internal fun hdPersonMask(source: Bitmap, maxEdge: Int = 1280): PersonMask? {
    val scale = min(1f, maxEdge.toFloat() / max(source.width, source.height))
    val work = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
    val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    return try {
        val result = Tasks.await(client.process(InputImage.fromBitmap(work, 0)))
        val buffer = result.foregroundConfidenceMask ?: return null
        buffer.rewind()
        val raw = FloatArray(work.width * work.height) { if (buffer.hasRemaining()) buffer.get().coerceIn(0f, 1f) else 0f }
        val radius = max(2, min(work.width, work.height) / 160)
        PersonMask(guidedFilterMask(work, raw, radius, 1e-3f), work.width, work.height)
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}

/** Edge-aware refinement (He et al. guided filter) using the photo's brightness as the guide. */
internal fun guidedFilterMask(image: Bitmap, mask: FloatArray, radius: Int, eps: Float): FloatArray {
    val w = image.width
    val h = image.height
    val n = w * h
    val pixels = IntArray(n)
    image.getPixels(pixels, 0, w, 0, 0, w, h)
    val guide = FloatArray(n) { i ->
        val p = pixels[i]
        (0.299f * Pixel.red(p) + 0.587f * Pixel.green(p) + 0.114f * Pixel.blue(p)) / 255f
    }
    val tmp = FloatArray(n)
    fun box(src: FloatArray, out: FloatArray) {
        // Horizontal running mean
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            for (x in 0..min(radius, w - 1)) { sum += src[row + x]; count++ }
            for (x in 0 until w) {
                tmp[row + x] = sum / count
                val add = x + radius + 1
                val remove = x - radius
                if (add < w) { sum += src[row + add]; count++ }
                if (remove >= 0) { sum -= src[row + remove]; count-- }
            }
        }
        // Vertical running mean
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (y in 0..min(radius, h - 1)) { sum += tmp[y * w + x]; count++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / count
                val add = y + radius + 1
                val remove = y - radius
                if (add < h) { sum += tmp[add * w + x]; count++ }
                if (remove >= 0) { sum -= tmp[remove * w + x]; count-- }
            }
        }
    }
    val meanI = FloatArray(n); box(guide, meanI)
    val meanP = FloatArray(n); box(mask, meanP)
    val work = FloatArray(n)
    val corrI = FloatArray(n)
    for (i in 0 until n) work[i] = guide[i] * guide[i]
    box(work, corrI)
    val corrIp = FloatArray(n)
    for (i in 0 until n) work[i] = guide[i] * mask[i]
    box(work, corrIp)
    // a and b (reuse corrI for a, corrIp for b)
    for (i in 0 until n) {
        val varI = corrI[i] - meanI[i] * meanI[i]
        val covIp = corrIp[i] - meanI[i] * meanP[i]
        val a = covIp / (varI + eps)
        corrI[i] = a
        corrIp[i] = meanP[i] - a * meanI[i]
    }
    box(corrI, meanI)   // mean of a
    box(corrIp, meanP)  // mean of b
    val out = FloatArray(n)
    for (i in 0 until n) {
        val q = (meanI[i] * guide[i] + meanP[i]).coerceIn(0f, 1f)
        // Keep clearly solid areas solid; let the filter shape the uncertain edges.
        val original = mask[i]
        out[i] = when {
            original > 0.97f -> max(q, original)
            original < 0.03f -> min(q, original + 0.02f)
            else -> q
        }
    }
    return out
}
