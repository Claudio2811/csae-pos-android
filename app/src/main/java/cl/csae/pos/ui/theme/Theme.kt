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

/**
 * **Sprint F16 (2026-08-11):** theme dinamico del casino. Si el casino
 * tiene colorPrimario/colorAcento personalizados, los aplica via
 * `lightColorScheme.copy(primary = ..., secondary = ...)`. Si no, usa
 * los colores default del producto (verde Salamanca + naranja casino).
 *
 * El casino actual se lee como `CasinoThemeDto?` desde
 * [cl.csae.pos.data.repository.AuthRepository.currentCasinoTheme]. La
 * app lo pasa aca desde [CsaeNavHost] (que colecta el Flow via
 * `collectAsState`).
 */
@Composable
fun CsaePosTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    casinoColorPrimario: String? = null,
    casinoColorAcento: String? = null,
    content: @Composable () -> Unit,
) {
    val baseColors = if (useDarkTheme) DarkColors else LightColors

    // Sprint F16: override de los colores del casino. Si el operador
    // tipeo un color invalido, parseHexColor devuelve null y caemos al
    // color default del producto (no se rompe la app).
    val primaryColor = parseHexColor(casinoColorPrimario) ?: baseColors.primary
    val secondaryColor = parseHexColor(casinoColorAcento) ?: baseColors.secondary
    val finalColors = if (casinoColorPrimario != null || casinoColorAcento != null) {
        baseColors.copy(
            primary = primaryColor,
            onPrimary = Color.White,
            secondary = secondaryColor,
            onSecondary = Color.White,
        )
    } else {
        baseColors
    }

    MaterialTheme(
        colorScheme = finalColors,
        typography = CsaeTypography,
        content = content,
    )
}

/**
 * Convierte un hex `#RRGGBB` (o `#RRGGBBAA` despues del F14 fix) a un
 * [Color] de Compose. Retorna `null` si el formato es invalido para que
 * el caller caiga al color default.
 *
 * Acepta los formatos:
 * - `#RRGGBB` (6 chars)
 * - `#RRGGBBAA` (8 chars, alpha se descarta — el backend siempre manda
 *   sin alpha despues del F14 fix, pero toleramos el caso por si la UI
 *   mobile lo genera con alpha en algun otro lado).
 */
internal fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return try {
        val long = java.lang.Long.parseLong(cleaned, 16)
        if (cleaned.length == 6) {
            // 0xFF + RRGGBB para que alpha sea 1.0.
            Color(0xFF000000L or long)
        } else {
            // 8 chars: el alpha ya esta en el string, pero Compose quiere
            // ARGB. Como el backend siempre manda sin alpha en F14, este
            // caso es raro; igualmente lo soportamos descartando el alpha.
            val rgb = long and 0x00FFFFFFL
            Color(0xFF000000L or rgb)
        }
    } catch (_: NumberFormatException) {
        null
    }
}
