package gh.com.bigsammy.passportpro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.floor
import kotlin.math.min

/*
 * Print straight from capture (pro feature): lays copies of the finished photo on
 * 4 × 6 in or A4 at true physical size, with optional cut guides, then prints through
 * Android's print service or shares the PDF.
 */

internal data class PrintPaper(val key: String, val label: String, val widthMm: Float, val heightMm: Float, val marginMm: Float, val media: PrintAttributes.MediaSize)

internal val printPapers = listOf(
    PrintPaper("4x6", "4 × 6 in", 101.6f, 152.4f, 4f, PrintAttributes.MediaSize.NA_INDEX_4X6),
    PrintPaper("a4", "A4", 210f, 297f, 8f, PrintAttributes.MediaSize.ISO_A4)
)

internal data class SheetLayout(val columns: Int, val rows: Int, val slots: List<RectF>)

/** Positions of each photo on the page, in millimetres. */
internal fun layoutSheet(paper: PrintPaper, photoW: Float, photoH: Float, gapMm: Float = 2f): SheetLayout {
    val usableW = paper.widthMm - 2 * paper.marginMm
    val usableH = paper.heightMm - 2 * paper.marginMm
    val cols = floor((usableW + gapMm) / (photoW + gapMm)).toInt().coerceAtLeast(0)
    val rows = floor((usableH + gapMm) / (photoH + gapMm)).toInt().coerceAtLeast(0)
    val blockW = cols * photoW + (cols - 1).coerceAtLeast(0) * gapMm
    val blockH = rows * photoH + (rows - 1).coerceAtLeast(0) * gapMm
    val left = (paper.widthMm - blockW) / 2f
    val top = (paper.heightMm - blockH) / 2f
    val slots = mutableListOf<RectF>()
    for (r in 0 until rows) for (c in 0 until cols) {
        val x = left + c * (photoW + gapMm)
        val y = top + r * (photoH + gapMm)
        slots += RectF(x, y, x + photoW, y + photoH)
    }
    return SheetLayout(cols, rows, slots)
}

internal fun createPrintSheetPdf(context: Context, photo: Bitmap, paper: PrintPaper, photoW: Float, photoH: Float, copies: Int, cutGuides: Boolean): File {
    val pt = 72f / 25.4f
    val layout = layoutSheet(paper, photoW, photoH)
    val pdf = PdfDocument()
    val page = pdf.startPage(PdfDocument.PageInfo.Builder((paper.widthMm * pt).toInt(), (paper.heightMm * pt).toInt(), 1).create())
    val canvas = page.canvas
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.3f; color = android.graphics.Color.rgb(170, 170, 170) }
    layout.slots.take(copies).forEach { slot ->
        val rect = RectF(slot.left * pt, slot.top * pt, slot.right * pt, slot.bottom * pt)
        canvas.drawBitmap(photo, null, rect, imagePaint)
        if (cutGuides) canvas.drawRect(rect, guidePaint)
    }
    pdf.finishPage(page)
    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PassportPro/Prints").apply { mkdirs() }
    val file = File(dir, "PassportPro_Sheet_${System.currentTimeMillis()}.pdf")
    FileOutputStream(file).use { pdf.writeTo(it) }
    pdf.close()
    return file
}

private class PdfFilePrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(),
            true
        )
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            if (cancellationSignal.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.localizedMessage)
        }
    }
}

internal fun printPdf(context: Context, file: File, paper: PrintPaper) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val attributes = PrintAttributes.Builder()
        .setMediaSize(paper.media)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    manager.print("PassportPro photo sheet", PdfFilePrintAdapter(file), attributes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintSheetSheet(photo: Bitmap, photoSize: DeliverySize, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val photoW = photoSize.widthMm ?: 35f
    val photoH = photoSize.heightMm ?: 45f
    var paperKey by remember { mutableStateOf(context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).getString("print_paper", "4x6") ?: "4x6") }
    val paper = printPapers.firstOrNull { it.key == paperKey } ?: printPapers.first()
    val layout = remember(paperKey, photoW, photoH) { layoutSheet(paper, photoW, photoH) }
    val capacity = layout.slots.size
    var copies by remember(paperKey) { mutableIntStateOf(capacity) }
    var cutGuides by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val thumb = remember(photo) { photo.asImageBitmap() }

    fun build(): File? = try {
        createPrintSheetPdf(context, photo, paper, photoW, photoH, copies.coerceIn(1, capacity), cutGuides)
    } catch (e: Exception) {
        message = "Could not create the sheet: ${e.localizedMessage ?: "please try again"}"
        null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Print photo sheet", color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${photoSize.label} · ${photoSize.detail} at true size", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                printPapers.forEach { option ->
                    FilterChip(selected = option.key == paperKey, onClick = {
                        paperKey = option.key
                        context.getSharedPreferences(QUICK_PREFS, Context.MODE_PRIVATE).edit().putString("print_paper", option.key).apply()
                    }, label = { Text(option.label) })
                }
            }

            // Preview of the page
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxHeight().aspectRatio(paper.widthMm / paper.heightMm)) {
                    val s = size.width / paper.widthMm
                    drawRect(Color.White)
                    drawRect(Color(0xFFD0D5DD), style = Stroke(1.dp.toPx()))
                    layout.slots.forEachIndexed { index, slot ->
                        val off = IntOffset((slot.left * s).toInt(), (slot.top * s).toInt())
                        val sz = IntSize((slot.width() * s).toInt().coerceAtLeast(1), (slot.height() * s).toInt().coerceAtLeast(1))
                        if (index < copies) drawImage(thumb, dstOffset = off, dstSize = sz)
                        else drawRect(Color(0xFFEAECF0), topLeft = Offset(off.x.toFloat(), off.y.toFloat()), size = Size(sz.width.toFloat(), sz.height.toFloat()))
                        if (cutGuides) drawRect(Color(0xFFAAAAAA), topLeft = Offset(off.x.toFloat(), off.y.toFloat()), size = Size(sz.width.toFloat(), sz.height.toFloat()), style = Stroke(0.6f))
                    }
                }
            }

            if (capacity == 0) {
                Text("This photo size does not fit on ${paper.label}. Choose the other paper.", color = DangerText, fontSize = 12.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copies = (copies - 1).coerceAtLeast(1) }, enabled = copies > 1) { Icon(Icons.Default.Remove, contentDescription = "Fewer copies", tint = PrimaryText) }
                    Text("$copies of $capacity", color = PrimaryText, fontSize = 14.sp, modifier = Modifier.widthIn(min = 56.dp))
                    IconButton(onClick = { copies = (copies + 1).coerceAtMost(capacity) }, enabled = copies < capacity) { Icon(Icons.Default.Add, contentDescription = "More copies", tint = PrimaryText) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cut guides", color = PrimaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = cutGuides, onCheckedChange = { cutGuides = it })
                }
                Text("In the print screen, choose the same paper and set scaling to 100% / actual size.", color = Muted, fontSize = 11.sp)
                ButtonPair {
                    StudioButton("Share PDF", icon = Icons.Default.Share, tone = ButtonTone.Secondary, compact = true, enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        build()?.let { file ->
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share photo sheet"))
                        }
                    })
                    StudioButton("Print", icon = Icons.Default.Print, busy = busy, modifier = Modifier.weight(1f), onClick = {
                        busy = true
                        build()?.let { file ->
                            runCatching { printPdf(context, file, paper) }
                                .onFailure { message = "Printing is not available: ${it.localizedMessage ?: "check your print service"}" }
                        }
                        busy = false
                    })
                }
            }
            message?.let { Text(it, color = DangerText, fontSize = 12.sp) }
        }
    }
}
