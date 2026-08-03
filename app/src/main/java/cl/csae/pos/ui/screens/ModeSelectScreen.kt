package cl.csae.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pantalla de seleccion de modo (sprint 3.2).
 *
 * Se muestra al abrir la app, salvo que [AuthStore.modoPreferido] este set.
 * Cada boton mapea a uno de los modos:
 *   - TOTEM: kiosko self-service (input RUT + generar ticket)
 *   - POS:   operador atiende comensales con un POS tradicional
 *   - GARZON: garzon escanea el QR del comensal para confirmar consumo
 *
 * Los colores (azul/verde/café) son los pedidos por el usuario. Como Material3
 * no trae "café" ni "azul" per se, usamos tonos custom fijos para identificarlos
 * de un vistazo en terreno.
 */
@Composable
fun ModeSelectScreen(
    onSelectModo: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                "CSAE POS",
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Selecciona el modo del dispositivo",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(8.dp))

            // TOTEM (azul)
            ModeButton(
                label = "TÓTEM",
                subtitle = "Self-service para comensales",
                color = Color(0xFF1565C0),  // azul
                onContent = Color.White,
                icon = Icons.Filled.LocalCafe,
                onClick = { onSelectModo("TOTEM") },
            )

            // POS (verde)
            ModeButton(
                label = "POS",
                subtitle = "Operador atiende a los comensales",
                color = Color(0xFF2E7D32),  // verde
                onContent = Color.White,
                icon = Icons.Filled.PointOfSale,
                onClick = { onSelectModo("POS") },
            )

            // GARZON (café)
            ModeButton(
                label = "GARZÓN",
                subtitle = "Validar tickets escaneando QR",
                color = Color(0xFF6D4C41),  // café
                onContent = Color.White,
                icon = Icons.Filled.QrCodeScanner,
                onClick = { onSelectModo("GARZON") },
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Tip: desde Configuración puedes fijar un modo por defecto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    subtitle: String,
    color: Color,
    onContent: Color,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = onContent,
        ),
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp).background(Color.Transparent))
            Column(horizontalAlignment = Alignment.Start) {
                Text(label, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, fontSize = 14.sp, color = onContent.copy(alpha = 0.85f))
            }
        }
    }
}
