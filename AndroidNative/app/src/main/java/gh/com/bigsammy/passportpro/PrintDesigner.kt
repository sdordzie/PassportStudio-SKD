package gh.com.bigsammy.passportpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor
import kotlin.math.min

private data class PhotoPreset(val label: String, val width: Int, val height: Int, val unit: String)
private val photoPresets = listOf(
    PhotoPreset("35 × 45 mm", 35, 45, "mm"),
    PhotoPreset("200 × 150 px", 200, 150, "px"),
    PhotoPreset("150 × 200 px", 150, 200, "px"),
    PhotoPreset("2 × 2 inch", 2, 2, "in"),
    PhotoPreset("4 × 6 inch", 2, 3, "ratio")
)

@Composable
internal fun PrintDesignerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(photoPresets[0]) }
    var paper by remember { mutableStateOf("A4") }
    var copies by remember { mutableIntStateOf(8) }
    var gapMm by remember { mutableIntStateOf(3) }
    var marginMm by remember { mutableIntStateOf(8) }
    var dpi by remember { mutableIntStateOf(300) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoName by remember { mutableStateOf("No photograph selected") }
    var status by remember { mutableStateOf("Choose a photo to prepare your print sheet.") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    photo = BitmapFactory.decodeStream(stream)?.copy(Bitmap.Config.ARGB_8888, false)
                }
                photoName = "Selected photograph"
                status = if (photo != null) "Photograph ready for layout." else "Could not read this image."
            } catch (e: Exception) { status = "Image error: ${e.localizedMessage}" }
        }
    }

    fun dimensionPx(p: PhotoPreset, axis: Int): Int = when (p.unit) {
        "px" -> if (axis == 0) p.width else p.height
        "mm" -> ((if (axis == 0) p.width else p.height) / 25.4f * dpi).toInt()
        "in" -> ((if (axis == 0) p.width else p.height) * dpi)
        else -> ((if (axis == 0) 2f else 3f) * dpi).toInt()
    }

    fun createPdf(source: Bitmap): File {
        val pageWidth = if (paper == "A4") 595 else 432
        val pageHeight = if (paper == "A4") 842 else 288
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(android.graphics.Color.WHITE)
        val pxPerMm = 72f / 25.4f
        val margin = marginMm * pxPerMm
        val gap = gapMm * pxPerMm
        val targetW: Float
        val targetH: Float
        if (selectedPreset.unit == "mm") {
            targetW = selectedPreset.width * pxPerMm
            targetH = selectedPreset.height * pxPerMm
        } else if (selectedPreset.unit == "px") {
            targetW = selectedPreset.width * 72f / dpi
            targetH = selectedPreset.height * 72f / dpi
        } else if (selectedPreset.unit == "in") {
            targetW = selectedPreset.width * 72f
            targetH = selectedPreset.height * 72f
        } else {
            targetW = 2f * 72f
            targetH = 3f * 72f
        }
        val availableW = pageWidth - 2 * margin
        val availableH = pageHeight - 2 * margin
        val cols = floor((availableW + gap) / (targetW + gap)).toInt().coerceAtLeast(1)
        val rows = floor((availableH + gap) / (targetH + gap)).toInt().coerceAtLeast(1)
        val total = min(copies, cols * rows)
        val usedW = cols * targetW + (cols - 1) * gap
        val startX = (pageWidth - usedW) / 2f
        val startY = margin
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRatio = source.width.toFloat() / source.height
        val dstRatio = targetW / targetH
        val cropRect = if (srcRatio > dstRatio) {
            val w = (source.height * dstRatio).toInt()
            Rect((source.width - w) / 2, 0, (source.width + w) / 2, source.height)
        } else {
            val h = (source.width / dstRatio).toInt()
            Rect(0, (source.height - h) / 2, source.width, (source.height + h) / 2)
        }
        for (i in 0 until total) {
            val col = i % cols
            val row = i / cols
            val left = startX + col * (targetW + gap)
            val top = startY + row * (targetH + gap)
            canvas.drawBitmap(source, cropRect, RectF(left, top, left + targetW, top + targetH), paint)
        }
        pdf.finishPage(page)
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PassportPro/Prints").apply { mkdirs() }
        val file = File(dir, "PassportPro_Print_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PHASE 5 • PRINT DESIGNER", color=Color(0xFFE4B45D), fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.3.sp)
        Text("Print-ready photo sheets", color=Color.White, fontSize=24.sp, fontWeight=FontWeight.Bold)
        Text("Arrange passport and ID photographs on printable sheets. Works offline.", color=Color(0xFF9AA7B8), fontSize=12.sp)

        Surface(color=Color(0xFF1B2433), shape=RoundedCornerShape(16.dp), modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={picker.launch("image/*")}, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))) {
                    Icon(Icons.Default.AddPhotoAlternate,null); Spacer(Modifier.width(8.dp)); Text("CHOOSE PHOTOGRAPH",fontWeight=FontWeight.Bold)
                }
                Text(photoName,color=Color(0xFF9AA7B8),fontSize=12.sp)
                photo?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(190.dp),contentScale=ContentScale.Fit) }
            }
        }

        Text("PHOTO SIZE",color=Color(0xFF9AA7B8),fontSize=11.sp,fontWeight=FontWeight.Bold)
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            photoPresets.forEach { preset ->
                FilterChip(selected=selectedPreset==preset,onClick={selectedPreset=preset},label={Text(preset.label)},modifier=Modifier.fillMaxWidth())
            }
        }
        if (selectedPreset.unit == "px") {
            Text("Pixel dimensions are preserved as the intended output size at the selected print resolution.",color=Color(0xFF9AA7B8),fontSize=11.sp)
        }

        Text("PAPER SIZE",color=Color(0xFF9AA7B8),fontSize=11.sp,fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=paper=="A4",onClick={paper="A4"},label={Text("A4")})
            FilterChip(selected=paper=="4×6",onClick={paper="4×6"},label={Text("4 × 6 inch")})
        }
        Text("COPIES: $copies",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
        Slider(value=copies.toFloat(),onValueChange={copies=it.toInt()},valueRange=1f..40f,steps=38)
        Text("GAP: $gapMm mm",color=Color.White,fontSize=12.sp)
        Slider(value=gapMm.toFloat(),onValueChange={gapMm=it.toInt()},valueRange=0f..10f,steps=9)
        Text("PAGE MARGIN: $marginMm mm",color=Color.White,fontSize=12.sp)
        Slider(value=marginMm.toFloat(),onValueChange={marginMm=it.toInt()},valueRange=0f..20f,steps=19)
        Text("PRINT RESOLUTION: $dpi DPI",color=Color.White,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(150,300,600).forEach { value -> FilterChip(selected=dpi==value,onClick={dpi=value},label={Text("$value DPI")}) }
        }

        val previewSource = photo
        val pageW = if(paper=="A4") 210f else 152.4f
        val pageH = if(paper=="A4") 297f else 101.6f
        val photoWmm = when(selectedPreset.unit) { "mm" -> selectedPreset.width.toFloat(); "px" -> selectedPreset.width*25.4f/dpi; "in" -> selectedPreset.width*25.4f; else -> 50.8f }
        val photoHmm = when(selectedPreset.unit) { "mm" -> selectedPreset.height.toFloat(); "px" -> selectedPreset.height*25.4f/dpi; "in" -> selectedPreset.height*25.4f; else -> 76.2f }
        val cols = floor((pageW-2*marginMm+gapMm)/(photoWmm+gapMm)).toInt().coerceAtLeast(0)
        val rows = floor((pageH-2*marginMm+gapMm)/(photoHmm+gapMm)).toInt().coerceAtLeast(0)
        val count = min(copies,cols*rows)
        Surface(color=Color(0xFF1B2433),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("LAYOUT SUMMARY",color=Color(0xFFE4B45D),fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text("$cols columns × $rows rows • $count photo(s) on one sheet",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                Text("Selected size: ${selectedPreset.label} • Paper: $paper • $dpi DPI",color=Color(0xFF9AA7B8),fontSize=11.sp)
                Text("The PDF uses physical page dimensions. Printer margins and borderless capability depend on the printer.",color=Color(0xFF9AA7B8),fontSize=10.sp)
            }
        }
        Button(onClick={
            val src=previewSource
            if(src==null) status="Choose a photograph first."
            else {
                busy=true
                try {
                    val file=createPdf(src)
                    val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                    val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type="application/pdf"; putExtra(android.content.Intent.EXTRA_STREAM,uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent,"Share or print PDF"))
                    status="PDF created: ${file.name}"
                } catch(e:Exception) { status="PDF export failed: ${e.localizedMessage ?: "unknown error"}" }
                finally { busy=false }
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))) {
            Icon(Icons.Default.PictureAsPdf,null); Spacer(Modifier.width(8.dp)); Text(if(busy) "CREATING PDF…" else "CREATE & SHARE PRINT PDF",fontWeight=FontWeight.Bold)
        }
        Text(status,color=Color(0xFF9AA7B8),fontSize=11.sp)
    }
}
