package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.repository.TicketCacheRepository
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Kpi
import cl.csae.pos.model.UsuarioPos
import kotlinx.coroutines.launch

/**
 * Dashboard principal. Arriba un saludo + boton "Generar ticket" grande.
 * Abajo KPIs en grid + lista de los ultimos tickets del turno.
 *
 * Sprint 3.1.2: los KPIs se calculan desde el [TicketCacheRepository] (los
 * tickets generados en este turno). El boton de refresco re-baja el catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    usuario: UsuarioPos,
    onLogout: () -> Unit,
    onIrPos: () -> Unit,
) {
    val tickets by ServiceLocator.ticketCache.tickets.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }

    val kpis = remember(tickets) {
        listOf(
            Kpi("Tickets hoy",   tickets.size.toString(),                "🎫"),
            Kpi("Monto total",   "$${ServiceLocator.ticketCache.montoTotalClp()}", "💰"),
            Kpi("Comensales unicos", ServiceLocator.ticketCache.comensalesUnicos().toString(), "👥"),
            Kpi("Servicios disponibles", ServiceLocator.catalogRepo.getCached()?.servicios?.size?.toString() ?: "-", "🍽"),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hola, ${usuario.displayName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${usuario.rol}${usuario.restauranteId?.let { " - $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (refreshing) return@IconButton
                            refreshing = true
                            refreshError = null
                            scope.launch {
                                val r = ServiceLocator.catalogRepo.refresh()
                                refreshing = false
                                r.onFailure { refreshError = it.message ?: "Error re-bajando catalog." }
                            }
                        },
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refrescar catalog")
                        }
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "Cerrar sesion")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // CTA principal: POS
            Button(
                onClick = onIrPos,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Icon(Icons.Filled.PointOfSale, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text("GENERAR TICKET", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            if (refreshError != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        refreshError!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Turno actual", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

            // KPIs grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(kpis) { kpi -> KpiCard(kpi) }
            }

            // Ultimos tickets
            if (tickets.isNotEmpty()) {
                Text(
                    "Ultimos tickets (${tickets.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tickets.takeLast(5).reversed().forEach { t ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${t.comensal.nombre} ${t.comensal.apellido ?: ""}".trim(),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${t.servicio.nombre} - $${t.servicio.precio}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    t.fechaHora,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiCard(kpi: Kpi) {
    Card(
        modifier = Modifier.fillMaxWidth().height(112.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(kpi.icono, fontSize = 32.sp)
            Column {
                Text(
                    kpi.value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    kpi.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}
