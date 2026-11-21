package gh.com.bigsammy.passportpro

import android.graphics.Bitmap
import android.graphics.Color as Pixel
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Automatic photo checks (pro feature). All analysis is on the device.
 * The checks are guidance for the operator; acceptance is always decided by the issuing office.
 */

internal enum class CheckLevel { PASS, WARN, FAIL }

internal data class CheckItem(val title: String, val level: CheckLevel, val detail: String)

private fun luminance(p: Int): Float = (0.299f * Pixel.red(p) + 0.587f * Pixel.green(p) + 0.114f * Pixel.blue(p)) / 255f

/**
 * @param photo the finished photo (after background change)
 * @param before the same crop before the background was changed (used for lighting and glare)
 * @param mask person mask for [before], if the background was replaced (gives the true top of the hair)
 */
internal fun runPhotoChecks(photo: Bitmap, before: Bitmap, mask: PersonMask?, size: DeliverySize, backgroundReplaced: Boolean, backgroundKey: String): List<CheckItem> {
    val checks = mutableListOf<CheckItem>()
    val w = photo.width
    val h = photo.height
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    )
    val faces: List<Face> = try {
        Tasks.await(detector.process(InputImage.fromBitmap(photo, 0)))
    } catch (_: Exception) {
        emptyList()
    } finally {
        detector.close()
    }

    when {
        faces.isEmpty() -> {
            checks += CheckItem("Face", CheckLevel.FAIL, "No face found. Retake with the face clearly visible and well lit.")
            return checks
        }
        faces.size > 1 -> checks += CheckItem("One person only", CheckLevel.FAIL, "${faces.size} faces found. Only the customer should be in the photo.")
        else -> checks += CheckItem("Face", CheckLevel.PASS, "One face found.")
    }
    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
    val box = face.boundingBox

    // ---- Head size (chin to top of hair) ----
    val contour = face.getContour(FaceContour.FACE)?.points.orEmpty()
    val chinY = contour.maxOfOrNull { it.y } ?: box.bottom.toFloat()
    val contourTop = contour.minOfOrNull { it.y } ?: box.top.toFloat()
    var crownMeasured = false
    val crownY: Float = mask?.let { m ->
        // Topmost row of the person mask above the face: the real top of the hair.
        val sx = m.width.toFloat() / w
        val sy = m.height.toFloat() / h
        val x0 = (box.left + box.width() * 0.25f).times(sx).toInt().coerceIn(0, m.width - 1)
        val x1 = (box.right - box.width() * 0.25f).times(sx).toInt().coerceIn(0, m.width - 1)
        var top = -1
        loop@ for (y in 0 until m.height) {
            for (x in x0..x1) if (m.values[y * m.width + x] > 0.5f) { top = y; break@loop }
        }
        if (top >= 0) { crownMeasured = true; top / sy } else null
    } ?: (chinY - (chinY - contourTop) * 1.2f)
    if (size.ratio != null) {
        val head = (chinY - crownY).coerceAtLeast(1f) / h
        val range = size.headRange ?: ((size.headFraction - 0.07f)..(size.headFraction + 0.07f))
        val tolerance = 0.03f
        fun describe(fraction: Float) = size.heightMm?.let { "%.1f mm".format(fraction * it) } ?: "${(fraction * 100).roundToInt()}%"
        val need = "${describe(range.start)}–${describe(range.endInclusive)}"
        val measured = describe(head) + if (crownMeasured) "" else " (estimated)"
        checks += when {
            head in range -> CheckItem("Head size", CheckLevel.PASS, "$measured — within $need.")
            head in (range.start - tolerance)..(range.endInclusive + tolerance) ->
                CheckItem("Head size", CheckLevel.WARN, "$measured — just outside $need. " + if (head < range.start) "Move closer or zoom in slightly." else "Step back slightly.")
            head < range.start -> CheckItem("Head size", CheckLevel.FAIL, "$measured — too small (needs $need). Move the camera closer.")
            else -> CheckItem("Head size", CheckLevel.FAIL, "$measured — too large (needs $need). Step back.")
        }
        val topMargin = crownY / h
        if (topMargin < 0.02f) checks += CheckItem("Top of head", CheckLevel.WARN, "The top of the hair is very close to the edge. Leave a little space above the head.")

        // Centring
        val offset = abs(box.exactCenterX() - w / 2f) / w
        checks += when {
            offset < 0.04f -> CheckItem("Centred", CheckLevel.PASS, "Face is centred.")
            offset < 0.09f -> CheckItem("Centred", CheckLevel.WARN, "Face is slightly off-centre.")
            else -> CheckItem("Centred", CheckLevel.FAIL, "Face is off-centre. Retake facing the camera straight on.")
        }

        // Eye line (US template: 28–35 mm from the bottom of a 51 mm photo)
        if (size.key == "doc_us") {
            val eyes = listOfNotNull(face.getLandmark(FaceLandmark.LEFT_EYE), face.getLandmark(FaceLandmark.RIGHT_EYE))
            if (eyes.size == 2) {
                val eyeFromBottom = (h - eyes.map { it.position.y }.average().toFloat()) / h * 51f
                checks += if (eyeFromBottom in 28f..35f) CheckItem("Eye height", CheckLevel.PASS, "%.0f mm from the bottom (needs 28–35 mm).".format(eyeFromBottom))
                else CheckItem("Eye height", CheckLevel.WARN, "%.0f mm from the bottom (needs 28–35 mm).".format(eyeFromBottom))
            }
        }
    }

    // ---- Pose ----
    val roll = abs(face.headEulerAngleZ)
    val yaw = abs(face.headEulerAngleY)
    val pitch = abs(face.headEulerAngleX)
    checks += when {
        roll < 5f && yaw < 8f && pitch < 10f -> CheckItem("Head straight", CheckLevel.PASS, "Facing the camera, not tilted.")
        roll < 9f && yaw < 14f && pitch < 16f -> CheckItem("Head straight", CheckLevel.WARN, buildString {
            append("Slightly ")
            append(listOfNotNull(if (roll >= 5f) "tilted" else null, if (yaw >= 8f) "turned" else null, if (pitch >= 10f) "looking up/down" else null).joinToString(", "))
            append(". Ask the customer to face the camera squarely.")
        })
        else -> CheckItem("Head straight", CheckLevel.FAIL, "Head is tilted or turned. Retake facing the camera squarely.")
    }

    // ---- Eyes & expression ----
    val left = face.leftEyeOpenProbability
    val right = face.rightEyeOpenProbability
    if (left != null && right != null) {
        val lowest = min(left, right)
        checks += when {
            lowest > 0.6f -> CheckItem("Eyes open", CheckLevel.PASS, "Both eyes open.")
            lowest > 0.3f -> CheckItem("Eyes open", CheckLevel.WARN, "Eyes may be half closed.")
            else -> CheckItem("Eyes open", CheckLevel.FAIL, "Eyes look closed. Retake.")
        }
    }
    face.smilingProbability?.let { smile ->
        checks += when {
            smile < 0.4f -> CheckItem("Neutral expression", CheckLevel.PASS, "Neutral expression.")
            smile < 0.75f -> CheckItem("Neutral expression", CheckLevel.WARN, "Slight smile. Most passports need a neutral expression with the mouth closed.")
            else -> CheckItem("Neutral expression", CheckLevel.FAIL, "Smiling. Most passports need a neutral expression with the mouth closed.")
        }
    }

    // ---- Lighting (measured on the photo before the background change) ----
    val pixels = IntArray(before.width * before.height)
    before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
    val bw = before.width
    val bh = before.height
    val sx = bw.toFloat() / w
    val sy = bh.toFloat() / h
    val inner = Rect(
        ((box.left + box.width() * 0.18f) * sx).toInt().coerceIn(0, bw - 1),
        ((box.top + box.height() * 0.25f) * sy).toInt().coerceIn(0, bh - 1),
        ((box.right - box.width() * 0.18f) * sx).toInt().coerceIn(1, bw),
        ((box.bottom - box.height() * 0.12f) * sy).toInt().coerceIn(1, bh)
    )
    if (inner.width() > 4 && inner.height() > 4) {
        var leftSum = 0f; var leftN = 0
        var rightSum = 0f; var rightN = 0
        val mid = inner.centerX()
        for (y in inner.top until inner.bottom step 2) for (x in inner.left until inner.right step 2) {
            val l = luminance(pixels[y * bw + x])
            if (x < mid) { leftSum += l; leftN++ } else { rightSum += l; rightN++ }
        }
        val l = leftSum / max(1, leftN)
        val r = rightSum / max(1, rightN)
        val mean = (l + r) / 2f
        val imbalance = abs(l - r) / max(0.01f, max(l, r))
        checks += when {
            imbalance < 0.15f -> CheckItem("Even lighting", CheckLevel.PASS, "Light is even across the face.")
            imbalance < 0.28f -> CheckItem("Even lighting", CheckLevel.WARN, "One side of the face is darker. Turn the customer towards the light.")
            else -> CheckItem("Even lighting", CheckLevel.FAIL, "Strong shadow on one side of the face. Use even, front-facing light.")
        }
        checks += when {
            mean < 0.25f -> CheckItem("Exposure", CheckLevel.FAIL, "Face is too dark. Add light or move to a brighter spot.")
            mean < 0.35f -> CheckItem("Exposure", CheckLevel.WARN, "Face is a little dark. Brightness can be raised before sending.")
            mean > 0.9f -> CheckItem("Exposure", CheckLevel.WARN, "Face looks overexposed (too bright).")
            else -> CheckItem("Exposure", CheckLevel.PASS, "Face brightness is good.")
        }

        // Sharpness: variance of the Laplacian on the face area.
        var sum = 0.0; var sumSq = 0.0; var count = 0
        val step = max(1, inner.width() / 220)
        for (y in inner.top + step until inner.bottom - step step step) for (x in inner.left + step until inner.right - step step step) {
            val c = luminance(pixels[y * bw + x])
            val lap = 4 * c - luminance(pixels[y * bw + x - step]) - luminance(pixels[y * bw + x + step]) -
                luminance(pixels[(y - step) * bw + x]) - luminance(pixels[(y + step) * bw + x])
            sum += lap; sumSq += lap * lap; count++
        }
        if (count > 50) {
            val variance = sumSq / count - (sum / count) * (sum / count)
            checks += if (variance < 0.0006) CheckItem("Sharpness", CheckLevel.WARN, "The face may be slightly blurred. Hold the camera steady and retake if needed.")
            else CheckItem("Sharpness", CheckLevel.PASS, "Face is in focus.")
        }
    }

    // ---- Glasses glare: bright specular spots around the eyes ----
    val eyePoints = listOfNotNull(face.getLandmark(FaceLandmark.LEFT_EYE), face.getLandmark(FaceLandmark.RIGHT_EYE))
    if (eyePoints.isNotEmpty()) {
        val radius = (box.width() * 0.13f * sx).toInt().coerceAtLeast(3)
        var bright = 0; var total = 0
        eyePoints.forEach { lm ->
            val cx = (lm.position.x * sx).toInt(); val cy = (lm.position.y * sy).toInt()
            for (y in (cy - radius).coerceAtLeast(0) until (cy + radius).coerceAtMost(bh)) for (x in (cx - radius).coerceAtLeast(0) until (cx + radius).coerceAtMost(bw)) {
                val p = pixels[y * bw + x]
                val spread = max(Pixel.red(p), max(Pixel.green(p), Pixel.blue(p))) - min(Pixel.red(p), min(Pixel.green(p), Pixel.blue(p)))
                if (luminance(p) > 0.94f && spread < 25) bright++
                total++
            }
        }
        val share = bright.toFloat() / max(1, total)
        checks += if (share > 0.035f) CheckItem("Glare / reflections", CheckLevel.WARN, "Possible glare near the eyes (glasses or flash). Many documents don't allow glasses — ask the customer to remove them.")
        else CheckItem("Glare / reflections", CheckLevel.PASS, "No glare found near the eyes.")
    }

    // ---- Background ----
    if (backgroundReplaced) {
        val expected = size.background
        checks += if (expected != null && expected != backgroundKey && !(expected == "lightgrey" && backgroundKey == "cream"))
            CheckItem("Background", CheckLevel.WARN, "${size.label} photos normally use a ${expected.replace("lightgrey", "light grey")} background.")
        else CheckItem("Background", CheckLevel.PASS, "Plain background applied.")
    } else {
        // Sample the top band and side strips away from the face.
        var sumL = 0f; var sumSq = 0f; var n = 0
        val faceLeft = (box.left * sx).toInt(); val faceRight = (box.right * sx).toInt()
        for (y in 0 until (bh * 0.12f).toInt() step 3) for (x in 0 until bw step 3) {
            if (x in (faceLeft - bw / 10)..(faceRight + bw / 10) && y > bh * 0.06f) continue
            val l = luminance(pixels[y * bw + x]); sumL += l; sumSq += l * l; n++
        }
        if (n > 20) {
            val mean = sumL / n
            val std = kotlin.math.sqrt(max(0f, sumSq / n - mean * mean))
            checks += when {
                std > 0.1f -> CheckItem("Background", CheckLevel.FAIL, "Background is not plain. Use a background colour to replace it.")
                mean < 0.6f -> CheckItem("Background", CheckLevel.WARN, "Background looks dark. Most documents need a light background.")
                std > 0.06f -> CheckItem("Background", CheckLevel.WARN, "Background has some marks or shadows.")
                else -> CheckItem("Background", CheckLevel.PASS, "Background is plain and light.")
            }
        }
    }
    return checks.sortedBy { when (it.level) { CheckLevel.FAIL -> 0; CheckLevel.WARN -> 1; CheckLevel.PASS -> 2 } }
}

// ---------------------------------------------------------------- UI

private val PassColour = Color(0xFF2FB67C)
private val WarnColour = Color(0xFFE0A020)
private val FailColour = Color(0xFFE0524B)

@Composable
internal fun PhotoCheckCard(checks: List<CheckItem>?, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val fails = checks?.count { it.level == CheckLevel.FAIL } ?: 0
    val warns = checks?.count { it.level == CheckLevel.WARN } ?: 0
    val passes = checks?.count { it.level == CheckLevel.PASS } ?: 0
    val tone = when { checks == null -> Muted; fails > 0 -> FailColour; warns > 0 -> WarnColour; else -> PassColour }
    Surface(
        onClick = { if (checks != null) expanded = !expanded },
        color = tone.copy(alpha = .12f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checks == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BrandAccent)
                else Icon(
                    when { fails > 0 -> Icons.Default.ErrorOutline; warns > 0 -> Icons.Default.WarningAmber; else -> Icons.Default.Verified },
                    contentDescription = null, tint = tone, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when { checks == null -> "Checking photo…"; fails > 0 -> "Photo check: needs attention"; warns > 0 -> "Photo check: nearly there"; else -> "Photo check: all good" },
                        color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (checks != null) Text("$passes passed · $warns warning${if (warns == 1) "" else "s"} · $fails problem${if (fails == 1) "" else "s"}", color = Muted, fontSize = 11.sp)
                }
                if (checks != null) Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Hide details" else "Show details", tint = Muted)
            }
            if (expanded && checks != null) {
                checks.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            when (item.level) { CheckLevel.PASS -> Icons.Default.CheckCircle; CheckLevel.WARN -> Icons.Default.WarningAmber; CheckLevel.FAIL -> Icons.Default.Cancel },
                            contentDescription = null,
                            tint = when (item.level) { CheckLevel.PASS -> PassColour; CheckLevel.WARN -> WarnColour; CheckLevel.FAIL -> FailColour },
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(item.title, color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
                Text("Checks are guidance only. The issuing office makes the final decision.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}
