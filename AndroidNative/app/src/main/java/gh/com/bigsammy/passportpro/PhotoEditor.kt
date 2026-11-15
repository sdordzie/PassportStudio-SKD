package gh.com.bigsammy.passportpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as Pixel
import android.graphics.Bitmap.CompressFormat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.min

@Composable
internal fun PhotoEditorScreen(photoPath: String, onRetake: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val original = remember(photoPath) { BitmapFactory.decodeFile(photoPath)?.copy(Bitmap.Config.ARGB_8888, true) }
    var image by remember(photoPath) { mutableStateOf(original) }
    var background by remember { mutableIntStateOf(Pixel.WHITE) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var crop by remember { mutableStateOf("Original") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Original photo preserved • Editing is on-device") }

    fun adjust(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            fun c(v: Int) = (contrast * (v - 128) + 128 + brightness * 255).toInt().coerceIn(0, 255)
            val r = c(Pixel.red(p)); val g = c(Pixel.green(p)); val b = c(Pixel.blue(p))
            val grey = .299f * r + .587f * g + .114f * b
            pixels[i] = Pixel.rgb(
                (grey + (r - grey) * saturation).toInt().coerceIn(0,255),
                (grey + (g - grey) * saturation).toInt().coerceIn(0,255),
                (grey + (b - grey) * saturation).toInt().coerceIn(0,255))
        }
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { it.setPixels(pixels,0,w,0,0,w,h) }
    }

    fun cropBitmap(src: Bitmap): Bitmap {
        val ratio = when(crop) { "35 × 45 mm" -> 35f/45f; "Square" -> 1f; "4 × 6" -> 2f/3f; else -> src.width.toFloat()/src.height }
        val sr = src.width.toFloat()/src.height
        val w: Int; val h: Int
        if(sr > ratio) { h=src.height; w=(h*ratio).toInt() } else { w=src.width; h=(w/ratio).toInt() }
        return Bitmap.createBitmap(src,(src.width-w)/2,(src.height-h)/2,w,h)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("PHASE 3 • PHOTO EDITOR", color=Color(0xFFE4B45D), fontSize=11.sp, fontWeight=FontWeight.Bold)
        Text("Professional photo editing", color=Color.White, fontSize=21.sp, fontWeight=FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF090D14), RoundedCornerShape(14.dp)), contentAlignment=Alignment.Center) {
            image?.let { Image(it.asImageBitmap(), "Passport photo preview", Modifier.fillMaxSize().padding(6.dp), contentScale=ContentScale.Fit) }
        }
        Text("BACKGROUND REPLACEMENT", color=Color(0xFF9AA7B8), fontSize=10.sp, fontWeight=FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("White" to Pixel.WHITE, "Blue" to Pixel.rgb(35,91,170), "Red" to Pixel.rgb(190,35,48), "Grey" to Pixel.LTGRAY).forEach { (name, color) ->
                FilterChip(selected=background==color, onClick={background=color}, label={Text(name)})
            }
        }
        Button(onClick={
            val src=original
            if(src!=null) {
                busy=true; status="AI background removal running locally…"
                val detector=Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
                detector.process(InputImage.fromBitmap(src,0))
                    .addOnSuccessListener { mask ->
                        try {
                            val buf=mask.buffer.order(ByteOrder.nativeOrder()); buf.rewind()
                            val mw=mask.width; val mh=mask.height
                            val input=IntArray(src.width*src.height); src.getPixels(input,0,src.width,0,0,src.width,src.height)
                            val output=IntArray(input.size)
                            for(y in 0 until src.height) for(x in 0 until src.width) {
                                val confidence=buf.getFloat((min(mh-1,y*mh/src.height)*mw+min(mw-1,x*mw/src.width))*4).coerceIn(0f,1f)
                                val p=input[y*src.width+x]
                                val r=(Pixel.red(p)*confidence+Pixel.red(background)*(1-confidence)).toInt()
                                val g=(Pixel.green(p)*confidence+Pixel.green(background)*(1-confidence)).toInt()
                                val b=(Pixel.blue(p)*confidence+Pixel.blue(background)*(1-confidence)).toInt()
                                output[y*src.width+x]=Pixel.rgb(r,g,b)
                            }
                            image=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888).also { it.setPixels(output,0,src.width,0,0,src.width,src.height) }
                            status="Background replaced with selected colour."
                        } catch(e:Exception) { status="Could not process image: ${e.localizedMessage}" }
                        finally { busy=false; detector.close() }
                    }.addOnFailureListener { e -> status="AI segmentation failed: ${e.localizedMessage}"; busy=false; detector.close() }
            }
        }, enabled=!busy && original!=null, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))) {
            if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("REMOVE BACKGROUND WITH AI",fontWeight=FontWeight.Bold)
        }
        Text("CROP PRESET", color=Color(0xFF9AA7B8), fontSize=10.sp, fontWeight=FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("Original","35 × 45 mm","Square","4 × 6").forEach { v ->
                FilterChip(selected=crop==v,onClick={crop=v; original?.let { image=cropBitmap(it) }},label={Text(v,fontSize=10.sp)})
            }
        }
        Text("Brightness ${(brightness*100).toInt()}%", color=Color(0xFF9AA7B8),fontSize=10.sp)
        Slider(value=brightness,onValueChange={brightness=it;original?.let { image=adjust(it) }},valueRange=-.35f.. .35f)
        Text("Contrast ${(contrast*100).toInt()}%",color=Color(0xFF9AA7B8),fontSize=10.sp)
        Slider(value=contrast,onValueChange={contrast=it;original?.let { image=adjust(it) }},valueRange=.5f..1.8f)
        Text("Saturation ${(saturation*100).toInt()}%",color=Color(0xFF9AA7B8),fontSize=10.sp)
        Slider(value=saturation,onValueChange={saturation=it;original?.let { image=adjust(it) }},valueRange=0f..2f)
        Text(status,color=Color(0xFF9AA7B8),fontSize=10.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onRetake,modifier=Modifier.weight(1f)){Text("Retake")}
            OutlinedButton(onClick={image=original;brightness=0f;contrast=1f;saturation=1f;crop="Original";status="Edits reset"},modifier=Modifier.weight(1f)){Text("Reset")}
            Button(onClick={
                try {
                    val result=image ?: return@Button
                    val finalImage=adjust(cropBitmap(result))
                    val dir=File(context.getExternalFilesDir(null),"PassportPro/Edited").apply{mkdirs()}
                    val name="PassportPro_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.jpg"
                    FileOutputStream(File(dir,name)).use { finalImage.compress(CompressFormat.JPEG,95,it) }
                    onSaved()
                } catch(e:Exception) { status="Save failed: ${e.localizedMessage}" }
            },enabled=image!=null,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE4B45D),contentColor=Color(0xFF101522))){Text("Save",fontWeight=FontWeight.Bold)}
        }
    }
}
