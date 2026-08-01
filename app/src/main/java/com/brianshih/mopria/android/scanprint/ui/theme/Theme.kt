package com.brianshih.mopria.android.scanprint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B628E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF53606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F3),
    onSecondaryContainer = Color(0xFF101C28),
    tertiary = Color(0xFF6B5772),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3D9F5),
    onTertiaryContainer = Color(0xFF25132B),
    background = Color(0xFFF9F9FC),
    surface = Color(0xFFF9F9FC),
    surfaceVariant = Color(0xFFE0E3E8),
    onSurface = Color(0xFF1A1B1F),
    onSurfaceVariant = Color(0xFF43474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8F7),
    onPrimary = Color(0xFF073258),
    primaryContainer = Color(0xFF214A73),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7D7),
    onSecondary = Color(0xFF25313F),
    secondaryContainer = Color(0xFF3B4856),
    onSecondaryContainer = Color(0xFFD7E3F3),
    tertiary = Color(0xFFD6BFD8),
    onTertiary = Color(0xFF3B2940),
    tertiaryContainer = Color(0xFF533F58),
    onTertiaryContainer = Color(0xFFF3D9F5),
)

@Composable
fun MopriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
