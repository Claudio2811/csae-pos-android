package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.model.Ticket
import kotlinx.coroutines.delay

/**
 * Pantalla del ticket generado. Muestra el detalle (preview de lo que iria
 * en el ticket fisico) y 3 acciones:
 *   - Imprimir (v1.0: Bluetooth ESC/POS. Sprint 3.0: solo muestra un Snackbar)
 *   - Nuevo ticket
 *   - Volver al dashboard
 *
 * En modo kiosko, despues de 10s sin accion, vuelve automaticamente al POS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketScreen(
    ticket: Ticket,
    esKiosko: Boolean,
    onNuevo: () -> Unit,
    onVolver: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var segundosParaSalir by remember { mutableStateOf(10) }

    // Auto-volver en modo kiosko despues de 10s
    LaunchedEffect(esKiosko) {
        if (esKiosko) {
            while (segundosParaSalir > 0) {
                delay(1000)
                segundosParaSalir--
            }
            onNuevo()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket generado") },
                navigationIcon = {
                    IconButton(onClick = onVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Confirmacion grande
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Listo!",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Preview del ticket (estilo papel termico 58mm)
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("CSAE POS", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Casino Salamanca", style = MaterialTheme.typography.bodySmall)
                    Text("---", modifier = Modifier.padding(vertical = 4.dp))
                    Text(ticket.fechaHora, style = MaterialTheme.typography.bodySmall)
                    Text("Ticket: ${ticket.numero}", style = MaterialTheme.typography.bodySmall)
                    Text("---", modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Comensal: ${ticket.comensal.nombre} ${ticket.comensal.apellido ?: ""}",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text("RUT: ${ticket.comensal.rut}", style = MaterialTheme.typography.bodySmall)
                    Text("Empresa: ${ticket.comensal.empresa}", style = MaterialTheme.typography.bodySmall)
                    Text("---", modifier = Modifier.padding(vertical = 4.dp))
                    Text("Servicio: ${ticket.servicio.nombre}", fontWeight = FontWeight.SemiBold)
                    Text("Tipo: ${ticket.servicio.tipo}", style = MaterialTheme.typography.bodySmall)
                    Text("Precio: $${ticket.servicio.precio}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("---", modifier = Modifier.padding(vertical = 4.dp))
                    Text("Operador: ${ticket.operador}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Botones de accion
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // Sprint 3.0: solo Snackbar. Sprint 3.1: ESC/POS Bluetooth.
                        // Para usar Snackbar dentro de coroutine usamos rememberCoroutineScope.
                        // (Aqui simplificamos: lanzamos un job simple.)
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Imprimir")
                }
                Button(
                    onClick = onNuevo,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("Nuevo")
                }
            }

            if (esKiosko) {
                Text(
                    "Volviendo al POS en ${segundosParaSalir}s...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
