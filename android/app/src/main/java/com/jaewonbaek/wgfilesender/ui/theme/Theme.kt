package com.jaewonbaek.wgfilesender.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * shadcn-flavored neutral palette (zinc) that follows the system light/dark theme.
 * Colors are @Composable getters so call sites stay `Shad.foreground` etc.
 */
object Shad {
    @Composable private fun pick(light: Long, dark: Long): Color =
        if (isSystemInDarkTheme()) Color(dark) else Color(light)

    val background: Color @Composable get() = pick(0xFFFFFFFF, 0xFF09090B)
    val foreground: Color @Composable get() = pick(0xFF09090B, 0xFFFAFAFA)
    val card: Color @Composable get() = pick(0xFFFFFFFF, 0xFF18181B)
    val muted: Color @Composable get() = pick(0xFFF4F4F5, 0xFF27272A)
    val mutedForeground: Color @Composable get() = pick(0xFF71717A, 0xFFA1A1AA)
    val border: Color @Composable get() = pick(0xFFE4E4E7, 0xFF2E2E33)
    val primary: Color @Composable get() = pick(0xFF18181B, 0xFFFAFAFA)
    val primaryForeground: Color @Composable get() = pick(0xFFFAFAFA, 0xFF18181B)

    val accent: Color @Composable get() = Color(0xFF6366F1)
    val destructive: Color @Composable get() = Color(0xFFEF4444)
    val received: Color @Composable get() = Color(0xFF22C55E)   // green — incoming
    val sent: Color @Composable get() = Color(0xFFF97316)       // orange — outgoing

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
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFFAFAFA), onPrimary = Color(0xFF18181B),
            background = Color(0xFF09090B), onBackground = Color(0xFFFAFAFA),
            surface = Color(0xFF18181B), onSurface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA),
            outline = Color(0xFF2E2E33), error = Color(0xFFEF4444)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF18181B), onPrimary = Color(0xFFFAFAFA),
            background = Color(0xFFFFFFFF), onBackground = Color(0xFF09090B),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF09090B),
            surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Color(0xFF71717A),
            outline = Color(0xFFE4E4E7), error = Color(0xFFEF4444)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = ShadTypography, content = content)
}
