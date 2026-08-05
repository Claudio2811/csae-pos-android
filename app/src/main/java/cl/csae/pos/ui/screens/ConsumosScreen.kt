package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import cl.csae.pos.ui.components.CambiarModoTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pantalla de Consumos (Sprint 3.2 + 3.4).
 *
 * Sprint 3.2: muestra los consumos del turno actual (desde 00:00 UTC de hoy).
 *
 * Sprint 3.4: ahora permite filtrar por rango de fechas con 2 DatePicker.
 * Default: hoy (desde=hasta=00:00 local).
 * Chips rapidos: Hoy, Ayer, Ultimos 7 dias, Ultimo mes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumosScreen(
    onCambiarModo: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var consumos by remember { mutableStateOf<List<ConsumoListItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCerrarDialog by remember { mutableStateOf(false) }

    // Sprint 3.4: rango de fechas. Default = hoy (en timezone local).
    var desdeDate by remember { mutableStateOf(onlyDate(hoyLocal())) }
    var hastaDate by remember { mutableStateOf(onlyDate(hoyLocal())) }
    // DatePickerDialog state
    var showDesdePicker by remember { mutableStateOf(false) }
    var showHastaPicker by remember { mutableStateOf(false) }

    fun cargar() {
        loading = true
        error = null
        scope.launch {
            val desdeUtc = startOfDayUtc(desdeDate)
            val hastaUtc = startOfDayUtc(hastaDate) + 24 * 60 * 60 * 1000 // +1 dia exclusivo
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val r = ServiceLocator.consumoRepo.listarConsumosEnRango(
                desdeUtc = fmt.format(Date(desdeUtc)),
                hastaUtc = fmt.format(Date(hastaUtc)),
            )
            loading = false
            r.onSuccess { consumos = it }
             .onFailure { error = it.message ?: "Error cargando consumos" }
        }
    }

    LaunchedEffect(Unit) { cargar() }

    val totalClp = consumos.sumOf { it.precioClp }
    val fmtChip = SimpleDateFormat("dd-MM-yyyy", Locale("es", "CL"))

    Scaffold(
        topBar = {
            CambiarModoTopBar(
                title = "Consumos",
                onCambiarModo = onCambiarModo,
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
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Total (${consumos.size} servicios)", style = MaterialTheme.typography.bodySmall)
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
            // Filtro rango de fechas
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showDesdePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Desde: ${fmtChip.format(desdeDate)}")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showHastaPicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Hasta: ${fmtChip.format(hastaDate)}")
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { cargar() }, enabled = !loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Filtrar")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = {
                                val h = hoyLocal()
                                desdeDate = onlyDate(h)
                                hastaDate = onlyDate(h)
                                cargar()
                            },
                            label = { Text("Hoy") },
                        )
                        AssistChip(
                            onClick = {
                                val a = onlyDate(hoyLocal()).time - 24 * 60 * 60 * 1000
                                desdeDate = Date(a)
                                hastaDate = Date(a)
                                cargar()
                            },
                            label = { Text("Ayer") },
                        )
                        AssistChip(
                            onClick = {
                                val h = hoyLocal()
                                desdeDate = Date(onlyDate(h).time - 6L * 24 * 60 * 60 * 1000)
                                hastaDate = onlyDate(h)
                                cargar()
                            },
                            label = { Text("7 dias") },
                        )
                        AssistChip(
                            onClick = {
                                val cal = Calendar.getInstance()
                                cal.add(Calendar.MONTH, -1)
                                desdeDate = onlyDate(cal.time)
                                hastaDate = onlyDate(hoyLocal())
                                cargar()
                            },
                            label = { Text("1 mes") },
                        )
                    }
                }
            }

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
                        "No hay consumos en el rango seleccionado.",
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

    // DatePickerDialogs
    if (showDesdePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = desdeDate.time,
        )
        DatePickerDialog(
            onDismissRequest = { showDesdePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        desdeDate = onlyDate(Date(millis))
                    }
                    showDesdePicker = false
                    cargar()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDesdePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = state)
        }
    }
    if (showHastaPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = hastaDate.time,
        )
        DatePickerDialog(
            onDismissRequest = { showHastaPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        hastaDate = onlyDate(Date(millis))
                    }
                    showHastaPicker = false
                    cargar()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showHastaPicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = state)
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

// ============= Helpers locales (no agregan dependencias) =============

private fun hoyLocal(): Date = Calendar.getInstance().time

/** Trunca a inicio del dia LOCAL (00:00:00). */
private fun onlyDate(d: Date): Date {
    val cal = Calendar.getInstance()
    cal.time = d
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

/** Devuelve el epoch ms del inicio del dia d en timezone UTC. */
private fun startOfDayUtc(d: Date): Long {
    val local = onlyDate(d)
    val calLocal = Calendar.getInstance().apply { time = local }
    val calUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(
            calLocal.get(Calendar.YEAR),
            calLocal.get(Calendar.MONTH),
            calLocal.get(Calendar.DAY_OF_MONTH),
            0, 0, 0,
        )
        set(Calendar.MILLISECOND, 0)
    }
    return calUtc.timeInMillis
}
