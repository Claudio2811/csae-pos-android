package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Input de RUT chileno con auto-formato y (opcional) teclado en pantalla.
 *
 * Dos modos:
 *
 * 1. **Sin keypad embebido** ([useCustomKeypad] = false, default): el campo
 *    usa el teclado nativo. Pensado para cuando el [NumericKeypad] se
 *    renderiza aparte (en el bottomBar del Scaffold, ver TotemScreen y
 *    POSScreen). Sprint 3.3: este es el modo recomendado.
 *
 * 2. **Con keypad embebido** ([useCustomKeypad] = true): el campo es
 *    readOnly y debajo se renderiza un [NumericKeypad]. Solo se mantiene
 *    por compat / tests.
 *
 * El formato de salida es siempre el mismo ("12.345.678-9" o
 * "12.345.678-K" cuando el DV es K) via [formatRutForDisplay].
 *
 * Sprint 3.3: estilos renovados:
 * - Texto grande (22sp) y centrado.
 * - FontFamily.Monospace para que los puntos/guion no "salten" al insertar.
 * - Color onSurface (no gris) y placeholder onSurfaceVariant.
 * - Sin candado en trailing (era confuso). Si el RUT es valido (formato
 *   7-8 digitos + DV), muestra un check verde.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RutInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "RUT",
    placeholder: String = "12.345.678-9",
    autoFormat: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    useCustomKeypad: Boolean = false,
) {
    val visualTransformation = remember(autoFormat) {
        if (autoFormat) RutVisualTransformation() else VisualTransformation.None
    }
    val rutValido = remember(value) { isRutFormatValid(value) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw: String ->
                onValueChange(normalizeRutInput(raw, autoFormat))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = useCustomKeypad,
            textStyle = TextStyle(
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            placeholder = {
                Text(
                    text = placeholder,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = {
                if (rutValido && value.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "RUT valido",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            isError = isError,
            visualTransformation = visualTransformation,
            // Sprint F13 (2026-08-11): cambiamos KeyboardType.Number -> Text
            // para que el teclado del sistema muestre tambien la K del DV
            // (KeyboardType.Number en Android NO muestra letras, asi que
            // el operador no podia tipear '17620455-K'). El sistema operativo
            // ofrece ademas un switch '123' para ver solo numeros.
            // El auto-formato via VisualTransformation se encarga de poner
            // los puntos y el guion antes del DV (12.345.678-K).
            // Cuando se usa el NumericKeypad embebido (useCustomKeypad=true),
            // se mantiene KeyboardType.Text para que el campo no acepte
            // input por teclado (readOnly=true igual lo bloquea).
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
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
 * Sprint 3.3: validador de formato. NO calcula DV real (eso lo hace el
 * backend); solo confirma que la entrada tiene 7 u 8 digitos + un DV
 * (0-9 o K). Suficiente para mostrar el check verde en el input y para
 * que el operador vea feedback inmediato.
 */
internal fun isRutFormatValid(clean: String): Boolean {
    if (clean.isEmpty()) return false
    val last = clean.last()
    if (!last.isDigit() && last != 'K') return false
    val body = clean.dropLast(1)
    if (body.length < 7 || body.length > 8) return false
    return body.all { it.isDigit() }
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
