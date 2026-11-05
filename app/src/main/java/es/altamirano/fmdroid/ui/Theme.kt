package es.altamirano.fmdroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Los colores de RadioApp (`Theme.swift`), sacados del icono de la radio antigua menta. Los
 * valores claros de la marca son más oscuros que el menta del icono para que el texto pequeño
 * pase el 4,5:1 de WCAG AA sobre los fondos menta (lo midieron en iOS).
 */
object Brand {
    val brandLight = Color(0xFF1F6F64)
    val brandDark = Color(0xFF5FD3C2)
    val backgroundLight = Color(0xFFEAF7F3)
    val backgroundDark = Color(0xFF0E1B19)
    val surfaceLight = Color(0xFFD6EFE8)      // barras y superficies elevadas
    val surfaceDark = Color(0xFF16302C)
}

/** Colores de las iniciales cuando una emisora no tiene logo; todos pasan 3:1 con blanco. */
val LogoPalette = listOf(
    0xFFD9541F, 0xFFE8445A, 0xFF7C5CBF, 0xFF2D9CDB, 0xFF1E8A72,
    0xFF219653, 0xFFC97A22, 0xFFEB5757, 0xFF1B8FBF, 0xFF9B51E0,
).map { Color(it) }

val LightScheme = lightColorScheme(
    primary = Brand.brandLight,
    // Sobre el botón de la marca, el color del fondo y no blanco: en oscuro la marca es un
    // menta claro y el blanco encima se quedaba en 1,8:1.
    onPrimary = Brand.backgroundLight,
    primaryContainer = Brand.surfaceLight,
    onPrimaryContainer = Brand.brandLight,
    secondary = Brand.brandLight,
    onSecondary = Brand.backgroundLight,
    secondaryContainer = Brand.surfaceLight,
    onSecondaryContainer = Brand.brandLight,
    background = Brand.backgroundLight,
    onBackground = Color(0xFF10201D),
    surface = Brand.backgroundLight,
    onSurface = Color(0xFF10201D),
    surfaceVariant = Brand.surfaceLight,
    onSurfaceVariant = Color(0xFF4A5C58),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFE0F3EE),
    surfaceContainer = Brand.surfaceLight,
    surfaceContainerHigh = Color(0xFFCDE9E1),
    surfaceContainerHighest = Color(0xFFC3E3DA),
    outline = Color(0xFF7A8E89),
    outlineVariant = Color(0xFFBFD9D2),
)

val DarkScheme = darkColorScheme(
    primary = Brand.brandDark,
    onPrimary = Brand.backgroundDark,
    primaryContainer = Brand.surfaceDark,
    onPrimaryContainer = Brand.brandDark,
    secondary = Brand.brandDark,
    onSecondary = Brand.backgroundDark,
    secondaryContainer = Brand.surfaceDark,
    onSecondaryContainer = Brand.brandDark,
    background = Brand.backgroundDark,
    onBackground = Color(0xFFE3F1EE),
    surface = Brand.backgroundDark,
    onSurface = Color(0xFFE3F1EE),
    surfaceVariant = Brand.surfaceDark,
    onSurfaceVariant = Color(0xFFA9BDB8),
    surfaceContainerLowest = Color(0xFF09130F),
    surfaceContainerLow = Color(0xFF122522),
    surfaceContainer = Brand.surfaceDark,
    surfaceContainerHigh = Color(0xFF1C3934),
    surfaceContainerHighest = Color(0xFF23433D),
    outline = Color(0xFF6F8580),
    outlineVariant = Color(0xFF2A4741),
)

@Composable
fun FmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}
