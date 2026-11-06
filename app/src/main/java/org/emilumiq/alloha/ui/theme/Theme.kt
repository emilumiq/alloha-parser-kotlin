package org.emilumiq.alloha.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Ink,
    primaryContainer = EmberDeep,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = InkOutline,
    error = Ember,
    onError = Ink,
)

private val LightColorScheme = lightColorScheme(
    primary = EmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = Color(0xFF77574F),
    onSecondary = Color.White,
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF221A18),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF221A18),
    surfaceVariant = Color(0xFFF5DED8),
    onSurfaceVariant = Color(0xFF53433F),
    outline = Color(0xFF85736E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun AllohaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AllohaTypography,
        content = content,
    )
}
