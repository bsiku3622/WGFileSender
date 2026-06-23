package com.jaewonbaek.wgfilesender.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** shadcn-flavored neutral palette (zinc) with a restrained indigo accent. */
object Shad {
    val background = Color(0xFFFFFFFF)
    val foreground = Color(0xFF09090B)
    val card = Color(0xFFFFFFFF)
    val muted = Color(0xFFF4F4F5)
    val mutedForeground = Color(0xFF71717A)
    val border = Color(0xFFE4E4E7)
    val primary = Color(0xFF18181B)
    val primaryForeground = Color(0xFFFAFAFA)
    val accent = Color(0xFF6366F1)
    val destructive = Color(0xFFEF4444)
    val success = Color(0xFF22C55E)

    val radiusSm = 6.dp
    val radiusMd = 8.dp
    val radiusLg = 12.dp
}

private val ShadTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun WgfsTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Shad.primary,
        onPrimary = Shad.primaryForeground,
        background = Shad.background,
        onBackground = Shad.foreground,
        surface = Shad.card,
        onSurface = Shad.foreground,
        surfaceVariant = Shad.muted,
        onSurfaceVariant = Shad.mutedForeground,
        outline = Shad.border,
        error = Shad.destructive
    )
    MaterialTheme(colorScheme = colors, typography = ShadTypography, content = content)
}
