package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.components.MinimalTopBar
import cl.csae.pos.ui.components.NumericKeypad
import cl.csae.pos.ui.components.normalizeRutInput
import cl.csae.pos.ui.components.RutInputField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla del modo TOTEM (sprint 3.2 + 3.3 + F8 + F18.2 + F20).
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframes 3-6 del usuario.
 * Ahora son 4 estados visuales distintos, cada uno con su layout
 * minimalista, todos con TopBar minimal (solo icono de settings):
 *
 *   1. RutInput: input RUT + boton "Buscar" (wireframe 3-4)
 *   2. SeleccionServicio: N botones outlined (wireframe 5). Los ya
 *      consumidos se deshabilitan con "Ya consumido" (F18.2 visual).
 *   3. Imprimiendo: "Imprimiendo Ticket" + icono impresora (wireframe 6)
 *   4. TicketMostrado: pantalla de ticket con QR (existente)
 *
 * F20 (2026-08-12): el `NumericKeypad` SI esta en el bottomBar (igual
 * que POS), con la K y la X. El `RutInputField` es readOnly y la unica
 * via de input es el keypad de abajo. Antes (F8) el operador tenia que
 * usar el teclado nativo qwerty del telefono, que ademas no mostraba
 * la K del DV en todos los dispositivos.
 *
 * Se removio (F8):
 * - CambiarModoTopBar (reemplazado por MinimalTopBar).
 * - Container azul TotemBlue (ahora usa background del theme).
 * - Cards de "un servicio" / "sin servicios" (reemplazado por
 *   auto-flow con error inline en RutInput).
 */
@Composable
fun TotemScreen(
    onSettings: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var rut by remember { mutableStateOf("") }
    var comensal by remember { mutableStateOf<Comensal?>(null) }
    var serviciosVisibles by remember { mutableStateOf<List<Servicio>>(emptyList()) }
    var estado by remember { mutableStateOf<EstadoTotem>(EstadoTotem.RutInput) }
    var error by remember { mutableStateOf<String?>(null) }
    var ultimoTicket by remember { mutableStateOf<Ticket?>(null) }
    var ultimosTickets by remember { mutableStateOf<List<Ticket>>(emptyList()) }

    fun reset() {
        rut = ""
        comensal = null
        serviciosVisibles = emptyList()
        estado = EstadoTotem.RutInput
        error = null
    }

    fun generarTicket(c: Comensal, s: Servicio) {
        estado = EstadoTotem.Imprimiendo
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
                // Volver al loop despues de 5s para que el comensal alcance a ver el QR.
                delay(5000)
                reset()
            }.onFailure { e ->
                estado = EstadoTotem.RutInput
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
            // F21 (2026-08-13): en vez de usar el cache local (que puede
            // estar desincronizado), consultamos el endpoint
            // /servicios-disponibles que SIEMPRE devuelve el estado actual
            // de la BD. Esto resuelve el bug "muestra servicios ya
            // consumidos" que el user reporto.
            //
            // Si la red falla, el metodo cae al cache local automaticamente
            // (con un warning en logcat). El backend igual valida la
            // regla unicoPorDia con 409, asi que no es un bug, solo
            // feedback visual perdido.
            if (ServiceLocator.catalogRepo.getCached() == null) {
                val r = ServiceLocator.catalogRepo.refresh()
                if (r.isFailure) {
                    estado = EstadoTotem.RutInput
                    error = "No se pudo cargar el catalogo: ${r.exceptionOrNull()?.message}"
                    return@launch
                }
            }
            val r = ServiceLocator.catalogRepo.buscarComensalServiciosFrescos(rut)
            if (r.isFailure) {
                estado = EstadoTotem.RutInput
                error = r.exceptionOrNull()?.message ?: "No se encontro comensal con RUT $rut."
                return@launch
            }
            val c = r.getOrNull()
            when {
                c == null -> {
                    estado = EstadoTotem.RutInput
                    error = "No se encontro comensal con RUT $rut en este casino."
                }
                c.servicios.isEmpty() -> {
                    estado = EstadoTotem.RutInput
                    error = "Este comensal no tiene servicios habilitados para hoy."
                }
                // F21: si TODOS los servicios ya fueron consumidos hoy,
                // mostrar el mensaje claro (no entrar al flujo de
                // seleccion).
                c.servicios.all { it.yaConsumido } -> {
                    estado = EstadoTotem.RutInput
                    error = "Este comensal ya consumio todos sus servicios hoy. Vuelve manana."
                }
                c.servicios.size == 1 && !c.servicios.first().yaConsumido -> {
                    comensal = c
                    serviciosVisibles = c.servicios
                    generarTicket(c, c.servicios.first())
                }
                else -> {
                    comensal = c
                    serviciosVisibles = c.servicios
                    estado = EstadoTotem.SeleccionServicio
                }
            }
        }
    }

    Scaffold(
        topBar = { MinimalTopBar(onSettings = onSettings) },
        // Fix teclado duplicado (2026-08-12): agregamos el NumericKeypad
        // al bottomBar para que el totem tenga un input consistente con
        // la caja (POS). Antes el operador tenia que usar el teclado
        // nativo qwerty del telefono, que ademas no mostraba la K del
        // DV en todos los dispositivos. Ahora el RutInputField es
        // readOnly y el unico input es el keypad de abajo.
        bottomBar = {
            if (estado == EstadoTotem.RutInput || estado == EstadoTotem.Buscando) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        NumericKeypad(
                            onKeyPress = { ch ->
                                rut = normalizeRutInput(rut + ch)
                            },
                            onBackspace = {
                                if (rut.isNotEmpty()) rut = rut.dropLast(1)
                            },
                            enabled = estado != EstadoTotem.Buscando,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            when (estado) {
                EstadoTotem.RutInput, EstadoTotem.Buscando -> RutInputStep(
                    rut = rut,
                    onRutChange = { rut = it; error = null },
                    onBuscar = ::buscar,
                    error = error,
                    loading = estado == EstadoTotem.Buscando,
                )
                EstadoTotem.SeleccionServicio -> SeleccionServicioStep(
                    servicios = serviciosVisibles,
                    onSelect = { generarTicket(comensal!!, it) },
                    onBack = { reset() },
                )
                EstadoTotem.Imprimiendo -> ImprimiendoStep()
                EstadoTotem.TicketMostrado -> TicketMostradoStep(
                    ticket = ultimoTicket,
                    onNuevo = { reset() },
                )
            }
        }
    }
}

private enum class EstadoTotem { RutInput, Buscando, SeleccionServicio, Imprimiendo, TicketMostrado }

/**
 * Pantalla 1 — wireframes 3 y 4. Input RUT + boton "Buscar" outlined.
 * El wireframe 4 es la misma pantalla con texto en el field, no un
 * estado distinto.
 */
@Composable
private fun RutInputStep(
    rut: String,
    onRutChange: (String) -> Unit,
    onBuscar: () -> Unit,
    error: String?,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Top),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            "Ingrese Rut",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(24.dp))

        // Fix RUT totem (2026-08-12): antes usabamos AppTextField con
        // normalizeRutInput que limpia el input a la forma canonica
        // (sin puntos/guion) y no aplicaba el VisualTransformation. Por eso
        // el operador veia "11111111" en vez de "11.111.111-1" mientras
        // tipeaba. Ahora usamos RutInputField que ya tiene el
        // VisualTransformation con formatRutForDisplay.
        // Ademas: readOnly=true porque el input viene del NumericKeypad
        // del bottomBar (no del teclado nativo, que ya no aparece).
        RutInputField(
            value = rut,
            onValueChange = { onRutChange(normalizeRutInput(it)) },
            label = "RUT",
            placeholder = "12.345.678-9 o 12.345.678-K",
            enabled = !loading,
            isError = error != null,
            autoFormat = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBuscar,
            enabled = !loading && rut.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text("Buscando...", fontSize = 22.sp)
            } else {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buscar", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Pantalla 2 — wireframe 5. Lista de servicios como botones outlined
 * grandes. Seleccionar dispara la generacion del ticket.
 *
 * **F18.2 fix visual (2026-08-12):** los servicios ya consumidos hoy por
 * este comensal aparecen con alpha reducida + texto "Ya consumido" debajo,
 * y el boton se deshabilita. Mismo patron que `POSScreen.ServicioCard`.
 * El backend igual valida con 409 la regla unicoPorDia, esto es solo
 * feedback visual para que el operador no intente generar 2 tickets del
 * mismo servicio.
 */
@Composable
private fun SeleccionServicioStep(
    servicios: List<Servicio>,
    onSelect: (Servicio) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            "Seleccione servicio",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(24.dp))

        servicios.forEach { s ->
            val consumido = s.yaConsumido
            OutlinedButton(
                onClick = { if (!consumido) onSelect(s) },
                enabled = !consumido,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (consumido) 112.dp else 96.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        s.nombre,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (consumido)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (consumido) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Ya consumido",
                            fontSize = 14.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onBack) {
            Text("Cancelar", fontSize = 16.sp)
        }
    }
}

/**
 * Pantalla 3 — wireframe 6. Estado de transicion: "Imprimiendo Ticket"
 * con icono grande de impresora, mientras se completa la llamada a
 * `consumoRepo.registrar`. No hay botones.
 */
@Composable
private fun ImprimiendoStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            "Imprimiendo Ticket",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Icon(
            Icons.Filled.Print,
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 3.dp,
        )

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Pantalla 4 — ticket con QR mostrado al comensal. Reutiliza el
 * TicketScreen existente via navegacion interna (no via NavController
 * para no perder el estado del flujo). Si el operador quiere volver
 * al inicio, tap "Nuevo".
 */
@Composable
private fun TicketMostradoStep(
    ticket: Ticket?,
    onNuevo: () -> Unit,
) {
    if (ticket == null) {
        // No deberia pasar, pero por seguridad.
        LaunchedEffect(Unit) { onNuevo() }
        return
    }
    TicketScreen(
        ticket = ticket,
        esKiosko = true,
        onNuevo = onNuevo,
        onCambiarModo = {},  // no-op: no hay back en minimal top bar
    )
}
