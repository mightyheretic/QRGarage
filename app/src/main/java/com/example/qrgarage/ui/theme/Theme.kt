package com.example.qrgarage.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.qrgarage.data.settings.ThemeMode

/** Brand palette ("workshop amber") used on API 26-30, where dynamic color is unavailable. */
private val LightBrand = lightColorScheme(
    primary = Color(0xFF8B5000), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE), onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF725A42), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCBE), onSecondaryContainer = Color(0xFF291806),
    tertiary = Color(0xFF58633A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8B4), onTertiaryContainer = Color(0xFF161E01),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF2DFD1), onSurfaceVariant = Color(0xFF51443A),
    outline = Color(0xFF837468), outlineVariant = Color(0xFFD5C3B5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFCF2EC),
    surfaceContainer = Color(0xFFF6ECE6), surfaceContainerHigh = Color(0xFFF0E6E0),
    surfaceContainerHighest = Color(0xFFEAE1DB),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFFFB870), onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00), onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE1C1A4), onSecondary = Color(0xFF402C18),
    secondaryContainer = Color(0xFF59422C), onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFC0CC9A), onTertiary = Color(0xFF2A3410),
    tertiaryContainer = Color(0xFF414B24), onTertiaryContainer = Color(0xFFDCE8B4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF18120E), onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF18120E), onSurface = Color(0xFFEDE0D8),
    surfaceVariant = Color(0xFF51443A), onSurfaceVariant = Color(0xFFD5C3B5),
    outline = Color(0xFF9E8E81), outlineVariant = Color(0xFF51443A),
    surfaceContainerLowest = Color(0xFF120D09), surfaceContainerLow = Color(0xFF201A16),
    surfaceContainer = Color(0xFF251E1A), surfaceContainerHigh = Color(0xFF2F2824),
    surfaceContainerHighest = Color(0xFF3A332E),
)

@Composable
fun QrGarageTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkBrand else LightBrand
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
