package it.xonos.ma.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * I colori di Xonos: blu notte e ambra, come l'icona e il menu in auto. Sempre scuri: l'app si
 * usa anche di sera in macchina, e le copertine risaltano meglio.
 */
private val XonosColors = darkColorScheme(
    primary = Color(0xFFFFC27A),
    onPrimary = Color(0xFF3A2300),
    primaryContainer = Color(0xFF5A3C0C),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFFB9C7DE),
    onSecondary = Color(0xFF233046),
    secondaryContainer = Color(0xFF2A3850),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFF8FD5C6),
    onTertiary = Color(0xFF00382F),
    background = Color(0xFF0B111B),
    onBackground = Color(0xFFEEF2F7),
    surface = Color(0xFF0B111B),
    onSurface = Color(0xFFEEF2F7),
    surfaceVariant = Color(0xFF1F293A),
    onSurfaceVariant = Color(0xFF97A3B6),
    surfaceTint = Color(0xFFFFC27A),
    surfaceContainerLowest = Color(0xFF070B12),
    surfaceContainerLow = Color(0xFF101723),
    surfaceContainer = Color(0xFF141C2A),
    surfaceContainerHigh = Color(0xFF1A2333),
    surfaceContainerHighest = Color(0xFF222C3E),
    surfaceBright = Color(0xFF2A3446),
    surfaceDim = Color(0xFF0B111B),
    inverseSurface = Color(0xFFEEF2F7),
    inverseOnSurface = Color(0xFF1A2333),
    inversePrimary = Color(0xFF7A5200),
    outline = Color(0xFF4A566A),
    outlineVariant = Color(0xFF2A3446),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val XonosType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

@Composable
fun XonosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XonosColors, typography = XonosType, content = content)
}
