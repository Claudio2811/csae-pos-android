package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.MockRepository
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos

/**
 * Pantalla principal del POS.
 * Flujo:
 *   1. Ingresar RUT (teclado numerico + guion)
 *   2. Mostrar comensal encontrado
 *   3. Seleccionar servicio habilitado
 *   4. Boton "Generar ticket" -> navegar a TicketScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun POSScreen(
    usuario: UsuarioPos,
    onBack: () -> Unit,
    onTicketGenerado: (Ticket) -> Unit,
) {
    var rut by remember { mutableStateOf("") }
    var comensal by remember { mutableStateOf<Comensal?>(null) }
    var servicioSeleccionado by remember { mutableStateOf<Servicio?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRut = remember { FocusRequester() }

    fun buscar() {
        comensal = null
        servicioSeleccionado = null
        error = null
        if (rut.isBlank()) {
            error = "Ingresa un RUT"
            return
        }
        val c = MockRepository.buscarPorRut(rut)
        if (c == null) {
            error = "No se encontro comensal con RUT $rut"
        } else if (c.serviciosHoy.isEmpty()) {
            error = "El comensal no tiene servicios asignados para hoy"
        } else {
            comensal = c
        }
    }

    fun generar() {
        val c = comensal
        val s = servicioSeleccionado
        if (c == null || s == null) {
            error = "Selecciona un servicio antes de generar el ticket"
            return
        }
        val r = MockRepository.generarTicket(c, s, usuario.displayName)
        r.onSuccess { t -> onTicketGenerado(t) }
         .onFailure { e -> error = e.message ?: "Error generando ticket" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generar Ticket") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Paso 1: RUT
            Text("1. RUT del comensal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = rut,
                onValueChange = { rut = it; error = null },
                label = { Text("12345678-5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRut),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { buscar() }),
                trailingIcon = {
                    IconButton(onClick = { buscar() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Buscar")
                    }
                },
            )
            Button(onClick = { buscar() }, modifier = Modifier.fillMaxWidth()) {
                Text("Buscar comensal")
            }

            if (error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
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
                                "${c.nombre} ${c.apellido ?: ""}",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text("RUT: ${c.rut}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Empresa: ${c.empresa}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                // Paso 3: servicio
                Text(
                    "2. Servicio a entregar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                c.serviciosHoy.forEach { s ->
                    ServicioCard(
                        servicio = s,
                        seleccionado = servicioSeleccionado?.id == s.id,
                        onClick = { servicioSeleccionado = s; error = null },
                    )
                }

                // Paso 4: generar
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { generar() },
                    enabled = servicioSeleccionado != null,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("GENERAR TICKET", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRut.requestFocus() }
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
