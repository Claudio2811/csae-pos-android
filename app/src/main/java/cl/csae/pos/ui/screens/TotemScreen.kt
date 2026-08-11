package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.components.MinimalTopBar
import cl.csae.pos.ui.components.normalizeRutInput
import cl.csae.pos.ui.components.AppTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Sprint F13 (2026-08-11): maxLength del input RUT.
// Backend espera hasta 9 chars canonicos (8 digitos + DV) y 12 con formato
// "12.345.678-K". El normalizeRutInput ya trunca a 9, pero el supportingText
// del field muestra el conteo en vivo para feedback.
private const val RUT_MAX_LENGTH = 12  // "12.345.678-K" es el formateado mas largo

/**
 * Pantalla del modo TOTEM (sprint 3.2 + 3.3 + F8).
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframes 3-6 del usuario.
 * Ahora son 4 estados visuales distintos, cada uno con su layout
 * minimalista, todos con TopBar minimal (solo icono de settings):
 *
 *   1. RutInput: input RUT + boton "Buscar" (wireframe 3-4)
 *   2. SeleccionServicio: 3 botones outlined (wireframe 5)
 *   3. Imprimiendo: "Imprimiendo Ticket" + icono impresora (wireframe 6)
 *   4. TicketMostrado: pantalla de ticket con QR (existente)
 *
 * Se removio:
 * - NumericKeypad del bottomBar (el operador usa teclado del sistema).
 * - CambiarModoTopBar (reemplazado por MinimalTopBar).
 * - Container azul TotemBlue (ahora usa background del theme).
 * - Cards de "un servicio" / "sin servicios" (reemplazado por
 *   auto-flow con error inline en RutInput).
 */
@Composable
fun TotemScreen(
    onSettings: () -> Unit = {},
    @Suppress("unused") onCambiarModo: () -> Unit = {},
    @Suppress("unused") onIrLoginTotem: () -> Unit = {},
    @Suppress("unused") onIrConfig: () -> Unit = {},
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
            // Si no hay catalog, intentar bajarlo.
            if (ServiceLocator.catalogRepo.getCached() == null) {
                val r = ServiceLocator.catalogRepo.refresh()
                if (r.isFailure) {
                    estado = EstadoTotem.RutInput
                    error = "No se pudo cargar el catalogo: ${r.exceptionOrNull()?.message}"
                    return@launch
                }
            }
            val c = ServiceLocator.catalogRepo.buscarComensal(rut)
            when {
                c == null -> {
                    estado = EstadoTotem.RutInput
                    error = "No se encontro comensal con RUT $rut en este casino."
                }
                c.servicios.isEmpty() -> {
                    estado = EstadoTotem.RutInput
                    error = "Este comensal no tiene servicios habilitados para hoy."
                }
                c.servicios.size == 1 -> {
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

        // Sprint F13 (2026-08-11): AppTextField con maxLength 12 (formato
        // "12.345.678-K"). KeyboardType.Text + KeyboardCapitalization.Characters
        // para que aparezca la K del DV. El auto-formato via normalizeRutInput
        // se encarga de poner puntos y guion antes del DV.
        AppTextField(
            value = rut,
            onValueChange = { onRutChange(normalizeRutInput(it)) },
            label = "RUT",
            placeholder = "12.345.678-9 o 12.345.678-K",
            maxLength = RUT_MAX_LENGTH,
            enabled = !loading,
            isError = error != null,
            errorMessage = null,  // El error general se muestra debajo del field
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search,
            ),
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
            OutlinedButton(
                onClick = { onSelect(s) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(s.nombre, fontSize = 26.sp, fontWeight = FontWeight.Medium)
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
