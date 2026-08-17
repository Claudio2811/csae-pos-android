package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
    /**
     * Si true, el field se muestra pero NO se puede editar con teclado.
     * Usado cuando el input viene de un NumericKeypad externo (no nativo,
     * no embebido) — asi no aparece el teclado del sistema al tap.
     * Default false. Se combina con useCustomKeypad (el keypad embebido
     * tambien requiere readOnly).
     */
    readOnly: Boolean = false,
) {
    val visualTransformation = remember(autoFormat) {
        if (autoFormat) RutVisualTransformation() else VisualTransformation.None
    }
    // **Sprint F26 (2026-08-14):** ahora valida formato + DV real con
    // RutHelper. Antes solo validaba formato (isRutFormatValid), no DV.
    val rutValido = remember(value) { cl.csae.pos.util.RutHelper.isValid(value) }
    val rutError = remember(value) { cl.csae.pos.util.RutHelper.errorMessage(value) }
    val isReadOnly = readOnly || useCustomKeypad

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw: String ->
                if (isReadOnly) return@OutlinedTextField
                onValueChange(normalizeRutInput(raw, autoFormat))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = isReadOnly,
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
                if (value.isNotEmpty() && rutError == null) {
                    // **Sprint F26 (2026-08-14):** check verde SOLO si formato
                    // + DV son validos. Antes era solo formato.
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "RUT valido (DV correcto)",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                } else if (value.isNotEmpty() && rutError != null) {
                    // **Sprint F26 (2026-08-14):** X rojo + mensaje si DV no coincide.
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = rutError,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            isError = isError || rutError != null,
            // **Sprint F26 (2026-08-14):** mostrar el mensaje de error
            // debajo del input (en supportingText). Si el operador tipeo
            // un DV incorrecto, ve "Digito verificador (DV) incorrecto.
            // El DV correcto es '5'." sin tener que esperar a la respuesta
            // del backend.
            supportingText = {
                if (rutError != null) {
                    Text(
                        text = rutError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
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
 * del DV un guion. El DV puede ser K (siempre separado) o un digito
 * (separado solo cuando ya estan los 8 digitos del cuerpo completos).
 *
 *   "" -> ""
 *   "1" -> "1"
 *   "12" -> "12"
 *   "1234" -> "1.234"
 *   "12345678" -> "12.345.678"   (8 digitos del cuerpo, sin DV todavia)
 *   "123456789" -> "12.345.678-9" (9 chars: el 9 es el DV)
 *   "12345678K" -> "12.345.678-K" (K es DV, separado por guion)
 *
 * Fix (2026-08-12): antes la funcion solo ponia guion cuando el ultimo
 * char era K. Con 9+ digitos, el DV (ultimo digito) NO se separaba, lo
 * que daba "176.204.559" en vez de "176.204.55-9". El usuario tenia
 * que borrar y re-tipear el DV para que se formateara bien. Ahora el
 * DV se separa automaticamente cuando:
 *   - el ultimo char es K (cuerpo puede ser de cualquier largo), o
 *   - el largo total es >= 9 (cuerpo completo = 8 digitos + 1 DV).
 */
internal fun formatRutForDisplay(clean: String): String {
    if (clean.isEmpty()) return ""
    val isLastCharDV = clean.last() == 'K' || clean.length >= 9
    if (!isLastCharDV) {
        // Aun no llega al DV: solo puntos, sin guion.
        val withDots = StringBuilder()
        var count = 0
        for (i in clean.indices.reversed()) {
            withDots.insert(0, clean[i])
            count++
            if (count == 3 && i != 0) {
                withDots.insert(0, '.')
                count = 0
            }
        }
        return withDots.toString()
    }
    // El ultimo char es DV (K o >=9 chars total): separarlo con guion.
    val body = clean.dropLast(1)
    val dv = clean.last().toString()
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
    return "$withDots-$dv"
}
