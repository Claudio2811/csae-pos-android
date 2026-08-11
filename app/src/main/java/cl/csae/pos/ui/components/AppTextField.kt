package cl.csae.pos.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * OutlinedTextField con maxLength automatico y supportingText para mostrar
 * el conteo de caracteres y mensaje de error.
 *
 * Sprint F13 (2026-08-11): wrapper creado para que TODOS los inputs de la
 * app (login, dialogs, forms) compartan:
 * - maxLength enforced (no permite tipear mas alla del limite)
 * - supportingText que muestra "X/200" en vivo + mensaje de error si lo hay
 * - isError que se propaga al outline (rojo) y al supportingText
 * - singleLine = true por default
 *
 * Coincide 1:1 con los MaxLength de los validators backend:
 *
 *   LoginRequest.Email           max 200
 *   LoginRequest.Password        max 200
 *   Comensal.Nombre              max 100
 *   Comensal.Apellido            max 100
 *   Comensal.Email               max 200
 *   Comensal.Telefono            max 30
 *   Empresa.RazonSocial          max 200
 *   Servicio.Nombre              max 100
 *   Servicio.Descripcion         max 500
 *   Servicio.IconoUrl            max 500
 *   Sucursal.Nombre              max 100
 *   Sucursal.Codigo              max 20
 *   Sucursal.Direccion           max 300
 *   Contrato.Numero              max 50
 *   Contrato.Condiciones         max 2000
 *   Usuario.Nombre               max 100
 *   Usuario.Apellido             max 100
 *   Usuario.Email                max 200
 *   CambioPassword.NewPassword   max 100
 *   ForgotPassword.Email         max 254
 *
 * El objetivo es que el mobile NO envie requests que el backend rechace por
 * largo. Si lo hace, el usuario igual ve el 400 con el mismo mensaje
 * detallado (gracias al ApiClient refactor de F2 + los mensajes consistentes
 * de F13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    placeholder: String? = null,
    maxLength: Int = 200,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    // Helper local: aplica el maxLength sin perder la posicion del cursor
    // (en realidad no es problema porque OutlinedTextField mantiene el
    // cursor en el final cuando se trunca).
    val onChange: (String) -> Unit = { raw ->
        if (raw.length <= maxLength) {
            onValueChange(raw)
        } else {
            // Truncar silenciosamente (no permite tipear mas alla del max).
            onValueChange(raw.take(maxLength))
        }
    }

    // supportingText: "X/200" o, si hay error, el mensaje de error.
    val supportingText: (@Composable () -> Unit)? = when {
        errorMessage != null -> {
            { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
        }
        else -> {
            {
                Text(
                    "${value.length}/$maxLength",
                    color = if (value.length >= maxLength)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = textStyle,
        supportingText = supportingText,
    )
}
