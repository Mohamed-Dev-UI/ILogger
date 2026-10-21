package com.mohamed.dev.ui.ilogger.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF003738),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealOnContainerDark,
    secondary = Sage80,
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = SageOnContainerDark,
    tertiary = Amber80,
    onTertiary = Color(0xFF47290C),
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = MistDark,
    onSurfaceVariant = OnMistDark,
    outline = OutlineDark,
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = Sage40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = SageContainerLight,
    onSecondaryContainer = SageOnContainerLight,
    tertiary = Amber40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = MistLight,
    onSurfaceVariant = OnMistLight,
    outline = OutlineLight,
)

/**
 * The demo opts out of dynamic color by default so the screenshots and the
 * palette stay recognizable; pass `dynamicColor = true` to follow the device.
 */
@Composable
fun ILoggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
