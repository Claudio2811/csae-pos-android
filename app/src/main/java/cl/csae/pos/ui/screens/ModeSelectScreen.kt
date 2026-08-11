package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.ui.components.MinimalTopBar

/**
 * Pantalla de seleccion de modo (sprint 3.2).
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframe 2. Antes tenia
 * botones grandes con colores (azul/verde/cafe) e iconos. Ahora son
 * 3 botones outlined minimalistas con solo texto, sobre fondo blanco
 * con un TopBar que solo tiene el icono de settings a la derecha.
 *
 * Se muestra al abrir la app, salvo que [AuthStore.modoPreferido] este set.
 * Cada boton mapea a uno de los modos:
 *   - TOTEM: kiosko self-service (input RUT + generar ticket)
 *   - POS:   operador atiende comensales con un POS tradicional
 *   - GARZON: garzon escanea el QR del comensal para confirmar consumo
 */
@Composable
fun ModeSelectScreen(
    onSelectModo: (String) -> Unit,
    onSettings: () -> Unit = {},
) {
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

            OutlinedButton(
                onClick = { onSelectModo("TOTEM") },
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Totem", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = { onSelectModo("POS") },
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Caja", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = { onSelectModo("GARZON") },
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Garzon", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
