package cl.csae.pos.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import cl.csae.pos.R

/**
 * **Sprint F18.3 (2026-08-11):** helper unificado para mostrar el logo
 * del casino, soportando los 3 formatos que el backend puede devolver:
 *
 * 1. `data:image/...;base64,...` (F17 inline, logos < 16KB)
 * 2. `https://...` o `http://...` (F18 Azure Blob Storage, logos > 16KB)
 * 3. `null` o vacio (sin logo configurado)
 *
 * En los 3 casos cae al drawable default del producto (`csae_logo`) si
 * algo falla (decodificacion base64 rota, URL no valida, red caida).
 *
 * Patron: "cascada con fallback" — el casino puede subir un logo, pero
 * la UI nunca se rompe si el logo falta o esta mal. El usuario siempre
 * ve algo (logo CSAE por defecto).
 *
 * Dependencias:
 * - `coil-compose` para URLs https/http (AsyncImage con cache en memoria
 *   + disco automatico).
 * - `BitmapFactory` + `Base64` para data URIs inline (no necesita red).
 */
@Composable
fun CasinoLogoImage(
    logoUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String = "Logo del casino",
    contentScale: ContentScale = ContentScale.Fit,
) {
    val fallback = @Composable {
        Image(
            painter = painterResource(id = R.drawable.csae_logo),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }

    if (logoUrl.isNullOrBlank()) {
        fallback()
        return
    }

    when {
        // Caso 1: data URI inline base64 (F17, logos < 16KB).
        logoUrl.startsWith("data:image/", ignoreCase = true) -> {
            // Recordamos el bitmap para no re-decoodear en cada recomposition.
            val bitmap = remember(logoUrl) { decodeDataUriBitmap(logoUrl) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier,
                    contentScale = contentScale,
                )
            } else {
                fallback()
            }
        }
        // Caso 2: URL http/https (F18 Azure Blob Storage, logos > 16KB).
        logoUrl.startsWith("http://", ignoreCase = true) ||
            logoUrl.startsWith("https://", ignoreCase = true) -> {
            AsyncImage(
                model = logoUrl,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                // Mientras descarga: muestra el logo CSAE (sin flicker feo).
                placeholder = painterResource(id = R.drawable.csae_logo),
                // Si falla la red o la URL es invalida: muestra el logo CSAE.
                error = painterResource(id = R.drawable.csae_logo),
                // Fallback final (URL null o model raro).
                fallback = painterResource(id = R.drawable.csae_logo),
            )
        }
        // Caso 3: formato no reconocido -> fallback.
        else -> fallback()
    }
}

/**
 * Decodifica un data URI `data:image/...;base64,XXXX` a un [android.graphics.Bitmap].
 * Devuelve `null` si el formato es invalido o la decodificacion falla.
 */
private fun decodeDataUriBitmap(dataUri: String): android.graphics.Bitmap? {
    return try {
        val base64 = dataUri.substringAfter("base64,", missingDelimiterValue = "")
        if (base64.isBlank()) return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        // Base64 mal formado
        null
    } catch (_: Exception) {
        // Cualquier otra excepcion (OOM en imagen gigante, etc)
        null
    }
}
