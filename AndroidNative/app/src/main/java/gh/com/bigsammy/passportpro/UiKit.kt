package gh.com.bigsammy.passportpro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * One design system for the whole app: a complete colour scheme for light and dark
 * mode (so no Material default purple leaks into chips, switches, tabs or dialogs)
 * and a single button component with fixed heights, single-line labels and
 * consistent colours.
 */

/** Gold that stays readable as text and icons on both light and dark surfaces. */
internal val BrandAccent: Color @Composable get() = if (LocalPassportDark.current) Color(0xFFE4B45D) else Color(0xFF8A5A12)

/** Readable error/danger colour for messages. */
internal val DangerText: Color @Composable get() = if (LocalPassportDark.current) Color(0xFFFF8A80) else Color(0xFFB42318)

internal fun passportColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFFE4B45D),
    onPrimary = Color(0xFF101522),
    primaryContainer = Color(0xFF3A2E16),
    onPrimaryContainer = Color(0xFFF6DDA8),
    inversePrimary = Color(0xFF8A5A12),
    secondary = Color(0xFFC9A86A),
    onSecondary = Color(0xFF101522),
    secondaryContainer = Color(0xFF33405A),
    onSecondaryContainer = Color(0xFFF7F8FA),
    tertiary = Color(0xFF7FC8A9),
    onTertiary = Color(0xFF0B2A1E),
    tertiaryContainer = Color(0xFF1C4636),
    onTertiaryContainer = Color(0xFFCDEFE0),
    background = Color(0xFF101522),
    onBackground = Color(0xFFF7F8FA),
    surface = Color(0xFF1B2433),
    onSurface = Color(0xFFF7F8FA),
    surfaceVariant = Color(0xFF243045),
    onSurfaceVariant = Color(0xFF9AA7B8),
    surfaceTint = Color(0xFFE4B45D),
    inverseSurface = Color(0xFFF7F8FA),
    inverseOnSurface = Color(0xFF101522),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A5568),
    outlineVariant = Color(0xFF2E3A4D),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A3447),
    surfaceDim = Color(0xFF101522),
    surfaceContainerLowest = Color(0xFF0C111B),
    surfaceContainerLow = Color(0xFF161E2B),
    surfaceContainer = Color(0xFF1B2433),
    surfaceContainerHigh = Color(0xFF222C3D),
    surfaceContainerHighest = Color(0xFF2A3447)
) else lightColorScheme(
    primary = Color(0xFF9A6418),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8E7C6),
    onPrimaryContainer = Color(0xFF3A2506),
    inversePrimary = Color(0xFFE4B45D),
    secondary = Color(0xFF765016),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E9D8),
    onSecondaryContainer = Color(0xFF2C1D06),
    tertiary = Color(0xFF21865A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F2E4),
    onTertiaryContainer = Color(0xFF0B3B26),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF182230),
    surface = Color.White,
    onSurface = Color(0xFF182230),
    surfaceVariant = Color(0xFFE9EDF2),
    onSurfaceVariant = Color(0xFF667085),
    surfaceTint = Color(0xFF9A6418),
    inverseSurface = Color(0xFF182230),
    inverseOnSurface = Color(0xFFF5F6F8),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
    outline = Color(0xFF98A2B3),
    outlineVariant = Color(0xFFD0D5DD),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE4E7EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEAECF0),
    surfaceContainerHighest = Color(0xFFE4E7EC)
)

internal enum class ButtonTone { Primary, Secondary, Quiet, Danger, WhatsApp }

/**
 * The app's only button. Labels never wrap (they shorten with "…" if space runs out),
 * heights are fixed, and colours follow the tone in both light and dark mode.
 */
@Composable
internal fun StudioButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    busy: Boolean = false,
    compact: Boolean = false
) {
    val dark = LocalPassportDark.current
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val height = if (compact) 40.dp else 50.dp
    val padding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = 0.dp)
    val sized = modifier.heightIn(min = height)
    val content: @Composable RowScope.(Color) -> Unit = { tint ->
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 17.dp else 19.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        }
        Text(
            text,
            fontSize = if (compact) 13.sp else 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
    when (tone) {
        ButtonTone.Secondary -> {
            val fg = if (dark) Color(0xFFF7F8FA) else Color(0xFF182230)
            OutlinedButton(
                onClick = onClick, enabled = enabled && !busy, modifier = sized, shape = shape, contentPadding = padding,
                border = BorderStroke(1.dp, if (enabled) (if (dark) Color(0xFF4A5568) else Color(0xFFD0D5DD)) else (if (dark) Color(0xFF2E3A4D) else Color(0xFFE4E7EC))),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = fg, disabledContentColor = fg.copy(alpha = .38f))
            ) { content(fg) }
        }
        ButtonTone.Quiet -> {
            val fg = BrandAccent
            TextButton(
                onClick = onClick, enabled = enabled && !busy, modifier = sized, shape = shape, contentPadding = padding,
                colors = ButtonDefaults.textButtonColors(contentColor = fg, disabledContentColor = fg.copy(alpha = .38f))
            ) { content(fg) }
        }
        else -> {
            val (bg, fg) = when (tone) {
                ButtonTone.Danger -> Color(0xFFB93838) to Color.White
                ButtonTone.WhatsApp -> Color(0xFF25D366) to Color(0xFF06291A)
                else -> if (dark) Color(0xFFE4B45D) to Color(0xFF101522) else Color(0xFF9A6418) to Color.White
            }
            Button(
                onClick = onClick, enabled = enabled && !busy, modifier = sized, shape = shape, contentPadding = padding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bg, contentColor = fg,
                    disabledContainerColor = bg.copy(alpha = if (busy) .75f else .35f),
                    disabledContentColor = fg.copy(alpha = if (busy) .9f else .6f)
                )
            ) { content(fg) }
        }
    }
}

/** Small uppercase section heading used above groups of controls. */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = modifier)
}

/** Two buttons side by side, each taking half the width. */
@Composable
internal fun ButtonPair(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
