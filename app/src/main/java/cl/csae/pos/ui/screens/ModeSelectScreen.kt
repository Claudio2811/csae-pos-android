package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.ui.components.MinimalTopBar

/**
 * Pantalla de seleccion de modo (sprint 3.2 + F9).
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframe 2 — 3 botones outlined
 * minimalistas con solo texto, sobre fondo blanco con TopBar minimal
 * (solo icono de settings).
 *
 * Sprint F9 (2026-08-11): filtrado por rol. Antes mostraba siempre los
 * 3 modos. Ahora solo aparecen los que el rol del usuario puede usar:
 *
 *   - OperadorPos:        solo "Caja"
 *   - Garzon:             solo "Garzon"
 *   - AdminCasino:        los 3 (puede supervisar cualquier modo)
 *   - SupervisorCasino:   los 3
 *   - SuperAdmin:         los 3
 *   - AdminEmpresa:       no deberia llegar aca (LoginScreen lo rechaza),
 *                         pero si llega, no ve ningun boton.
 *
 * El catalogo de modos disponibles se computa segun el `rol` del
 * UsuarioPos (string que viene del JWT y se persiste en DataStore).
 */
@Composable
fun ModeSelectScreen(
    onSelectModo: (String) -> Unit,
    onSettings: () -> Unit = {},
    usuario: UsuarioPos? = null,
) {
    // F18.x: casino actual (cacheado en AuthStore). Mostramos el nombre como
    // subtitulo para que el operador sepa en que casino esta parado.
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)
    val modosDisponibles = remember(usuario?.rol) {
        when (usuario?.rol) {
            "OperadorPos" -> listOf("POS" to "Caja")
            "Garzon" -> listOf("GARZON" to "Garzon")
            "AdminCasino", "SupervisorCasino", "SuperAdmin" -> listOf(
                "TOTEM" to "Totem",
                "POS" to "Caja",
                "GARZON" to "Garzon",
            )
            else -> emptyList()  // AdminEmpresa / null / desconocido
        }
    }

    Scaffold(
        topBar = { MinimalTopBar(onSettings = onSettings) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.weight(1f))

            // F18.x: nombre del casino como subtitulo. El logo del casino
            // (data URI inline del F17) todavia no se muestra aca — eso
            // queda para F18.3 cuando se agregue un helper para decodear
            // data:image/png;base64,... a ImageBitmap en Compose.
            casinoTheme?.razonSocial?.let { nombre ->
                Text(
                    nombre,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            if (modosDisponibles.isEmpty()) {
                // Empresa user o rol desconocido — LoginScreen deberia haber
                // bloqueado, pero por seguridad mostramos un mensaje aqui.
                Text(
                    "Tu cuenta no tiene permisos para usar esta app.\nContacta al administrador del casino.",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            } else {
                modosDisponibles.forEach { (key, label) ->
                    OutlinedButton(
                        onClick = { onSelectModo(key) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(label, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
