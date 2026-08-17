package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.csae.pos.di.ServiceLocator
import androidx.compose.runtime.collectAsState

/**
 * Banner persistente que muestra el dispositivo POS actual.
 *
 * **Estados:**
 * - **Con dispositivo**: card compacto verde/primario con el nombre
 *   + boton "Cambiar" opcional.
 * - **Sin dispositivo**: card amarillo/secondary con icono de warning
 *   + texto "Sin dispositivo asignado" + boton "Seleccionar" que
 *   abre la pantalla de Configuracion.
 *
 * **Sprint F20 (2026-08-12):** el operador del POS debe tener un
 * dispositivo seleccionado para registrar consumos (el backend lo
 * requiere para trazabilidad). Antes el dispositivo se elegia solo en
 * Configuracion (lejos del flujo del POS) y muchos operadores ni
 * se daban cuenta. Ahora hay un banner en TODAS las pantallas del
 * POS: Dashboard, POS, Consumos, Configuracion.
 *
 * Si no hay dispositivo, el flujo del POS esta bloqueado: el CTA
 * "Generar ticket" del Dashboard esta disabled y ConsumosScreen
 * muestra un mensaje en vez de la lista.
 */
@Composable
fun DeviceStatusBanner(
    modifier: Modifier = Modifier,
    onSelectDevice: () -> Unit,
    onChangeDevice: (() -> Unit)? = null,
) {
    val dispositivoState by ServiceLocator.dispositivoPosActual.current
        .collectAsState(initial = null)
    val dispositivo = dispositivoState // snapshot local para smart cast

    if (dispositivo == null) {
        // Sin dispositivo: warning + CTA a Configuracion.
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sin dispositivo asignado",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Para registrar consumos debes seleccionar el dispositivo fisico que estas usando.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                FilledTonalButton(
                    onClick = onSelectDevice,
                ) {
                    Text("Seleccionar")
                }
            }
        }
    } else {
        // Con dispositivo: card compacto informativo.
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        dispositivo.nombre ?: "Dispositivo sin nombre",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    val codigo = dispositivo.codigo
                    if (!codigo.isNullOrBlank()) {
                        Text(
                            codigo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (onChangeDevice != null) {
                    TextButton(onClick = onChangeDevice) {
                        Text("Cambiar")
                    }
                }
            }
        }
    }
}
