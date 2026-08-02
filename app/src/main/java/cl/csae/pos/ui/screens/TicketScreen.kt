package cl.csae.pos.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Ticket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla del ticket generado. Muestra el detalle (preview de lo que iria
 * en el ticket fisico) y 3 acciones:
 *   - Imprimir (Bluetooth ESC/POS sobre dispositivos emparejados).
 *   - Nuevo ticket (vuelve al POS).
 *   - Volver al dashboard.
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var segundosParaSalir by remember { mutableStateOf(10) }
    var showPrinterDialog by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }

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

    // Guardar el ticket en el cache al mostrarlo (asi aparece en el dashboard).
    LaunchedEffect(ticket.numero) {
        ServiceLocator.ticketCache.agregar(ticket)
    }

    // Launcher de permiso BLUETOOTH_CONNECT (Android 12+)
    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showPrinterDialog = true
        else scope.launch { snackbar.showSnackbar("Sin permiso de Bluetooth no se puede imprimir.") }
    }

    fun pedirPermisoYMostrarDialogo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) showPrinterDialog = true
            else btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            showPrinterDialog = true
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
                        "Comensal: ${ticket.comensal.nombre} ${ticket.comensal.apellido ?: ""}".trim(),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text("RUT: ${ticket.comensal.rut}", style = MaterialTheme.typography.bodySmall)
                    if (ticket.comensal.empresa.isNotEmpty()) {
                        Text("Empresa: ${ticket.comensal.empresa}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("---", modifier = Modifier.padding(vertical = 4.dp))
                    Text("Servicio: ${ticket.servicio.nombre}", fontWeight = FontWeight.SemiBold)
                    Text("Tipo: ${ticket.servicio.tipo}", style = MaterialTheme.typography.bodySmall)
                    Text("Precio: $${ticket.precio.takeIf { it > 0 } ?: ticket.servicio.precio}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    onClick = { pedirPermisoYMostrarDialogo() },
                    enabled = !printing,
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

    if (showPrinterDialog) {
        PrinterPickerDialog(
            onDismiss = { showPrinterDialog = false },
            onSelect = { device ->
                showPrinterDialog = false
                printing = true
                scope.launch {
                    val r = ServiceLocator.printerService.imprimir(device.address, ticket)
                    // Independientemente del resultado de la impresion fisica,
                    // marcamos el ticket como impreso en el backend.
                    ticket.ticketId?.let { id ->
                        ServiceLocator.consumoRepo.marcarImpreso(id, device.address)
                    }
                    printing = false
                    r.onSuccess { snackbar.showSnackbar("Impreso OK en ${device.name ?: device.address}.") }
                     .onFailure { snackbar.showSnackbar(it.message ?: "Error imprimiendo.") }
                }
            },
        )
    }
}

@Composable
private fun PrinterPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (BluetoothDevice) -> Unit,
) {
    val emparejados = remember { ServiceLocator.printerService.listarEmparejados() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar impresora") },
        text = {
            if (emparejados.isEmpty()) {
                Text(
                    "No hay impresoras Bluetooth emparejadas. Ve a Settings > Bluetooth del dispositivo y empareja tu impresora 58mm.",
                )
            } else {
                Column {
                    emparejados.forEach { d ->
                        val nombre = try { d.name } catch (_: SecurityException) { null } ?: d.address
                        TextButton(
                            onClick = { onSelect(d) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(nombre, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}
