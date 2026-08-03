package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
 * Input de RUT chileno con auto-formato y teclado en pantalla.
 *
 * Dos modos:
 *
 * 1. **Custom keypad** (default, [useCustomKeypad] = true): el campo es
 *    readOnly (no aparece el teclado nativo) y debajo se renderiza un
 *    [NumericKeypad] con la distribucion 1-9 / K, 0, <- . Pensado para
 *    modo kiosko (Totem) y POS donde la K no se puede tipear con el
 *    teclado numerico del sistema.
 *
 * 2. **Legacy** ([useCustomKeypad] = false): teclado nativo numerico +
 *    un boton "K" en el trailing icon que toggle la K al final. Se
 *    mantiene por si alguna pantalla quiere esa UX.
 *
 * El formato de salida es siempre el mismo ("12.345.678-9" o
 * "12.345.678-K" cuando el DV es K) via [formatRutForDisplay].
 *
 * Sprint 3.2.1: agregamos el modo legacy con K trailing.
 * Sprint 3.2.2: agregamos el [NumericKeypad] y lo dejamos como default.
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
    useCustomKeypad: Boolean = true,
) {
    val visualTransformation = remember(autoFormat) {
        if (autoFormat) RutVisualTransformation() else VisualTransformation.None
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw ->
                onValueChange(normalizeRutInput(raw, autoFormat))
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            enabled = enabled,
            readOnly = useCustomKeypad,
            isError = isError,
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            trailingIcon = {
                if (!useCustomKeypad) {
                    // Modo legacy: trailing K button.
                    if (value.length <= 10) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
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
                } else {
                    // Modo keypad: indicamos visualmente que el campo esta
                    // bloqueado al teclado nativo con un candadito.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Teclado en pantalla",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            keyboardOptions = if (useCustomKeypad) {
                // readOnly=true ya suprime el soft keyboard nativo en M3, pero
                // pasamos KeyboardType.Text explicito para no pedir teclado
                // numerico que no se va a usar.
                KeyboardOptions(keyboardType = KeyboardType.Text)
            } else {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            },
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )

        if (useCustomKeypad) {
            Spacer(Modifier.height(12.dp))
            NumericKeypad(
                onKeyPress = { ch ->
                    onValueChange(normalizeRutInput(value + ch, autoFormat))
                },
                onBackspace = {
                    if (value.isNotEmpty()) {
                        onValueChange(value.dropLast(1))
                    }
                },
                enabled = enabled,
            )
        }
    }
}

/**
 * Normaliza un string "crudo" (lo que podria llegar del OutlinedTextField o
 * del keypad) al formato canonico del RUT sin puntos ni guion: solo digitos
 * y a lo mas una 'K' al final, maximo 9 caracteres.
 *
 * - Acepta solo [0-9Kk.-] (descarta todo lo demas).
 * - Mayusculas.
 * - Una sola K (si hay varias, deja la primera y descarta el resto).
 * - K es siempre el ultimo caracter (lo que viene despues se descarta).
 * - Largo maximo 9 (8 cuerpo + DV).
 *
 * Si [autoFormat] es false, solo filtra y devuelve (sin normalizar formato).
 */
internal fun normalizeRutInput(raw: String, autoFormat: Boolean = true): String {
    val filtrado = raw.filter { ch ->
        ch.isDigit() || ch == '-' || ch == '.' || ch == 'k' || ch == 'K'
    }
    if (!autoFormat) return filtrado
    val clean = filtrado.replace(Regex("[.\\-]"), "").uppercase()
    val normalized = if (clean.count { it == 'K' } > 1) {
        val firstK = clean.indexOf('K')
        clean.substring(0, firstK + 1) + clean.substring(firstK + 1).replace("K", "")
    } else {
        clean
    }
    val sinExtra = if (normalized.contains('K')) {
        val idxK = normalized.indexOf('K')
        normalized.substring(0, idxK + 1)
    } else {
        normalized
    }
    return if (sinExtra.length > 9) sinExtra.substring(0, 9) else sinExtra
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
        // posteriores se desplazan. Usamos ratio para que el caret siga
        // aproximadamente la longitud del out (mas simple, sirve para
        // nuestro caso).
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
