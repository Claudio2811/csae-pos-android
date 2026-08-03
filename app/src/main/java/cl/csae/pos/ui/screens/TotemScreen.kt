package cl.csae.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla del modo TOTEM (sprint 3.2).
 *
 * Flujo pensado para kiosko self-service (tablet dedicado):
 *   1. Comensal tipea su RUT.
 *   2. Tap "GENERAR TICKET".
 *   3. La pantalla consulta el comensal + servicios habilitados.
 *   4. Si tiene UN solo servicio, lo elige automaticamente.
 *   5. Si tiene varios, muestra chips clickeables.
 *   6. Genera el ticket (POST /pos/consumos).
 *   7. Muestra el ticket con QR por 3 segundos.
 *   8. Vuelve automaticamente al input vacio (loop).
 *
 * La pantalla NO muestra historial completo ni menus. Solo los ultimos 5
 * tickets generados en la sesion (para que el comensal pueda volver a ver
 * su ticket si la pantalla se confunde).
 *
 * **Importante:** el tótem no requiere login del operador. La idea es que
 * el dispositivo este fisicamente en modo kiosko y el comensal se identifica
 * por RUT. El backend autoriza el consumo segun la membresia del comensal.
 *
 * Como la API actual exige JWT (interceptor de OkHttp), en MVP el tótem
 * usa un usuario kiosko (mapeo SP -> restaurante con claim opcional, ver
 * CON-79). Si el operador no esta logueado, la API responde 401; en ese
 * caso la pantalla mostrara el error y permitira ir a Login TOTEM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotemScreen(
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
                    estado = EstadoTotem.Idle
                    error = "El comensal no tiene servicios habilitados."
                }
                c.servicios.size == 1 -> {
                    comensal = c
                    serviciosVisibles = c.servicios
                    servicioSeleccionado = c.servicios.first()
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1565C0)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "TÓTEM - CSAE",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onIrLoginTotem) {
                            Text("LOGIN OPERADOR", color = Color.White)
                        }
                        TextButton(onClick = onIrConfig) {
                            Text("CONFIG", color = Color.White)
                        }
                    }
                }
                Text(
                    "Tipea tu RUT y genera tu ticket",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 18.sp,
                )

                Spacer(Modifier.height(16.dp))

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
                        // Input RUT grande
                        OutlinedTextField(
                            value = rut,
                            onValueChange = { v ->
                                val filtrado = v.filter { it.isDigit() || it == '-' || it == '.' || it == 'k' || it == 'K' }
                                rut = filtrado
                                error = null
                            },
                            label = { Text("Tu RUT (12345678-5)") },
                            singleLine = true,
                            enabled = estado == EstadoTotem.Idle,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                            ),
                        )

                        if (estado == EstadoTotem.SeleccionServicio) {
                            Text(
                                "Selecciona tu servicio:",
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

                        Button(
                            onClick = {
                                if (estado == EstadoTotem.SeleccionServicio) {
                                    val c = comensal
                                    val s = servicioSeleccionado
                                    if (c != null && s != null) generarTicket(c, s)
                                } else {
                                    buscar()
                                }
                            },
                            enabled = when (estado) {
                                EstadoTotem.Idle -> rut.isNotBlank()
                                EstadoTotem.SeleccionServicio -> servicioSeleccionado != null
                                else -> false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1565C0),
                            ),
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                        ) {
                            if (estado == EstadoTotem.Buscando || estado == EstadoTotem.Generando) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color(0xFF1565C0),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (estado == EstadoTotem.Buscando) "Buscando..." else "Generando...",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (estado == EstadoTotem.SeleccionServicio) "GENERAR TICKET" else "BUSCAR",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }
                    }
                }

                error?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Indicador ultimo ticket
                if (estado == EstadoTotem.Idle && ultimosTickets.isNotEmpty()) {
                    Text(
                        "Ultimo ticket: ${ultimosTickets.first().numero} - ${ultimosTickets.first().comensal.nombre}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private enum class EstadoTotem { Idle, Buscando, SeleccionServicio, Generando, TicketMostrado }

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
                color = Color(0xFF1565C0),
            )
            Spacer(Modifier.height(8.dp))
            Text(nombre.trim(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("RUT: $rut", fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Servicio: $servicio", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Precio: $$precio", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1565C0))
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
