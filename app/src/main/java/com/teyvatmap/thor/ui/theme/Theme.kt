package com.teyvatmap.thor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Gold = Color(0xFFF4C95D)

private val NavyScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF3B3010),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF9CCAE8),
    onSecondary = Color(0xFF00344D),
    secondaryContainer = Color(0xFF1B2A4A),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFFB6D98C),
    background = Color(0xFF080D1A),
    onBackground = Color(0xFFEAEDF5),
    surface = Color(0xFF080D1A),
    onSurface = Color(0xFFEAEDF5),
    surfaceVariant = Color(0xFF16203A),
    onSurfaceVariant = Color(0xFFA9B2C7),
    surfaceContainerLowest = Color(0xFF050914),
    surfaceContainerLow = Color(0xFF0B1224),
    surfaceContainer = Color(0xFF0F182E),
    surfaceContainerHigh = Color(0xFF142039),
    surfaceContainerHighest = Color(0xFF1A2847),
    outline = Color(0xFF5A6684),
    outlineVariant = Color(0xFF26324F),
    error = Color(0xFFFF8A80),
)

private val Base = Typography()
val TeyvatTypography = Typography(
    titleLarge = Base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = Base.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
)

/** Look of everything that floats over the map: translucent deep navy, hairline border, rounded corners. */
object Chrome {
    val Surface = Color(0xE60B1224)
    val Panel = Color(0xF50B1224)
    val SurfaceSolid = Color(0xFF0F182E)
    val Border = Color(0xFF2A3757)
    val Active = Gold.copy(alpha = 0.18f)
    val Shape = RoundedCornerShape(18.dp)
    val ShapeSmall = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
}

@Composable
fun TeyvatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NavyScheme, typography = TeyvatTypography) {
        // Surface provides LocalContentColor; without it plain Text defaults to black on the black background.
        Surface(color = Color(0xFF080D1A), contentColor = Color(0xFFEAEDF5), content = content)
    }
}
