package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.api.ConsumoListItemDto
import cl.csae.pos.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Pantalla de Consumos del turno actual (sprint 3.2).
 *
 * Llama a `GET /api/v1/pos/consumos?desde=<UTC medianoche>&pageSize=500` y
 * muestra cada consumo con: hora (CL), RUT, nombre, servicio, precio CLP.
 *
 * Al final del dia hay un boton "Cerrar turno" como placeholder (sprint 3.2
 * no implementa el cierre real: eso queda para v1.2 con el backoffice de
 * facturacion).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumosScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var consumos by remember { mutableStateOf<List<ConsumoListItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCerrarDialog by remember { mutableStateOf(false) }

    fun cargar() {
        loading = true
        error = null
        scope.launch {
            val r = ServiceLocator.consumoRepo.listarConsumosDelTurno()
            loading = false
            r.onSuccess { consumos = it }
             .onFailure { error = it.message ?: "Error cargando consumos" }
        }
    }

    LaunchedEffect(Unit) { cargar() }

    val totalClp = consumos.sumOf { it.precioClp }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consumos del turno") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { cargar() }, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refrescar")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Total consumos", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "$${totalClp} CLP",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedButton(
                        onClick = { showCerrarDialog = true },
                    ) {
                        Text("Cerrar turno")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (consumos.isEmpty() && !loading) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No hay consumos en el turno actual.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(consumos, key = { it.consumoId }) { c ->
                        ConsumoRow(c)
                    }
                }
            }
        }
    }

    if (showCerrarDialog) {
        AlertDialog(
            onDismissRequest = { showCerrarDialog = false },
            title = { Text("Cerrar turno") },
            text = {
                Text("El cierre real del turno se hace desde el backoffice. Esta version solo limpia la lista local.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCerrarDialog = false
                    consumos = emptyList()
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCerrarDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun ConsumoRow(c: ConsumoListItemDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${c.comensalNombre} (${c.comensalRut})",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${c.servicioNombre} - N° ${c.ticketNumero ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    c.fechaConsumoUtc.take(19).replace("T", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(
                "$${c.precioClp}",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
            )
        }
    }
}
