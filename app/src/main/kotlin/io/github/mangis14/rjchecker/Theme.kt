package io.github.mangis14.rjchecker

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Zlta RegioJetu ako primarna farba, tmava "inkova" na text. Zlta je velmi
// svetla, takze na nej musi byt tmavy text - biela by bola necitatelna.
val RjYellow = Color(0xFFFFDD00)
val RjYellowDim = Color(0xFFE0C200)
val RjInk = Color(0xFF1D1D1B)
val RjInkSoft = Color(0xFF5A5A57)
val RjBgLight = Color(0xFFF6F6F3)
val RjBgDark = Color(0xFF141413)
val RjSurfaceDark = Color(0xFF211F1D)

// stavy miest - zelena/siva sa citaju rychlejsie ako odtiene zltej
val RjSeatFree = Color(0xFF1E8E3E)
val RjSeatTaken = Color(0xFFB9B9B4)
val RjSeatMine = Color(0xFFD32F2F)
val RjWarn = Color(0xFF9A5B00)

private val LightColors = lightColorScheme(
    primary = RjYellow,
    onPrimary = RjInk,
    primaryContainer = RjYellow,
    onPrimaryContainer = RjInk,
    secondary = RjInk,
    onSecondary = Color.White,
    background = RjBgLight,
    onBackground = RjInk,
    surface = Color.White,
    onSurface = RjInk,
    surfaceVariant = Color(0xFFEDEDE8),
    onSurfaceVariant = RjInkSoft,
    outline = Color(0xFFC9C9C2),
)

private val DarkColors = darkColorScheme(
    primary = RjYellow,
    onPrimary = RjInk,
    primaryContainer = RjYellowDim,
    onPrimaryContainer = RjInk,
    secondary = RjYellow,
    onSecondary = RjInk,
    background = RjBgDark,
    onBackground = Color(0xFFF2F2EE),
    surface = RjSurfaceDark,
    onSurface = Color(0xFFF2F2EE),
    surfaceVariant = Color(0xFF302D2A),
    onSurfaceVariant = Color(0xFFC9C7C1),
    outline = Color(0xFF4A4744),
)

private val RjTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun RjSeatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // stavova lista v zltej, takze ikony musia byt tmave
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(colorScheme = colors, typography = RjTypography, content = content)
}
