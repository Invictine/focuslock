package com.focuslock.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val PixelDarkColorScheme = darkColorScheme(
    primary = PixelPrimaryDark,
    onPrimary = PixelOnPrimaryDark,
    primaryContainer = PixelPrimaryContainerDark,
    onPrimaryContainer = PixelOnPrimaryContainerDark,
    secondary = PixelSecondaryDark,
    onSecondary = PixelOnSecondaryDark,
    secondaryContainer = PixelSecondaryContainerDark,
    onSecondaryContainer = PixelOnSecondaryContainerDark,
    tertiary = PixelTertiaryDark,
    onTertiary = PixelOnTertiaryDark,
    tertiaryContainer = PixelTertiaryContainerDark,
    onTertiaryContainer = PixelOnTertiaryContainerDark,
    error = PixelErrorDark,
    onError = PixelOnErrorDark,
    errorContainer = PixelErrorContainerDark,
    onErrorContainer = PixelOnErrorContainerDark,
    background = PixelBackgroundDark,
    onBackground = PixelOnBackgroundDark,
    surface = PixelSurfaceDark,
    onSurface = PixelOnSurfaceDark,
    surfaceVariant = PixelSurfaceVariantDark,
    onSurfaceVariant = PixelOnSurfaceVariantDark,
    outline = PixelOutlineDark,
    outlineVariant = PixelOutlineVariantDark,
    surfaceContainerLowest = PixelSurfaceContainerLowestDark,
    surfaceContainerLow = PixelSurfaceContainerLowDark,
    surfaceContainer = PixelSurfaceContainerDark,
    surfaceContainerHigh = PixelSurfaceContainerHighDark,
    surfaceContainerHighest = PixelSurfaceContainerHighestDark
)

@Composable
fun FocusLockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PixelDarkColorScheme
        else -> lightColorScheme(
            primary = Color(0xFF365E48), onPrimary = Color.White,
            primaryContainer = Color(0xFFD8EADB), onPrimaryContainer = Color(0xFF153322),
            secondaryContainer = Color(0xFFE2E8E0), onSecondaryContainer = Color(0xFF263329),
            background = Color(0xFFF7F8F3), onBackground = Color(0xFF1A1D19),
            surface = Color(0xFFF7F8F3), onSurface = Color(0xFF1A1D19),
            surfaceContainer = Color(0xFFEEF0E9), surfaceContainerHigh = Color(0xFFE8EAE3),
            surfaceContainerHighest = Color(0xFFE2E4DD), onSurfaceVariant = Color(0xFF444B43)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
