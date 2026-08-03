package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.launch

/**
 * Pantalla principal del POS. Sprint 3.1.2: contra la API real.
 * Sprint 3.2: teclado numerico en RUT + menu inferior con Consumos / Config.
 * Sprint 3.2.1: RUTInputField con auto-formato + boton K, top bar con
 * "Cambiar modo" hacia mode_select.
 * Sprint 3.3 (fix UX):
 * - NumericKeypad en `bottomBar` del Scaffold (panel fijo abajo).
 * - Todo el contenido (RUT, comensal, servicios, boton GENERAR) scrolleable.
 * - Navegacion a Consumos / Config movida a la top bar (iconos).
 *
 * Flujo:
 *   1. Operador ingresa RUT del comensal (teclado en pantalla del bottomBar).
 *   2. La app busca en el catalog cacheado (descargado al login).
 *   3. Operador selecciona servicio habilitado para ese comensal.
 *   4. Boton "Generar ticket" -> `POST /api/v1/pos/consumos` con `IdempotencyKey`.
 *   5. Navega a TicketScreen con el ticket devuelto.
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
    val focusRut = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

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
        // Si no hay catalog en cache, intentar bajarlo.
        if (ServiceLocator.catalogRepo.getCached() == null) {
            scope.launch {
                val r = ServiceLocator.catalogRepo.refresh()
                r.onFailure { error = "No se pudo cargar el catalog: ${it.message}" }
            }
            return
        }
        val c = ServiceLocator.catalogRepo.buscarComensal(rut)
        when {
            c == null -> error = "No se encontro comensal con RUT $rut en este casino."
            c.servicios.isEmpty() -> {
                comensal = c
                error = "El comensal no tiene servicios habilitados."
            }
            else -> comensal = c
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
                title = "POS - Generar Ticket",
                subtitle = "Operador: ${usuario.displayName}",
                onCambiarModo = onCambiarModo,
                actions = {
                    // Sprint 3.3: navegacion movida del NavigationBar a la top bar.
                    IconButton(onClick = onIrConsumos) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Consumos",
                        )
                    }
                    IconButton(onClick = onIrConfig) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Configuracion",
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Panel fijo abajo: keypad full-width. NO es scrolleable.
            // Solo activo cuando no estamos cargando/generando.
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Paso 1: RUT (sin keypad embebido; el keypad esta en el bottomBar).
            Text("1. RUT del comensal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RutInputField(
                value = rut,
                onValueChange = {
                    rut = it
                    error = null
                },
                label = "12345678-5",
                placeholder = "12.345.678-9",
                enabled = !loading,
                isError = error != null,
                useCustomKeypad = false,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRut),
            )
            Button(
                onClick = { buscar() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Buscar comensal")
            }

            error?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Paso 2: comensal encontrado
            comensal?.let { c ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${c.nombre} ${c.apellido ?: ""}".trim(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text("RUT: ${c.rut}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Empresa: ${c.empresa}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                if (c.servicios.isNotEmpty()) {
                    // Paso 3: servicio
                    Text(
                        "2. Servicio a entregar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    c.servicios.forEach { s ->
                        ServicioCard(
                            servicio = s,
                            seleccionado = servicioSeleccionado?.id == s.id,
                            onClick = { servicioSeleccionado = s; error = null },
                        )
                    }

                    Button(
                        onClick = { generar() },
                        enabled = servicioSeleccionado != null && !loading,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Generando...", fontSize = 18.sp)
                        } else {
                            Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("GENERAR TICKET", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Padding inferior para que el contenido no quede pegado al
            // keypad cuando se hace scroll hasta el final.
            Spacer(Modifier.height(8.dp))
        }
    }

    // No usamos focusRut.requestFocus() porque el RUT se tipea con el keypad
    // del bottomBar (no con el teclado nativo). Mantengo el FocusRequester
    // en el codigo por si en el futuro se quiere saltar a teclado nativo.
    @Suppress("UNUSED_EXPRESSION") focusRut
}

@Composable
private fun ServicioCard(servicio: Servicio, seleccionado: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (seleccionado) MaterialTheme.colorScheme.secondary
                             else MaterialTheme.colorScheme.surface,
            contentColor = if (seleccionado) MaterialTheme.colorScheme.onSecondary
                           else MaterialTheme.colorScheme.onSurface,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(servicio.nombre, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(servicio.tipo, style = MaterialTheme.typography.bodySmall)
            }
            Text("$${servicio.precio}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
