package cl.csae.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.components.CambiarModoTopBar
import cl.csae.pos.ui.components.NumericKeypad
import cl.csae.pos.ui.components.RutInputField
import cl.csae.pos.ui.components.normalizeRutInput
import cl.csae.pos.ui.components.formatRutForDisplay
import cl.csae.pos.util.FormatUtil
import kotlinx.coroutines.launch

/**
 * Pantalla principal del POS.
 *
 * F24 (2026-08-14): UI Polish v2.
 *   - Patron consistente con TotemScreen F24: paso 1 RUT, paso 2 comensal,
 *     paso 3 servicio + generar.
 *   - Card de comensal con avatar circular + nombre + RUT + empresa.
 *   - Card de servicio con icono circular tinted + nombre + tipo + precio.
 *   - Servicio seleccionado tiene border + check icon.
 *   - Servicios ya consumidos con alpha 0.4 + badge.
 *   - Boton "Generar ticket" Filled prominente.
 *   - Banner de error en errorContainer con icono.
 *   - Scroll vertical para que el keypad del bottom no tape el contenido.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun POSScreen(
    usuario: UsuarioPos,
    onCambiarModo: () -> Unit = {},
    onTicketGenerado: (Ticket) -> Unit,
    onIrConsumos: () -> Unit = {},
    onIrConfig: () -> Unit = {},
) {
    var rut by remember { mutableStateOf("") }
    var comensal by remember { mutableStateOf<Comensal?>(null) }
    var servicioSeleccionado by remember { mutableStateOf<Servicio?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sucursalId by ServiceLocator.authStore.sucursalId.collectAsState(initial = null)
    val sucursales by ServiceLocator.authRepo.sucursalesDisponibles.collectAsState()
    val sucursalActualNombre = remember(sucursalId, sucursales) {
        sucursales.firstOrNull { it.id == sucursalId }?.nombre ?: "Casino completo"
    }

    fun buscar() {
        if (rut.isBlank()) {
            error = "Ingresa un RUT"
            comensal = null
            servicioSeleccionado = null
            return
        }
        comensal = null
        servicioSeleccionado = null
        error = null
        if (ServiceLocator.catalogRepo.getCached() == null) {
            scope.launch {
                val r = ServiceLocator.catalogRepo.refresh()
                r.onFailure { error = "No se pudo cargar el catalog: ${it.message}" }
            }
            return
        }
        scope.launch {
            val r = ServiceLocator.catalogRepo.buscarComensalServiciosFrescos(rut)
            r.onSuccess { c ->
                when {
                    c.servicios.isEmpty() -> {
                        comensal = c
                        error = "El comensal no tiene servicios habilitados."
                    }
                    c.servicios.all { it.yaConsumido } -> {
                        comensal = c
                        error = "Este comensal ya consumio todos sus servicios hoy. Vuelve manana."
                    }
                    else -> {
                        comensal = c
                        servicioSeleccionado = null
                        error = null
                    }
                }
            }.onFailure {
                error = it.message ?: "No se encontro comensal con RUT $rut."
            }
        }
    }

    fun generar() {
        val c = comensal
        val s = servicioSeleccionado
        if (c == null || s == null) {
            error = "Selecciona un servicio antes de generar el ticket"
            return
        }
        if (loading) return
        loading = true
        error = null
        scope.launch {
            val r = ServiceLocator.consumoRepo.registrar(
                membresiaId = c.membresiaId,
                servicioId = s.id,
                operador = usuario.displayName,
            )
            loading = false
            r.onSuccess { t ->
                comensal = null
                servicioSeleccionado = null
                rut = ""
                ServiceLocator.ticketCache.agregar(t)
                onTicketGenerado(t)
            }.onFailure { e ->
                error = e.message ?: "Error generando ticket"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CambiarModoTopBar(
                title = "POS - Generar ticket",
                subtitle = "Operador: ${usuario.displayName}  -  Sucursal: $sucursalActualNombre",
                onCambiarModo = onCambiarModo,
                actions = {
                    IconButton(onClick = onIrConsumos) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Consumos")
                    }
                    IconButton(onClick = onIrConfig) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configuracion")
                    }
                },
            )
        },
        bottomBar = {
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
                        enabled = !loading,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Paso 1: RUT.
            PasoHeader(
                numero = 1,
                titulo = "RUT del comensal",
                icono = Icons.Filled.Badge,
                subtitulo = "Usa el teclado de abajo",
            )
            RutInputField(
                value = rut,
                onValueChange = {
                    rut = it
                    error = null
                },
                label = "12345678-5 o 12345678-K",
                placeholder = "12.345.678-9",
                enabled = !loading,
                isError = error != null,
                useCustomKeypad = false,
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { buscar() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buscar comensal", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            // Banner de error.
            error?.let { msg ->
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

            // Paso 2: comensal encontrado.
            comensal?.let { c ->
                PasoHeader(
                    numero = 2,
                    titulo = "Comensal encontrado",
                    icono = Icons.Filled.Person,
                    subtitulo = "Verifica que sea la persona correcta",
                )
                ComensalCardPOS(c)

                // Paso 3: servicios.
                if (c.servicios.isNotEmpty()) {
                    PasoHeader(
                        numero = 3,
                        titulo = "Servicio a entregar",
                        icono = Icons.Filled.Restaurant,
                        subtitulo = "Selecciona uno (los tachados ya fueron consumidos)",
                    )
                    c.servicios.forEach { s ->
                        ServicioCardPOS(
                            servicio = s,
                            seleccionado = servicioSeleccionado?.id == s.id,
                            onClick = {
                                if (!s.yaConsumido) {
                                    servicioSeleccionado = s
                                    error = null
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { generar() },
                        enabled = servicioSeleccionado != null && !loading,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Generando...", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("GENERAR TICKET", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * F24: header de cada paso del flujo. Numero circular tinted + titulo +
 * subtitulo. Patron consistente con el resto de la app.
 */
@Composable
private fun PasoHeader(
    numero: Int,
    titulo: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    subtitulo: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$numero",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                titulo,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitulo,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * F24: card de preview del comensal con avatar circular + info.
 */
@Composable
private fun ComensalCardPOS(c: Comensal) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.nombre.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${c.nombre} ${c.apellido ?: ""}".trim(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    "RUT ${formatRutForDisplay(c.rut.replace(".", "").replace("-", "").uppercase())}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                if (c.empresa.isNotEmpty()) {
                    Text(
                        c.empresa,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * F24: card de servicio con icono circular tinted + nombre + tipo +
 * precio. Seleccionado tiene border primario + check icon. Ya consumido
 * tiene alpha 0.4 + badge "Ya consumido".
 */
@Composable
private fun ServicioCardPOS(servicio: Servicio, seleccionado: Boolean, onClick: () -> Unit) {
    val consumido = servicio.yaConsumido
    val border = if (seleccionado) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                seleccionado -> MaterialTheme.colorScheme.primaryContainer
                consumido -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        border = border,
        enabled = !consumido,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (seleccionado) MaterialTheme.colorScheme.primary
                        else if (consumido) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = if (seleccionado) MaterialTheme.colorScheme.onPrimary
                    else if (consumido) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    servicio.nombre,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (consumido)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    servicio.tipo,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            when {
                seleccionado -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                )
                consumido -> Surface(
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
                servicio.precio > 0 -> Text(
                    "$${FormatUtil.formatClp(servicio.precio)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
