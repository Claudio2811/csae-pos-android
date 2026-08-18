package cl.csae.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.components.CasinoLogoImage
import cl.csae.pos.ui.components.MinimalTopBar
import cl.csae.pos.ui.components.NumericKeypad
import cl.csae.pos.ui.components.RutInputField
import cl.csae.pos.ui.components.normalizeRutInput
import cl.csae.pos.ui.components.formatRutForDisplay
import cl.csae.pos.util.FormatUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla del modo TOTEM.
 *
 * F24 (2026-08-14): UI Polish v2.
 *   - Header con logo del casino + subtitulo "Tótem kiosko" (consistente
 *     con el resto de screens).
 *   - Estado RutInput: input con icono Badge + error en banner tinted +
 *     boton "Buscar" Filled.
 *   - Estado SeleccionServicio: card con preview del comensal encontrado
 *     (avatar circular + nombre + RUT + empresa) y servicios como cards
 *     con icono circular tinted + nombre + precio.
 *   - Estado Imprimiendo: misma idea pero mas minimal (icono grande +
 *     texto descriptivo + CircularProgressIndicator).
 *   - Estado TicketMostrado: reutiliza TicketScreen.
 *   - Servicios ya consumidos aparecen con alpha 0.4 + "Ya consumido"
 *     + icono tachado (visualmente distintos).
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
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // F24: Header con logo del casino.
            TotemHeader(razonSocial = casinoTheme?.razonSocial, logoUrl = casinoTheme?.logoUrl)

            Spacer(Modifier.height(24.dp))

            when (estado) {
                EstadoTotem.RutInput, EstadoTotem.Buscando -> RutInputStep(
                    rut = rut,
                    onRutChange = { rut = it; error = null },
                    onBuscar = ::buscar,
                    error = error,
                    loading = estado == EstadoTotem.Buscando,
                )
                EstadoTotem.SeleccionServicio -> comensal?.let { c ->
                    SeleccionServicioStep(
                        comensal = c,
                        servicios = serviciosVisibles,
                        onSelect = { generarTicket(c, it) },
                        onBack = { reset() },
                    )
                } ?: run {
                    LaunchedEffect(Unit) { reset() }
                }
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
 * F24: header con logo del casino + nombre + subtitulo "Tótem kiosko".
 * Patron consistente con el resto de la app (ModeSelect, Login).
 */
@Composable
private fun TotemHeader(razonSocial: String?, logoUrl: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            CasinoLogoImage(
                logoUrl = logoUrl,
                contentDescription = razonSocial ?: "CSAE",
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                razonSocial ?: "CSAE POS",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                "Totem kiosko",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Pantalla 1 - estado RutInput. Input RUT + boton Buscar (Filled).
 */
@Composable
private fun RutInputStep(
    rut: String,
    onRutChange: (String) -> Unit,
    onBuscar: () -> Unit,
    error: String?,
    loading: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Icono grande de "scaner RUT" tinted, con subtitulo.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Ingresa tu RUT",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Usa el teclado de abajo para escribirlo",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        RutInputField(
            value = rut,
            onValueChange = { onRutChange(normalizeRutInput(it)) },
            label = "RUT",
            placeholder = "12.345.678-9",
            enabled = !loading,
            isError = error != null,
            autoFormat = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Banner de error (mismo patron que LoginScreen F23).
        error?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onBuscar,
            enabled = !loading && rut.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Buscando...", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buscar", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * F24: pantalla 2 - SeleccionServicio. Card con preview del comensal
 * (avatar + nombre + RUT + empresa) y servicios como cards clickeables
 * con icono circular tinted + nombre + precio. Los ya consumidos
 * aparecen con alpha 0.4 y badge "Ya consumido".
 */
@Composable
private fun SeleccionServicioStep(
    comensal: Comensal,
    servicios: List<Servicio>,
    onSelect: (Servicio) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header con boton "Atras" + titulo.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atras")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Elige un servicio",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // Card preview del comensal.
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar circular con la inicial del nombre.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        comensal.nombre.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${comensal.nombre} ${comensal.apellido ?: ""}".trim(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        "RUT ${formatRutForDisplay(comensal.rut.replace(".", "").replace("-", "").uppercase())} - ${comensal.empresa}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Servicios como cards con icono circular.
        servicios.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            ServicioCardTotem(s, onSelect)
        }
    }
}

@Composable
private fun ServicioCardTotem(s: Servicio, onSelect: (Servicio) -> Unit) {
    val consumido = s.yaConsumido
    Card(
        onClick = { if (!consumido) onSelect(s) },
        enabled = !consumido,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (consumido)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (consumido) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icono circular tinted.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (consumido) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = if (consumido)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            // Nombre + tipo + badge.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.nombre,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (consumido)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    s.tipo,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            if (consumido) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Ya consumido",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else if (s.precio > 0) {
                Text(
                    "$${FormatUtil.formatClp(s.precio)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Pantalla 3 - estado de transicion "Imprimiendo Ticket".
 */
@Composable
private fun ImprimiendoStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Print,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Imprimiendo ticket",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Espera un momento, no retires el ticket hasta que termine.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun TicketMostradoStep(
    ticket: Ticket?,
    onNuevo: () -> Unit,
) {
    if (ticket == null) {
        LaunchedEffect(Unit) { onNuevo() }
        return
    }
    TicketScreen(
        ticket = ticket,
        esKiosko = true,
        onNuevo = onNuevo,
        onCambiarModo = {},
    )
}
