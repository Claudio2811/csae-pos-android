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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.api.ConsumoListItemDto
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.ui.components.CambiarModoTopBar
import cl.csae.pos.ui.components.DeviceStatusBanner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pantalla de Consumos (Sprint 3.2 + 3.4 + F20).
 *
 * Sprint 3.2: muestra los consumos del turno actual (desde 00:00 UTC de hoy).
 * Sprint 3.4: permite filtrar por rango de fechas con 2 DatePicker.
 * Sprint F20: bloquea la vista si no hay dispositivo POS seleccionado.
 *   El operador tiene que ir a Configuracion a elegir uno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumosScreen(
    onCambiarModo: () -> Unit = {},
    /**
     * F20: callback para ir a la pantalla de Configuracion cuando no
     * hay dispositivo POS seleccionado. Se usa desde el DeviceStatusBanner
     * y desde el mensaje "Ir a Configuracion".
     */
    onIrConfig: () -> Unit = {},
) {
    // F20: leemos el dispositivo actual para decidir si mostrar los
    // consumos o el mensaje de "asigna un dispositivo".
    val dispositivoActual by ServiceLocator.dispositivoPosActual.current
        .collectAsState(initial = null)

    val scope = rememberCoroutineScope()
    var consumos by remember { mutableStateOf<List<ConsumoListItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCerrarDialog by remember { mutableStateOf(false) }

    // Sprint 3.4: rango de fechas. Default = hoy (en timezone local).
    var desdeDate by remember { mutableStateOf(onlyDate(hoyLocal())) }
    var hastaDate by remember { mutableStateOf(onlyDate(hoyLocal())) }
    var showDesdePicker by remember { mutableStateOf(false) }
    var showHastaPicker by remember { mutableStateOf(false) }

    fun cargar() {
        loading = true
        error = null
        scope.launch {
            // Fix rango local->UTC (2026-08-12): antes `startOfDayUtc(desdeDate)`
            // trataba el Date (con hora local 00:00) como si fuera UTC, lo que
            // en Chile (UTC-4) movia el rango 4 horas. Resultado: consumos
            // hechos entre 00:00 CLT y 04:00 UTC no aparecian en el query.
            // Ahora `startOfDayLocalMillis` devuelve el timestamp UTC
            // equivalente al inicio del dia LOCAL, que es lo que el operador
            // espera al pedir "hoy".
            val desdeMs = startOfDayLocalMillis(desdeDate)
            val hastaMs = startOfDayLocalMillis(hastaDate) + 24L * 60L * 60L * 1000L
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val r = ServiceLocator.consumoRepo.listarConsumosEnRango(
                desdeUtc = fmt.format(Date(desdeMs)),
                hastaUtc = fmt.format(Date(hastaMs)),
            )
            loading = false
            r.onSuccess { consumos = it }
             .onFailure { error = it.message ?: "Error cargando consumos" }
        }
    }

    // F20 fix (2026-08-12): cargar al inicio del Composable (LaunchedEffect
    // con Unit) en vez de depender de `dispositivoActual`. Antes
    // `LaunchedEffect(dispositivoActual) { if (...) cargar() }` no se
    // ejecutaba si el operador ya tenia dispositivo seleccionado al
    // entrar (el LaunchedEffect solo se dispara cuando la key cambia), y
    // dejaba la pantalla en blanco hasta que el operador seleccionara uno
    // o cambiara algo. Ahora carga siempre al entrar.
    //
    // El bloqueo visual "sin dispositivo" sigue funcionando (la UI lo
    // muestra), pero la lista del backend se carga igual para que cuando
    // el operador elija dispositivo vea los datos sin delay.
    LaunchedEffect(Unit) {
        cargar()
    }

    val totalClp = consumos.sumOf { it.precioClp }
    val fmtChip = SimpleDateFormat("dd-MM-yyyy", Locale("es", "CL"))

    Scaffold(
        topBar = {
            CambiarModoTopBar(
                title = "Consumos",
                onCambiarModo = onCambiarModo,
                actions = {
                    IconButton(
                        onClick = { cargar() },
                        enabled = !loading && dispositivoActual != null,
                    ) {
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
            // F20: solo mostrar el bottomBar si hay dispositivo seleccionado.
            if (dispositivoActual != null) {
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
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // F20: banner del dispositivo arriba de todo.
            DeviceStatusBanner(
                onSelectDevice = onIrConfig,
                onChangeDevice = onIrConfig,
            )

            if (dispositivoActual == null) {
                // F20: bloquear la vista si no hay dispositivo.
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Sin dispositivo asignado",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Para ver los consumos de hoy debes seleccionar el dispositivo fisico que estas usando.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onIrConfig) {
                            Text("Ir a Configuracion")
                        }
                    }
                }
            } else {
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
    }

    // DatePickerDialogs
    if (showDesdePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = desdeDate.time)
        DatePickerDialog(
            onDismissRequest = { showDesdePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> desdeDate = onlyDate(Date(millis)) }
                    showDesdePicker = false
                    cargar()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDesdePicker = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = state)
        }
    }
    if (showHastaPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = hastaDate.time)
        DatePickerDialog(
            onDismissRequest = { showHastaPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> hastaDate = onlyDate(Date(millis)) }
                    showHastaPicker = false
                    cargar()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showHastaPicker = false }) { Text("Cancelar") } },
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCerrarDialog = false }) { Text("Cancelar") } },
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

/** Trunca a inicio del dia LOCAL (00:00:00 hora local). */
private fun onlyDate(d: Date): Date {
    val cal = Calendar.getInstance()
    cal.time = d
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

/**
 * Fix local->UTC (2026-08-12): dado un Date con hora local 00:00:00 (de
 * `onlyDate`), devuelve el timestamp UTC en milisegundos. Esto es lo que
 * el backend necesita para filtrar "consumos del dia X en hora local".
 *
 * Antes `startOfDayUtc(d)` cargaba el Date en un Calendar UTC, lo que
 * MOVIA el dia 4 horas en Chile (UTC-4), dejando fuera del rango los
 * consumos hechos entre 00:00 y 04:00 hora local.
 */
private fun startOfDayLocalMillis(d: Date): Long {
    val calLocal = Calendar.getInstance()  // local timezone del device
    calLocal.time = d
    calLocal.set(Calendar.HOUR_OF_DAY, 0)
    calLocal.set(Calendar.MINUTE, 0)
    calLocal.set(Calendar.SECOND, 0)
    calLocal.set(Calendar.MILLISECOND, 0)
    return calLocal.timeInMillis
}
