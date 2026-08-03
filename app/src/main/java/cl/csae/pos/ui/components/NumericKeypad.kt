package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Teclado numerico custom en pantalla con la K del DV integrada.
 *
 * Distribucion 4 filas x 3 columnas:
 * ```
 * [ 1 ] [ 2 ] [ 3 ]
 * [ 4 ] [ 5 ] [ 6 ]
 * [ 7 ] [ 8 ] [ 9 ]
 * [ K ] [ 0 ] [ <- ]
 * ```
 *
 * Pensado para uso kiosko / tablet donde el teclado nativo no muestra la K
 * y/o queremos controlar exactamente que caracteres acepta el input. Cada
 * tecla es un Surface clickeable de ~60dp de alto. La K va en negrita y color
 * primary para destacar que es la letra del digito verificador.
 *
 * Sprint 3.2.2: lo agregamos para que el RUT se tipee completo desde un solo
 * teclado visible, sin necesidad del trailing K button separado.
 */
@Composable
fun NumericKeypad(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("K", "0", BACKSPACE_KEY),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    KeypadKey(
                        key = key,
                        enabled = enabled,
                        onClick = {
                            if (key == BACKSPACE_KEY) {
                                onBackspace()
                            } else {
                                onKeyPress(key)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                    )
                }
            }
        }
    }
}

private const val BACKSPACE_KEY = "BACK"

@Composable
private fun KeypadKey(
    key: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isK = key == "K"
    val isBack = key == BACKSPACE_KEY
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isK) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isK -> Text(
                    text = "K",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                isBack -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Borrar",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = key,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
