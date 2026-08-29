package io.github.xia92.pause.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

internal val PauseLightColorScheme = lightColorScheme(
    primary = PauseBlue40,
    onPrimary = PauseOnBlue40,
    primaryContainer = PauseBlueContainer,
    onPrimaryContainer = PauseOnBlueContainer,
    secondary = PauseWarmOnSurfaceVariant,
    tertiary = Pink40,
    background = PauseWarmBackground,
    onBackground = PauseWarmOnSurface,
    surface = PauseWarmSurface,
    onSurface = PauseWarmOnSurface,
    surfaceVariant = PauseWarmSurfaceVariant,
    onSurfaceVariant = PauseWarmOnSurfaceVariant,
    outline = PauseWarmOutline,
    outlineVariant = PauseWarmOutlineVariant,
    error = PauseError,
    surfaceTint = PauseWarmSurface,
    surfaceBright = PauseWarmSurface,
    surfaceDim = PauseWarmBackground,
    surfaceContainerLowest = PauseWarmSurface,
    surfaceContainerLow = PauseWarmSurface,
    surfaceContainer = PauseWarmSurface,
    surfaceContainerHigh = PauseWarmSurface,
    surfaceContainerHighest = PauseWarmSurface
)

@Composable
fun PauseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> PauseLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
