package ai.sonario.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Sonario desktop palette (from static/index.html)
object SonarioColors {
    val Abyss = Color(0xFF16222F)
    val Deep = Color(0xFF1B2A3A)      // page background
    val Panel = Color(0xFF26384C)     // card surface
    val Panel2 = Color(0xFF2B3E54)    // raised / input surface
    val Ink = Color(0xFFE8EEF4)
    val InkSoft = Color(0xFFB9C7D6)
    val Muted = Color(0xFF7E93A8)
    val Green = Color(0xFF5CC7A4)     // accent (the .ai green)
    val GreenDeep = Color(0xFF3AA978)
    val Teal = Color(0xFF5FB0C4)
    val RuleSoft = Color(0xFF2B3E54)
}

private val SonarioScheme = darkColorScheme(
    primary = SonarioColors.Green,
    onPrimary = SonarioColors.Abyss,
    secondary = SonarioColors.Teal,
    background = SonarioColors.Deep,
    onBackground = SonarioColors.Ink,
    surface = SonarioColors.Panel,
    onSurface = SonarioColors.Ink,
    surfaceVariant = SonarioColors.Panel2,
    onSurfaceVariant = SonarioColors.InkSoft,
    outline = SonarioColors.RuleSoft,
)

// Desktop pairs Spectral (display) with Inter (body). System serif/sans are the
// closest no-asset stand-ins; drop Spectral + Inter ttf into res/font and swap
// these FontFamily values to match the desktop exactly.
private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

private val SonarioType = Typography(
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 0.8.sp),
)

@Composable
fun SonarioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SonarioScheme,
        typography = SonarioType,
        content = content,
    )
}

/** Shared OutlinedTextField colors for Sonario's dark panels (used by the input
 *  card and the settings fields so they stay visually identical). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun sonarioFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedContainerColor = SonarioColors.Panel2,
    unfocusedContainerColor = SonarioColors.Panel2,
    focusedBorderColor = SonarioColors.Green,
    unfocusedBorderColor = SonarioColors.RuleSoft,
    cursorColor = SonarioColors.Green,
    focusedTextColor = SonarioColors.Ink,
    unfocusedTextColor = SonarioColors.Ink,
    focusedLabelColor = SonarioColors.InkSoft,
    unfocusedLabelColor = SonarioColors.Muted,
)
