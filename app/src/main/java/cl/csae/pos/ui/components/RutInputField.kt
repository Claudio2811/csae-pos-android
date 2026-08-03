package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Input de RUT chileno con auto-formato y boton K.
 *
 * - Teclado nativo numerico (KeyboardType.Number). Como Android no muestra la
 *   K en ese teclado, agregamos un trailing "K" (boton) que suma/quita K al
 *   final del RUT.
 * - Filtra entrada a [0-9Kk.-] (sin espacios ni letras raras).
 * - Si [autoFormat] esta activo, mientras tipea aplica la mascara chilena
 *   "12.345.678-9" (millares con punto, guion antes del DV, o K como DV).
 * - Leading: icono de persona.
 *
 * Sprint 3.2.1: lo agregamos para soportar RUTs terminados en K (DV=10) que
 * el teclado numerico nativo no permite tipear.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RutInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "RUT",
    placeholder: String = "11.111.111-1",
    autoFormat: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val visualTransformation = remember(autoFormat) {
        if (autoFormat) RutVisualTransformation() else VisualTransformation.None
    }

    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            // 1. Filtrar: solo [0-9Kk.-]. Descarta espacios y letras raras.
            val filtrado = raw.filter { ch ->
                ch.isDigit() || ch == '-' || ch == '.' || ch == 'k' || ch == 'K'
            }
            if (!autoFormat) {
                onValueChange(filtrado)
                return@OutlinedTextField
            }
            // 2. Quitar formato existente y dejar solo el "esqueleto" que
            //    re-formatearemos al final.
            val clean = filtrado.replace(Regex("[.\\-]"), "").uppercase()
            // 3. Si tipeo K dos veces, dejamos solo la primera ocurrencia.
            val normalized = if (clean.count { it == 'K' } > 1) {
                val firstK = clean.indexOf('K')
                clean.substring(0, firstK + 1) + clean.substring(firstK + 1).replace("K", "")
            } else {
                clean
            }
            // 4. Si el usuario ya puso una K, todo lo que viene despues no es
            //    valido (K es el DV, siempre va al final). La cortamos.
            val sinExtra = if (normalized.contains('K')) {
                val idxK = normalized.indexOf('K')
                normalized.substring(0, idxK + 1)
            } else {
                normalized
            }
            // 5. Limitar a un maximo razonable (8 digitos + K = 9 chars).
            val trimmed = if (sinExtra.length > 9) sinExtra.substring(0, 9) else sinExtra
            onValueChange(trimmed)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
        trailingIcon = {
            // Mostrar el boton K solo si el RUT no esta "completo" (10 chars
            // limpios = 8 digitos + K + margen). Asi no estorba al final.
            if (value.length <= 10) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = {
                            onValueChange(
                                if (value.endsWith("K", ignoreCase = true)) {
                                    value.dropLast(1)
                                } else {
                                    value + "K"
                                }
                            )
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Text(
                            text = "K",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = visualTransformation,
        modifier = modifier,
    )
}

/**
 * VisualTransformation que aplica la mascara chilena "12.345.678-9" a un
 * string "limpio" (sin puntos ni guion). Si el ultimo char es K, lo respeta
 * (no le agrega guion adelante).
 */
private class RutVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val out = formatRutForDisplay(raw)
        val outLen = out.length
        val rawLen = raw.length
        // Mapeo de offsets: cada raw char -> misma posicion en out solo si
        // el formato no cambia. Cuando agregamos puntos/guion, los raw chars
        // posteriores se desplazan. Usamos Identity para que el caret siga
        // la longitud del out (mas simple, sirve para nuestro caso).
        val offsetMap = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (rawLen == 0) return 0
                val ratio = offset.toFloat() / rawLen
                return (ratio * outLen).toInt().coerceIn(0, outLen)
            }
            override fun transformedToOriginal(offset: Int): Int {
                if (outLen == 0) return 0
                val ratio = offset.toFloat() / outLen
                return (ratio * rawLen).toInt().coerceIn(0, rawLen)
            }
        }
        return TransformedText(AnnotatedString(out), offsetMap)
    }
}

/**
 * Mascara chilena: cada 3 digitos desde la derecha va un punto, y antes
 * del DV un guion. Si el ultimo char es K, va al final sin guion.
 *   "" -> ""
 *   "1" -> "1"
 *   "1234" -> "1.234"
 *   "12345678" -> "12.345.678"
 *   "123456789" -> "12.345.678-9"
 *   "12345678K" -> "12.345.678-K"
 */
internal fun formatRutForDisplay(clean: String): String {
    if (clean.isEmpty()) return ""
    val endsInK = clean.last() == 'K'
    val body = if (endsInK) clean.dropLast(1) else clean
    if (body.isEmpty()) return if (endsInK) "K" else ""
    val withDots = StringBuilder()
    // Insertar punto cada 3 digitos desde la derecha.
    var count = 0
    for (i in body.indices.reversed()) {
        withDots.insert(0, body[i])
        count++
        if (count == 3 && i != 0) {
            withDots.insert(0, '.')
            count = 0
        }
    }
    return if (endsInK) "$withDots-K" else withDots.toString()
}
