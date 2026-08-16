package com.insta.reelsoff.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// Ink on paper. Mirrors res/values/colors.xml — see the note there.
val Papier = Color(0xFFF4F1EA)
val Encre = Color(0xFF14100C)
val EncreDouce = Color(0xFF6B6257)
val Accent = Color(0xFF2E6F6A)
val Alerte = Color(0xFFB3341F)
val Filet = Color(0x2214100C)

/**
 * Locked to light. The direction is a printed page, and a dark variant would need
 * its own palette rather than an inversion of this one, so following the system
 * theme here would only produce a washed-out version of neither.
 */
private val colors = lightColorScheme(
    primary = Accent,
    onPrimary = Papier,
    secondary = Accent,
    onSecondary = Papier,
    background = Papier,
    onBackground = Encre,
    surface = Papier,
    onSurface = Encre,
    surfaceVariant = Papier,
    onSurfaceVariant = EncreDouce,
    outline = EncreDouce,
    outlineVariant = Filet,
    error = Alerte,
    onError = Papier,
    errorContainer = Papier,
    onErrorContainer = Encre,
)

/**
 * No font file is bundled and none is downloaded — the project forbids any network
 * call, which rules out downloadable fonts. The editorial feel therefore comes from
 * weight, size and letter spacing on the platform family alone.
 */
private val typography = Typography(
    // The two counters. Light and large: a big thin numeral reads as a printed
    // figure, where a bold one would read as a notification badge.
    displayLarge = TextStyle(
        fontSize = 64.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Section headings and the wordmark, always set in capitals by the caller.
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
    ),
)

// Near-square corners: rounded shapes are the main thing that makes Material look
// like Material, and a printed page has none.
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(3.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

@Composable
fun DigueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
