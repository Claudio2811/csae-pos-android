package cl.csae.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = VerdePrincipal,
    onPrimary = TexSobrePrimario,
    primaryContainer = VerdeClaro,
    onPrimaryContainer = Color.White,
    secondary = NaranjaAcento,
    onSecondary = Color.White,
    secondaryContainer = NaranjaClaro,
    onSecondaryContainer = Color.Black,
    background = FondoClaro,
    onBackground = TextoPrimario,
    surface = SuperficieClara,
    onSurface = TextoPrimario,
    error = ErrorRojo,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = VerdeClaro,
    onPrimary = Color.Black,
    primaryContainer = VerdePrincipal,
    onPrimaryContainer = Color.White,
    secondary = NaranjaClaro,
    onSecondary = Color.Black,
    secondaryContainer = NaranjaAcento,
    onSecondaryContainer = Color.White,
    background = FondoOscuro,
    onBackground = Color.White,
    surface = SuperficieOscura,
    onSurface = Color.White,
    error = ErrorRojo,
    onError = Color.White,
)

@Composable
fun CsaePosTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = CsaeTypography,
        content = content,
    )
}
