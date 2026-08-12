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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.data.repository.TicketCacheRepository
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Kpi
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.components.CasinoLogoImage
import cl.csae.pos.ui.components.DeviceStatusBanner
import kotlinx.coroutines.launch

/**
 * Dashboard principal. Arriba un saludo + boton "Generar ticket" grande.
 * Abajo KPIs en grid + lista de los ultimos tickets del turno.
 *
 * Sprint 3.1.2: los KPIs se calculan desde el [TicketCacheRepository] (los
 * tickets generados en este turno). El boton de refresco re-baja el catalog.
 *
 * **Fix dashboard responsive (2026-08-12):** el layout se adapta al ancho de
 * pantalla. Antes el boton "GENERAR TICKET" media 96dp y el TopBar mostraba
 * email largo + subtitulo + 2 acciones, lo que en un celular de 6" portrait
 * hacia que el email se truncara y los KPIs cortaran el label. Ahora:
 *  - `screenWidthDp < 600` (celular): TopBar compacto (logo 32dp, 1 linea de
 *    titulo, subtitulo en una segunda linea corta), boton "Generar ticket"
 *    72dp / 20sp, KPIs en 2 columnas pero con cards de 88dp y value 22sp.
 *  - `screenWidthDp >= 600` (tablet): layout original (logo 40dp, 2 lineas
 *    de titulo, boton 96dp / 24sp, KPIs 112dp / 28sp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    usuario: UsuarioPos,
    onLogout: () -> Unit,
    onIrPos: () -> Unit,
    /**
     * F20: callback para ir a la pantalla de Configuracion (que es
     * donde se selecciona el dispositivo POS). Se usa desde el
     * DeviceStatusBanner.
     */
    onIrConfig: () -> Unit = {},
) {
    val tickets by ServiceLocator.ticketCache.tickets.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    // F18.3: logo del casino para el TopBar.
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)
    // F20: dispositivo POS seleccionado. Si es null, el CTA "Generar
    // ticket" se bloquea y se muestra un banner pidiendo seleccionarlo.
    val dispositivoActual by ServiceLocator.dispositivoPosActual.current
        .collectAsState(initial = null)

    val kpis = remember(tickets) {
        listOf(
            Kpi("Tickets hoy",   tickets.size.toString(),                "🎫"),
            Kpi("Monto total",   "$${ServiceLocator.ticketCache.montoTotalClp()}", "💰"),
            Kpi("Comensales unicos", ServiceLocator.ticketCache.comensalesUnicos().toString(), "👥"),
            Kpi("Servicios disponibles", ServiceLocator.catalogRepo.getCached()?.servicios?.size?.toString() ?: "-", "🍽"),
        )
    }

    val isCompact = LocalConfiguration.current.screenWidthDp < 600
    // Tamanos segun el ancho de pantalla.
    val logoSize = if (isCompact) 32.dp else 40.dp
    val ctaHeight = if (isCompact) 72.dp else 96.dp
    val ctaFontSize = if (isCompact) 20.sp else 24.sp
    val ctaIconSize = if (isCompact) 28.dp else 36.dp
    // En celular la KPI ahora va en 1 columna (Adaptive 200dp), asi que la
    // card puede ser mas baja sin que se corten los labels. En tablet sigue
    // en 2 columnas con la altura original.
    val kpiCardHeight = if (isCompact) 80.dp else 112.dp
    val kpiValueFontSize = if (isCompact) 24.sp else 28.sp
    val kpiIconFontSize = if (isCompact) 28.sp else 32.sp
    val contentPadding = if (isCompact) 12.dp else 16.dp
    val sectionSpacing = if (isCompact) 12.dp else 16.dp

    Scaffold(
        topBar = {
            TopAppBar(
                // F18.3: logo del casino a la izquierda del TopBar (navigation
                // icon). Reemplaza el menu hamburguesa default por la marca
                // visual del casino. Si no hay logo, cae al csae_logo.
                navigationIcon = {
                    CasinoLogoImage(
                        logoUrl = casinoTheme?.logoUrl,
                        contentDescription = casinoTheme?.razonSocial ?: "CSAE",
                        modifier = Modifier.size(logoSize).padding(start = 8.dp),
                    )
                },
                title = {
                    // En celular el titulo + subtitulo se cortan si el email
                    // es largo. Mostrar el email en una sola linea con ellipsis
                    // y el rol debajo. En tablet, el subtitulo incluye el
                    // restauranteId como antes.
                    Column {
                        Text(
                            "Hola, ${usuario.displayName}",
                            style = if (isCompact) MaterialTheme.typography.titleSmall
                                    else MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            if (isCompact) {
                                usuario.rol
                            } else {
                                "${usuario.rol}${usuario.restauranteId?.let { " - $it" } ?: ""}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            // F20: banner persistente del dispositivo POS. Si no hay
            // dispositivo seleccionado, el CTA "Generar ticket" se
            // bloquea y el operador tiene que ir a Configuracion.
            DeviceStatusBanner(
                onSelectDevice = onIrConfig,
                onChangeDevice = onIrConfig,
            )

            // CTA principal: POS
            Button(
                onClick = onIrPos,
                enabled = dispositivoActual != null,
                modifier = Modifier.fillMaxWidth().height(ctaHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Icon(Icons.Filled.PointOfSale, contentDescription = null, modifier = Modifier.size(ctaIconSize))
                Spacer(Modifier.width(12.dp))
                Text(
                    if (dispositivoActual == null) "SELECCIONA UN DISPOSITIVO" else "GENERAR TICKET",
                    fontSize = ctaFontSize,
                    fontWeight = FontWeight.Bold,
                )
            }

            refreshError?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "Turno actual",
                style = if (isCompact) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            // KPIs grid: Adaptive con minSize 200dp.
            // - En celular 6" portrait (~360dp): pasa a 1 columna automaticamente
            //   para que el label "Servicios disponibles" (22 chars) entre sin
            //   cortarse. Antes con Fixed(2) quedaba ~165dp por card y los
            //   labels se truncaban con ellipsis.
            // - En tablet 10" (~600dp+): 2 columnas de ~280dp, comodo.
            // - En tablet 13" landscape (~1280dp): 5+ columnas, pero como solo
            //   tenemos 4 KPIs, queda en 4 columnas (no se ve apretado).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(kpis) { kpi -> KpiCard(kpi, kpiCardHeight, kpiValueFontSize, kpiIconFontSize) }
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
private fun KpiCard(
    kpi: Kpi,
    cardHeight: androidx.compose.ui.unit.Dp,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    iconFontSize: androidx.compose.ui.unit.TextUnit,
) {
    // Fix dashboard KPI (2026-08-12): antes el layout era Column con el
    // icono arriba y value+label abajo (stacked vertical). En celular el
    // label se cortaba. Ahora es Row con icono a la izquierda y value+label
    // a la derecha (stacked), centrados verticalmente. Asi el label tiene
    // todo el ancho disponible a la derecha del icono y nunca se corta.
    Card(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Icono a la izquierda.
            Text(kpi.icono, fontSize = iconFontSize)
            // Value + label a la derecha, stacked.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kpi.value,
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    kpi.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
