package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.api.SucursalDto
import cl.csae.pos.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * **Sprint F3 (2026-08-13):** selector de sucursal para OperadorPos.
 *
 * Comportamiento:
 *  - **Post-login:** se muestra si el casino tiene >1 sucursal y el JWT no
 *    trae `sucursal_id`. Si el casino tiene 0 o 1, AppNavHost la skipea
 *    automaticamente.
 *  - **Desde Configuracion:** se muestra siempre que haya >=1 sucursal.
 *    El usuario puede cambiar la sucursal activa (que se persiste en
 *    DataStore y gatilla un re-bajado del catalog).
 *  - **"Casino completo"** se representa pasando `null` como sucursalId en
 *    el cache. El backend interpreta eso como "no filtrar por sucursal".
 *
 * El re-bajado del catalog NO se hace aca — el caller (AppNavHost o
 * ConfiguracionScreen) lo gatilla con `catalogRepo.refresh()` despues de
 * un cambio de sucursal exitoso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SucursalSelectScreen(
    /** Callback cuando el usuario selecciono una sucursal (o cerro el dialog). */
    onSucursalSelected: (sucursalId: String?) -> Unit = {},
    /** Permite volver atras (ej: boton back del topbar). null = sin back. */
    onBack: (() -> Unit)? = null,
) {
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme.collectAsState(initial = null)
    val sucursalActual by ServiceLocator.authStore.sucursalId.collectAsState(initial = null)
    val sucursales by ServiceLocator.authRepo.sucursalesDisponibles.collectAsState()
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloading by remember { mutableStateOf(false) }

    // Cargar la lista de sucursales si esta vacia (caso: navegamos desde
    // Configuracion sin haber pasado por login, o el login fallo en obtenerlas).
    LaunchedEffect(Unit) {
        if (sucursales.isEmpty()) {
            loading = true
            ServiceLocator.authRepo.me().onFailure { e ->
                error = e.message ?: "Error cargando sucursales"
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Seleccionar sucursal", fontWeight = FontWeight.SemiBold)
                        casinoTheme?.razonSocial?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver",
                            )
                        }
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                Icons.Filled.LocationCity,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Text(
                "Elija la sucursal donde esta operando",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                "Esta eleccion se persiste entre sesiones. Para cambiarla, vaya a Configuracion.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Cargando sucursales...")
                    }
                }
                error != null && sucursales.isEmpty() -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                error!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    loading = true
                                    error = null
                                    scope.launch {
                                        ServiceLocator.authRepo.me().onFailure { e ->
                                            error = e.message ?: "Error cargando sucursales"
                                        }
                                        loading = false
                                    }
                                },
                                enabled = !loading,
                            ) { Text("Reintentar") }
                        }
                    }
                }
                sucursales.isEmpty() -> {
                    // 0 sucursales: el casino opera sin subdivision. Mostrar
                    // un mensaje y un boton "Continuar" para que el operador
                    // continue al modo preferido.
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Este casino no tiene sucursales registradas.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Operara sobre el casino completo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { onSucursalSelected(null) }) {
                                Text("Continuar")
                            }
                        }
                    }
                }
                else -> {
                    sucursales.forEach { s ->
                        SucursalCard(
                            sucursal = s,
                            seleccionada = s.id == sucursalActual,
                            loading = reloading,
                            onSelect = {
                                reloading = true
                                error = null
                                scope.launch {
                                    ServiceLocator.authRepo.cambiarSucursal(s.id)
                                        .onSuccess { onSucursalSelected(s.id) }
                                        .onFailure { e ->
                                            error = e.message ?: "Error cambiando sucursal"
                                        }
                                    reloading = false
                                }
                            },
                        )
                    }
                }
            }

            error?.let { msg ->
                if (sucursales.isNotEmpty()) {
                    // Mostrar error inline (la lista sigue visible arriba).
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card outlined para una sucursal. Igual patron visual que el resto de
 * la app (ModoSelect, TipoDispositivo, etc). Muestra nombre + codigo +
 * direccion, y un check si es la activa.
 */
@Composable
private fun SucursalCard(
    sucursal: SucursalDto,
    seleccionada: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        enabled = !loading && !seleccionada,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        shape = MaterialTheme.shapes.medium,
        colors = if (seleccionada) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else ButtonDefaults.outlinedButtonColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sucursal.nombre,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (seleccionada) {
                    Text(
                        "ACTIVA",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                "Codigo: ${sucursal.codigo}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            sucursal.direccion?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
