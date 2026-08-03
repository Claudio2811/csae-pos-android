package cl.csae.pos.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.screens.ConfiguracionScreen
import cl.csae.pos.ui.screens.ConsumosScreen
import cl.csae.pos.ui.screens.DashboardScreen
import cl.csae.pos.ui.screens.GarzonScreen
import cl.csae.pos.ui.screens.LoginScreen
import cl.csae.pos.ui.screens.ModeSelectScreen
import cl.csae.pos.ui.screens.POSScreen
import cl.csae.pos.ui.screens.TicketScreen
import cl.csae.pos.ui.screens.TotemScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object Routes {
    // Sprint 3.2
    const val MODE_SELECT = "mode_select"
    const val LOGIN_TOTEM = "login_totem"
    const val LOGIN_GARZON = "login_garzon"
    const val TOTEM = "totem"
    const val POS = "pos"
    const val CONSUMOS = "consumos"
    const val CONFIGURACION = "configuracion"
    const val GARZON = "garzon"

    // Existentes
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val TICKET = "ticket/{numero}"
    fun ticket(numero: String) = "ticket/$numero"
}

// Removido: usamos un inline remember mas simple en CsaeNavHost.

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun CsaeNavHost() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var usuario by remember { mutableStateOf<UsuarioPos?>(null) }
    var kiosko by remember { mutableStateOf(false) }
    var ultimoTicketNumero by remember { mutableStateOf<String?>(null) }
    var startRoute by remember { mutableStateOf<String?>(null) }

    val cambiarModo = remember(nav) {
        // Navega a mode_select limpiando todo el back stack.
        { nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } } }
    }

    // Sprint 3.2: decidir start destination segun modoPreferido + isLoggedIn.
    LaunchedEffect(Unit) {
        val modo = ServiceLocator.authStore.getModoPreferido()
        val isLoggedIn = ServiceLocator.authRepo.isLoggedIn.first()
        startRoute = when {
            modo == "TOTEM" && isLoggedIn -> Routes.TOTEM
            modo == "POS" && isLoggedIn -> Routes.DASHBOARD
            modo == "GARZON" && isLoggedIn -> Routes.GARZON
            else -> Routes.MODE_SELECT
        }
    }

    if (startRoute == null) {
        // Mientras determinamos el start route, pantalla vacia.
        return
    }

    NavHost(navController = nav, startDestination = startRoute!!) {
        // ====== Sprint 3.2: selector de modo (start por defecto) ======
        composable(Routes.MODE_SELECT) {
            ModeSelectScreen(
                onSelectModo = { modo ->
                    when (modo) {
                        "TOTEM" -> nav.navigate(Routes.LOGIN_TOTEM)
                        "POS" -> {
                            // Si ya esta logueado, vamos al dashboard. Si no, login.
                            scope.launch {
                                val logged = ServiceLocator.authRepo.isLoggedIn.first()
                                if (logged) {
                                    nav.navigate(Routes.DASHBOARD) {
                                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                                    }
                                } else {
                                    nav.navigate(Routes.LOGIN) {
                                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                                    }
                                }
                            }
                        }
                        "GARZON" -> {
                            scope.launch {
                                val logged = ServiceLocator.authRepo.isLoggedIn.first()
                                if (logged) {
                                    nav.navigate(Routes.GARZON) {
                                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                                    }
                                } else {
                                    nav.navigate(Routes.LOGIN_GARZON) {
                                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }

        // ====== Login TOTEM (branding azul) ======
        composable(Routes.LOGIN_TOTEM) {
            LoginScreen(
                onLoginOk = { u ->
                    usuario = u
                    nav.navigate(Routes.TOTEM) {
                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                    }
                },
                headerTitle = "CSAE POS - Tótem",
                headerSubtitle = "Login operador del casino",
                brandColor = Color(0xFF1565C0),
            )
        }

        // ====== Login GARZON (branding café) ======
        composable(Routes.LOGIN_GARZON) {
            LoginScreen(
                onLoginOk = { u ->
                    usuario = u
                    // Bajar catalog para que la validacion de tickets tenga info.
                    scope.launch { ServiceLocator.catalogRepo.refresh() }
                    nav.navigate(Routes.GARZON) {
                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                    }
                },
                headerTitle = "CSAE POS - Garzón",
                headerSubtitle = "Login para escanear QR",
                brandColor = Color(0xFF6D4C41),
            )
        }

        // ====== Login (modo POS, branding verde) ======
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginOk = { u ->
                    usuario = u
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                    }
                },
                headerTitle = "CSAE POS",
                headerSubtitle = "Control de Servicios de Alimentación",
                brandColor = null,  // default primary
            )
        }

        // ====== TOTEM (sin login del operador, asume JWT ya cargado) ======
        composable(Routes.TOTEM) {
            TotemScreen(
                onCambiarModo = cambiarModo,
                onIrLoginTotem = { nav.navigate(Routes.LOGIN_TOTEM) },
                onIrConfig = { nav.navigate(Routes.CONFIGURACION) },
            )
        }

        // ====== GARZON (camara) ======
        composable(Routes.GARZON) {
            val u = usuario ?: run {
                // Si no hay usuario en memoria, volver al selector.
                nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } }
                return@composable
            }
            GarzonScreen(
                usuario = u,
                onCambiarModo = cambiarModo,
                onLogout = {
                    scope.launch {
                        ServiceLocator.authRepo.logout()
                        ServiceLocator.resetSession()
                    }
                    usuario = null
                    nav.navigate(Routes.MODE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // ====== Dashboard (existente) ======
        composable(Routes.DASHBOARD) {
            val u = usuario ?: run {
                nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } }
                return@composable
            }
            DashboardScreen(
                usuario = u,
                onLogout = {
                    scope.launch {
                        ServiceLocator.authRepo.logout()
                        ServiceLocator.resetSession()
                    }
                    usuario = null
                    nav.navigate(Routes.MODE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onIrPos = { nav.navigate(Routes.POS) },
            )
        }

        // ====== POS (refactor sprint 3.2: menu inferior) ======
        composable(Routes.POS) {
            val u = usuario ?: run {
                nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } }
                return@composable
            }
            POSScreen(
                usuario = u,
                onCambiarModo = cambiarModo,
                onTicketGenerado = { t ->
                    ultimoTicketNumero = t.numero
                    nav.navigate(Routes.ticket(t.numero))
                },
                onIrConsumos = { nav.navigate(Routes.CONSUMOS) },
                onIrConfig = { nav.navigate(Routes.CONFIGURACION) },
            )
        }

        // ====== Consumos ======
        composable(Routes.CONSUMOS) {
            ConsumosScreen(onCambiarModo = cambiarModo)
        }

        // ====== Configuracion ======
        composable(Routes.CONFIGURACION) {
            ConfiguracionScreen(onCambiarModo = cambiarModo)
        }

        // ====== Ticket (preview con QR) ======
        composable(Routes.TICKET) { entry ->
            val args = entry.arguments ?: return@composable
            val numero = args.getString("numero") ?: return@composable
            val ticket: Ticket = ServiceLocator.ticketCache.tickets.value
                .firstOrNull { it.numero == numero }
                ?: return@composable
            TicketScreen(
                ticket = ticket,
                esKiosko = kiosko,
                onNuevo = {
                    nav.popBackStack(Routes.POS, inclusive = false)
                },
                onCambiarModo = cambiarModo,
            )
        }
    }
}
