package com.bigsammy.sammessagescheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigsammy.sammessagescheduler.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  Brand palette                                                      */
/* ------------------------------------------------------------------ */

object SamColors {
    val Indigo = Color(0xFF4338CA)
    val IndigoLight = Color(0xFFA5B4FC)
    val Navy = Color(0xFF171B29)
    val Teal = Color(0xFF0F9D8A)
    val TealLight = Color(0xFF5EEAD4)
    val Amber = Color(0xFFD97706)
    val Green = Color(0xFF16A34A)
    val Red = Color(0xFFDC2626)
    val Slate = Color(0xFF64748B)
    val WhatsApp = Color(0xFF25A244)
    val Social = Color(0xFFDB2777)
}

val SamLightColors = lightColorScheme(
    primary = SamColors.Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = SamColors.Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF053B34),
    tertiary = SamColors.Amber,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF0F7),
    onSurfaceVariant = Color(0xFF526077),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE9ECF5),
    outline = Color(0xFFB7BFCE),
    outlineVariant = Color(0xFFE2E6EF),
    error = SamColors.Red,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
)

val SamDarkColors = darkColorScheme(
    primary = SamColors.IndigoLight,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = SamColors.TealLight,
    onSecondary = Color(0xFF053B34),
    secondaryContainer = Color(0xFF134E48),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0D1018),
    onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF161A25),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF232836),
    onSurfaceVariant = Color(0xFFA9B1C4),
    surfaceContainer = Color(0xFF1B2030),
    surfaceContainerHigh = Color(0xFF232838),
    outline = Color(0xFF4B5367),
    outlineVariant = Color(0xFF2B3142),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF4C1515),
    onErrorContainer = Color(0xFFFEE2E2)
)

/** Hero gradient used on the dashboard and composer header. */
fun samHeroBrush(dark: Boolean): Brush = if (dark)
    Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF134E48)))
else
    Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF0F9D8A)))

/* ------------------------------------------------------------------ */
/*  Status & channel semantics                                          */
/* ------------------------------------------------------------------ */

data class StatusVisual(val label: String, val color: Color, val icon: ImageVector)

private val failedDelivery = listOf("SEND_FAILED", "DELIVERY_FAILED")

fun MessageEntity.needsAttention(): Boolean =
    status in listOf("FAILED", "PERMISSION_REQUIRED", "AWAITING_USER") || deliveryStatus in failedDelivery

fun MessageEntity.isFailed(): Boolean =
    status in listOf("FAILED", "PERMISSION_REQUIRED") || deliveryStatus in failedDelivery

fun statusVisual(message: MessageEntity): StatusVisual = when {
    message.deliveryStatus in failedDelivery -> StatusVisual("SMS failed", SamColors.Red, Icons.Default.ErrorOutline)
    message.status == "FAILED" -> StatusVisual("Failed", SamColors.Red, Icons.Default.ErrorOutline)
    message.status == "PERMISSION_REQUIRED" -> StatusVisual("Permission needed", SamColors.Red, Icons.Default.Lock)
    message.status == "AWAITING_USER" -> StatusVisual("Needs your tap", SamColors.Amber, Icons.Default.TouchApp)
    message.status == "PAUSED" -> StatusVisual("Paused", SamColors.Slate, Icons.Default.PauseCircle)
    message.status == "COMPLETED" -> StatusVisual("Done", SamColors.Green, Icons.Default.TaskAlt)
    message.deliveryStatus == "DELIVERED" -> StatusVisual("Delivered", SamColors.Green, Icons.Default.DoneAll)
    message.status == "SENT" -> StatusVisual("Sent", SamColors.Teal, Icons.Default.Done)
    message.status == "SCHEDULED" && message.triggerType == "LOCATION" -> StatusVisual("On arrival", SamColors.Indigo, Icons.Default.Place)
    message.status == "SCHEDULED" -> StatusVisual("Scheduled", SamColors.Indigo, Icons.Default.Schedule)
    else -> StatusVisual(message.status.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, SamColors.Slate, Icons.Default.Info)
}

fun channelColor(channel: String): Color = when (channel) {
    "SMS" -> SamColors.Indigo
    "WhatsApp" -> SamColors.WhatsApp
    else -> SamColors.Social
}

fun channelIcon(channel: String): ImageVector = when (channel) {
    "SMS" -> Icons.Default.Sms
    "WhatsApp" -> Icons.Default.Chat
    else -> Icons.Default.Campaign
}

fun repeatLabel(rule: String): String = when (rule) {
    "DAILY" -> "Daily"
    "WEEKLY" -> "Weekly"
    "MONTHLY" -> "Monthly"
    "YEARLY" -> "Yearly"
    else -> ""
}

/* ------------------------------------------------------------------ */
/*  Friendly time formatting                                            */
/* ------------------------------------------------------------------ */

private fun dayDelta(timestamp: Long): Int {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val target = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return ((target.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
}

/** "Today, 08:00", "Tomorrow, 08:00", "Sat 28 Nov, 08:00" or "28 Nov 2027, 08:00". */
fun friendlyDateTime(timestamp: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    val sameYear = Calendar.getInstance().get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
    return when (val delta = dayDelta(timestamp)) {
        0 -> "Today, $time"
        1 -> "Tomorrow, $time"
        -1 -> "Yesterday, $time"
        in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestamp)) + ", $time"
        else -> if (sameYear && delta > -300)
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(timestamp)) + ", $time"
        else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp)) + ", $time"
    }
}

/** Day heading for grouped lists: "Today", "Tomorrow" or "Saturday, 28 November 2026". */
fun dayHeading(timestamp: Long): String = when (dayDelta(timestamp)) {
    0 -> "Today"
    1 -> "Tomorrow"
    -1 -> "Yesterday"
    else -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
}

/** Short countdown such as "in 45 min", "in 3 h 10 min", "in 4 days" or "2 h ago". */
fun countdown(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = timestamp - now
    val abs = kotlin.math.abs(diff)
    val minutes = abs / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    val core = when {
        minutes < 1 -> return if (diff >= 0) "any moment now" else "just now"
        minutes < 60 -> "$minutes min"
        hours < 24 -> if (minutes % 60 == 0L) "$hours h" else "$hours h ${minutes % 60} min"
        days < 14 -> if (days == 1L) "1 day" else "$days days"
        else -> "${days / 7} weeks"
    }
    return if (diff >= 0) "in $core" else "$core ago"
}

fun greetingForNow(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/* ------------------------------------------------------------------ */
/*  Reusable components                                                 */
/* ------------------------------------------------------------------ */

@Composable
fun ChannelAvatar(channel: String, size: Int = 42) {
    val tint = channelColor(channel)
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(channelIcon(channel), contentDescription = channel, tint = tint, modifier = Modifier.size((size * 0.52f).dp))
    }
}

@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(actionLabel) }
    }
}

@Composable
fun SamCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun SettingsSection(title: String, subtitle: String, icon: ImageVector, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable ColumnScope.() -> Unit) {
    SamCard(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}

/** Two-tone horizontal bar used for the channel mix on the dashboard. */
@Composable
fun ShareBar(label: String, count: Int, total: Int, color: Color) {
    val fraction = if (total <= 0) 0f else count.toFloat() / total.toFloat()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}
