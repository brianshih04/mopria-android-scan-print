package com.brianshih.mopria.android.scanprint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFFD),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF05201D),
    tertiary = Color(0xFF53618E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE1FF),
    onTertiaryContainer = Color(0xFF0E1736),
    background = Color(0xFFF7FBFA),
    surface = Color(0xFFF7FBFA),
    surfaceVariant = Color(0xFFDCE5E5),
    onSurface = Color(0xFF161D1E),
    onSurfaceVariant = Color(0xFF3F494A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D3E0),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9EEFFD),
    secondary = Color(0xFFB0CCC8),
    onSecondary = Color(0xFF1B3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFBBC4F5),
    onTertiary = Color(0xFF252E4D),
    tertiaryContainer = Color(0xFF3C4665),
    onTertiaryContainer = Color(0xFFDDE1FF),
)

@Composable
fun MopriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography().run {
            copy(
                headlineMedium = headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                titleLarge = titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            )
        },
        content = content,
    )
}
