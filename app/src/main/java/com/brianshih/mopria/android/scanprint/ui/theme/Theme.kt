package com.brianshih.mopria.android.scanprint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val LightColors = lightColorScheme(
    primary = Color(0xFF3156D3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E7FF),
    onPrimaryContainer = Color(0xFF102264),
    secondary = Color(0xFF007A73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F1EA),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF8A4D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurface = Color(0xFF191B23),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767780),
    outlineVariant = Color(0xFFC6C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    surfaceContainerLow = Color(0xFFF1F0F7),
    surfaceContainer = Color(0xFFEBEBF2),
    surfaceContainerHigh = Color(0xFFE5E4EC),
    surfaceContainerHighest = Color(0xFFDFDEE6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF00258B),
    primaryContainer = Color(0xFF173EBA),
    onPrimaryContainer = Color(0xFFE1E7FF),
    secondary = Color(0xFF8DD5CD),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504B),
    onSecondaryContainer = Color(0xFFB9F1EA),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF492900),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF11131A),
    surface = Color(0xFF11131A),
    surfaceVariant = Color(0xFF45464F),
    onSurface = Color(0xFFE3E1EA),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    surfaceContainerLow = Color(0xFF191B23),
    surfaceContainer = Color(0xFF1D1F28),
    surfaceContainerHigh = Color(0xFF272A33),
    surfaceContainerHighest = Color(0xFF323540),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MopriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
