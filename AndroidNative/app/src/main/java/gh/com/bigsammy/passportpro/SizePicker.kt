package gh.com.bigsammy.passportpro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lets the operator set the photo size *before* taking the picture. The choice is
 * remembered, drives the camera frame, and is applied automatically after capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SizePickerSheet(
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sizes = remember { deliverySizes(context) }
    val existing = remember { readCustomSize(context) }
    fun clean(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    var width by remember { mutableStateOf(existing?.let { clean(it.width) } ?: "35") }
    var height by remember { mutableStateOf(existing?.let { clean(it.height) } ?: "45") }
    var unit by remember { mutableStateOf(existing?.unit ?: "mm") }
    var dpi by remember { mutableIntStateOf(existing?.dpi?.takeIf { it == 300 || it == 600 } ?: 600) }
    var error by remember { mutableStateOf<String?>(null) }

    val draft = run {
        val w = width.toFloatOrNull()
        val h = height.toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) CustomSizeSpec(w, h, unit, if (unit == "px") 300 else dpi) else null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Photo size", color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Choose before you take the photo. The camera frame matches this size and every photo is cropped to it automatically.",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(2.dp))
            fun choose(option: DeliverySize) {
                writeSelectedSize(context, option.key)
                // Documents expect a particular background colour; pre-select it.
                option.background?.let { bg ->
                    context.getSharedPreferences(QUICK_PREFS, android.content.Context.MODE_PRIVATE).edit().putString("background", bg).apply()
                }
                onSelect(option.key)
                onDismiss()
            }
            sizes.filterNot { it.fromLibrary }.forEach { option ->
                SizeRow(option, selected = option.key == selectedKey) { choose(option) }
            }
            val library = sizes.filter { it.fromLibrary }
            if (library.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionHeading("Document library")
                library.forEach { option -> SizeRow(option, selected = option.key == selectedKey) { choose(option) } }
                Text("Based on published requirements. Always confirm with the issuing office.", color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            SectionHeading("Custom size")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = width, onValueChange = { width = it.filter { c -> c.isDigit() || c == '.' }; error = null },
                    label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = studioOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = height, onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' }; error = null },
                    label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = studioOutlinedTextFieldColors()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("mm", "cm", "in", "px").forEach { value ->
                    FilterChip(selected = unit == value, onClick = { unit = value }, label = { Text(value) })
                }
            }
            if (unit != "px") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Print quality", color = Muted, fontSize = 12.sp)
                    FilterChip(selected = dpi == 300, onClick = { dpi = 300 }, label = { Text("300 DPI") })
                    FilterChip(selected = dpi == 600, onClick = { dpi = 600 }, label = { Text("600 DPI") })
                }
            }
            draft?.let {
                val (pw, ph) = it.pixels()
                Text("Saved image: $pw × $ph pixels", color = Muted, fontSize = 12.sp)
            }
            error?.let { Text(it, color = DangerText, fontSize = 12.sp) }
            StudioButton(
                "Use this size",
                icon = Icons.Default.Check,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val spec = draft
                    if (spec == null) {
                        error = "Enter a width and height greater than zero."
                    } else {
                        val ratio = spec.width / spec.height
                        if (ratio < 0.25f || ratio > 4f) {
                            error = "That shape is too narrow for a photo. Check the width and height."
                        } else {
                            writeCustomSize(context, spec)
                            writeSelectedSize(context, "custom")
                            onSelect("custom")
                            onDismiss()
                        }
                    }
                }
            )
            Text("The custom size is also used as the Photo Editor's default.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SizeRow(option: DeliverySize, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) BrandAccent.copy(alpha = .12f) else Ink,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) BrandAccent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Crop, contentDescription = null, tint = if (selected) BrandAccent else Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(option.label, color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(option.detail, color = Muted, fontSize = 12.sp)
                if (option.note.isNotEmpty()) Text(option.note, color = Muted, fontSize = 11.sp)
            }
            if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = BrandAccent, modifier = Modifier.size(22.dp))
        }
    }
}
