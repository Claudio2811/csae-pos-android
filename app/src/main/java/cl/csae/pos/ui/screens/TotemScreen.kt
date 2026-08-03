package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.components.CambiarModoTopBar
import cl.csae.pos.ui.components.NumericKeypad
import cl.csae.pos.ui.components.RutInputField
import cl.csae.pos.ui.components.topBarColorsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla del modo TOTEM (sprint 3.2 + 3.3).
 *
 * Flujo pensado para kiosko self-service (tablet dedicado):
 *   1. Comensal tipea su RUT en el input de la parte superior.
 *   2. Tap "GENERAR TICKET" (o el unico servicio si hay uno solo).
 *   3. La pantalla consulta el comensal + servicios habilitados.
 *   4. Si tiene UN solo servicio, lo elige automaticamente.
 *   5. Si tiene varios, muestra chips clickeables con contador.
 *   6. Si no tiene, muestra card rojo explicativo.
 *   7. Genera el ticket (POST /pos/consumos).
 *   8. Muestra el ticket con QR por 3 segundos.
 *   9. Vuelve automaticamente al input vacio (loop).
 *
 * Sprint 3.3 (fix UX):
 * - NumericKeypad vive en el `bottomBar` del Scaffold (panel fijo abajo).
 * - Todo el resto (RUT, servicios, feedback, boton) vive en un Column con
 *   verticalScroll, asi el operador puede scrollear para ver TODOS los
 *   servicios en pantallas chicas o si hay muchos.
 * - RutInputField ya no embebe el keypad: el flag `useCustomKeypad = false`
 *   delega la captura al NumericKeypad del bottomBar.
 * - El keypad NO se renderiza cuando el estado es TicketMostrado (ahi ocupa
 *   toda la pantalla el QR); ver `keypadVisible` abajo.
 */
private val TotemBlue = Color(0xFF1565C0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotemScreen(
    onCambiarModo: () -> Unit = {},
    onIrLoginTotem: () -> Unit = {},
    onIrConfig: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var rut by remember { mutableStateOf("") }
    var comensal by remember { mutableStateOf<Comensal?>(null) }
    var serviciosVisibles by remember { mutableStateOf<List<Servicio>>(emptyList()) }
    var servicioSeleccionado by remember { mutableStateOf<Servicio?>(null) }
    var estado by remember { mutableStateOf<EstadoTotem>(EstadoTotem.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var ultimoTicket by remember { mutableStateOf<Ticket?>(null) }
    var ultimosTickets by remember { mutableStateOf<List<Ticket>>(emptyList()) }

    fun reset() {
        rut = ""
        comensal = null
        serviciosVisibles = emptyList()
        servicioSeleccionado = null
        estado = EstadoTotem.Idle
        error = null
    }

    fun generarTicket(c: Comensal, s: Servicio) {
        estado = EstadoTotem.Generando
        error = null
        scope.launch {
            val r = ServiceLocator.consumoRepo.registrar(
                membresiaId = c.membresiaId,
                servicioId = s.id,
                operador = "TOTEM",
            )
            r.onSuccess { t ->
                ultimoTicket = t
                ultimosTickets = (listOf(t) + ultimosTickets).take(5)
                estado = EstadoTotem.TicketMostrado
                // Volver al loop despues de 3s.
                delay(3000)
                reset()
            }.onFailure { e ->
                estado = EstadoTotem.Idle
                error = e.message ?: "Error generando ticket"
            }
        }
    }

    fun buscar() {
        if (rut.isBlank()) {
            error = "Ingresa un RUT"
            return
        }
        error = null
        estado = EstadoTotem.Buscando
        scope.launch {
            // Si no hay catalog, intentar bajarlo.
            if (ServiceLocator.catalogRepo.getCached() == null) {
                val r = ServiceLocator.catalogRepo.refresh()
                if (r.isFailure) {
                    estado = EstadoTotem.Idle
                    error = "No se pudo cargar el catalog: ${r.exceptionOrNull()?.message}"
                    return@launch
                }
            }
            val c = ServiceLocator.catalogRepo.buscarComensal(rut)
            when {
                c == null -> {
                    estado = EstadoTotem.Idle
                    error = "No se encontro comensal con RUT $rut en este casino."
                }
                c.servicios.isEmpty() -> {
                    comensal = c
                    serviciosVisibles = emptyList()
                    estado = EstadoTotem.SinServicios
                }
                c.servicios.size == 1 -> {
                    comensal = c
                    serviciosVisibles = c.servicios
                    servicioSeleccionado = c.servicios.first()
                    estado = EstadoTotem.UnServicio
                    // Autogenerar ticket.
                    generarTicket(c, c.servicios.first())
                }
                else -> {
                    comensal = c
                    serviciosVisibles = c.servicios
                    servicioSeleccionado = null
                    estado = EstadoTotem.SeleccionServicio
                }
            }
        }
    }

    // Mientras se muestra el ticket con QR el keypad se oculta para dar
    // todo el espacio vertical al QR (que es lo que mira el comensal).
    val keypadVisible = estado != EstadoTotem.TicketMostrado

    Scaffold(
        containerColor = TotemBlue,
        topBar = {
            CambiarModoTopBar(
                title = "TOTEM - CSAE",
                subtitle = "Kiosko self-service",
                onCambiarModo = onCambiarModo,
                actions = {
                    TextButton(onClick = onIrLoginTotem) {
                        Text("LOGIN", color = Color.White)
                    }
                    TextButton(onClick = onIrConfig) {
                        Text("CONFIG", color = Color.White)
                    }
                },
                colors = topBarColorsFor(TotemBlue),
            )
        },
        bottomBar = {
            if (keypadVisible) {
                // Panel fijo abajo: keypad full-width. NO es scrolleable.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        NumericKeypad(
                            onKeyPress = { ch ->
                                rut = cl.csae.pos.ui.components.normalizeRutInput(rut + ch)
                            },
                            onBackspace = {
                                if (rut.isNotEmpty()) rut = rut.dropLast(1)
                            },
                            enabled = estado == EstadoTotem.Idle || estado == EstadoTotem.Buscando,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Tipea tu RUT y genera tu ticket",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 18.sp,
                )

                when (estado) {
                    EstadoTotem.TicketMostrado -> {
                        ultimoTicket?.let { t ->
                            TicketMostradoCard(
                                nombre = t.comensal.nombre + " " + (t.comensal.apellido ?: ""),
                                rut = t.comensal.rut,
                                servicio = t.servicio.nombre,
                                precio = t.precio.takeIf { it > 0 } ?: t.servicio.precio,
                                ticketNumero = t.numero,
                                qrToken = t.qrToken,
                            )
                        }
                    }
                    else -> {
                        // Input RUT grande SIN keypad embebido (el keypad esta en bottomBar).
                        RutInputField(
                            value = rut,
                            onValueChange = {
                                rut = it
                                error = null
                            },
                            label = "Tu RUT",
                            placeholder = "12.345.678-9",
                            enabled = estado == EstadoTotem.Idle || estado == EstadoTotem.Buscando,
                            isError = error != null,
                            useCustomKeypad = false,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        when (estado) {
                            EstadoTotem.SinServicios -> {
                                comensal?.let { c ->
                                    SinServiciosCard(nombre = "${c.nombre} ${c.apellido ?: ""}".trim(), rut = c.rut)
                                }
                            }
                            EstadoTotem.UnServicio -> {
                                serviciosVisibles.firstOrNull()?.let { s ->
                                    UnServicioCard(
                                        nombre = "${comensal?.nombre ?: ""} ${comensal?.apellido ?: ""}".trim(),
                                        servicio = s,
                                    )
                                }
                            }
                            EstadoTotem.SeleccionServicio -> {
                                Text(
                                    "${serviciosVisibles.size} servicios disponibles. Selecciona uno:",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                FlowRowChips(
                                    items = serviciosVisibles,
                                    selected = servicioSeleccionado,
                                    onSelect = { servicioSeleccionado = it },
                                )
                            }
                            else -> { /* Idle, Buscando, Generando -> solo spinner abajo */ }
                        }

                        if (estado == EstadoTotem.Buscando || estado == EstadoTotem.Generando) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color.White,
                                )
                                Text(
                                    when (estado) {
                                        EstadoTotem.Buscando -> "Buscando comensal..."
                                        EstadoTotem.Generando -> "Generando ticket..."
                                        else -> ""
                                    },
                                    color = Color.White,
                                    fontSize = 18.sp,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                when (estado) {
                                    EstadoTotem.SeleccionServicio -> {
                                        val c = comensal
                                        val s = servicioSeleccionado
                                        if (c != null && s != null) generarTicket(c, s)
                                    }
                                    else -> buscar()
                                }
                            },
                            enabled = when (estado) {
                                EstadoTotem.Idle -> rut.isNotBlank()
                                EstadoTotem.SeleccionServicio -> servicioSeleccionado != null
                                else -> false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = TotemBlue,
                            ),
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                        ) {
                            Icon(
                                when (estado) {
                                    EstadoTotem.SeleccionServicio -> Icons.Filled.Restaurant
                                    else -> Icons.Filled.Search
                                },
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (estado) {
                                    EstadoTotem.SeleccionServicio -> "GENERAR TICKET"
                                    else -> "BUSCAR"
                                },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }

                error?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020)),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Error, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(msg, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Indicador ultimo ticket
                if (estado == EstadoTotem.Idle && ultimosTickets.isNotEmpty()) {
                    Text(
                        "Ultimo ticket: ${ultimosTickets.first().numero} - ${ultimosTickets.first().comensal.nombre}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Padding inferior para que el contenido no quede pegado al
                // keypad cuando se hace scroll hasta el final.
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private enum class EstadoTotem { Idle, Buscando, SeleccionServicio, Generando, TicketMostrado, UnServicio, SinServicios }

@Composable
private fun FlowRowChips(
    items: List<Servicio>,
    selected: Servicio?,
    onSelect: (Servicio) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { s ->
            FilterChip(
                selected = selected?.id == s.id,
                onClick = { onSelect(s) },
                label = {
                    Column {
                        Text(s.nombre, fontWeight = FontWeight.SemiBold)
                        Text("$${s.precio}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                leadingIcon = { Icon(Icons.Filled.Restaurant, contentDescription = null) },
            )
        }
    }
}

/**
 * Sprint 3.2.1: card verde que muestra el unico servicio del comensal y lo
 * auto-selecciona. Mas amigable que la pantalla anterior.
 */
@Composable
private fun UnServicioCard(nombre: String, servicio: Servicio) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(nombre, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    "Servicio: ${servicio.nombre} - $${servicio.precio}",
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/**
 * Sprint 3.2.1: card rojo que indica que el comensal no tiene servicios
 * habilitados hoy. Antes era un mensaje de error generico.
 */
@Composable
private fun SinServiciosCard(nombre: String, rut: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(nombre, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("RUT: $rut", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "No hay servicios habilitados para este comensal hoy.",
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun TicketMostradoCard(
    nombre: String,
    rut: String,
    servicio: String,
    precio: Int,
    ticketNumero: String,
    qrToken: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "TICKET GENERADO",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TotemBlue,
            )
            Spacer(Modifier.height(8.dp))
            Text(nombre.trim(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("RUT: $rut", fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Servicio: $servicio", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Precio: $$precio", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = TotemBlue)
            Spacer(Modifier.height(8.dp))
            Text("N° $ticketNumero", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            // QR del ticket: usa el qrToken real del backend (Sprint 3.2). Si
            // por algun motivo no viene, fallback a uno sintetico para no romper
            // la UI (tickets viejos sin qrToken).
            val qrReal = qrToken ?: "CSAE-$ticketNumero-$rut"
            val qrBitmap = remember(qrReal) {
                ServiceLocator.printerService.generarQrBitmap(qrReal, sizePx = 256)
            }
            qrBitmap?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "QR del ticket",
                    modifier = Modifier.size(200.dp),
                )
            }
        }
    }
}
