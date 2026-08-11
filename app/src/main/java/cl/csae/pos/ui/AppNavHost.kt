package cl.csae.pos.ui

import androidx.compose.runtime.*
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
    // Sprint F9 (2026-08-11): un solo LOGIN. Antes habia LOGIN_TOTEM, LOGIN y
    // LOGIN_GARZON — redundante y causaba que el operador tuviera que
    // loguearse cada vez que cambiaba de modo.
    const val MODE_SELECT = "mode_select"
    const val LOGIN = "login"
    const val TOTEM = "totem"
    const val POS = "pos"
    const val CONSUMOS = "consumos"
    const val CONFIGURACION = "configuracion"
    const val GARZON = "garzon"
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

    // Sprint 3.3: decidir start destination. Login primero SIEMPRE, salvo
    // que el usuario ya este logueado. Asi la app sabe casino + sucursal
    // + quien es desde el primer tap (trazabilidad completa).
    LaunchedEffect(Unit) {
        val modo = ServiceLocator.authStore.getModoPreferido()
        val isLoggedIn = ServiceLocator.authRepo.isLoggedIn.first()
        startRoute = when {
            !isLoggedIn -> Routes.LOGIN
            modo == "TOTEM" -> Routes.TOTEM
            modo == "POS" -> Routes.DASHBOARD
            modo == "GARZON" -> Routes.GARZON
            else -> Routes.MODE_SELECT
        }
    }

    if (startRoute == null) {
        // Mientras determinamos el start route, pantalla vacia.
        return
    }

    NavHost(navController = nav, startDestination = startRoute!!) {
        // ====== Login unico (Sprint F9 2026-08-11) ======
        // Antes habia LOGIN_TOTEM, LOGIN y LOGIN_GARZON (3 pantallas con la
        // misma UI que forzaban re-login al cambiar de modo). Ahora es un
        // solo LOGIN, y el ModeSelectScreen filtra los modos segun el rol.
        // El LoginScreen rechaza usuarios empresa (AdminEmpresa / GestorComensales)
        // con un mensaje claro — esa app es solo para operadores del casino.
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginOk = { u ->
                    usuario = u
                    nav.navigate(Routes.MODE_SELECT) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        // ====== Sprint 3.2: selector de modo (start por defecto) ======
        // Filtra los botones segun el rol del usuario logueado:
        //   - OperadorPos: solo "Caja"
        //   - Garzon:      solo "Garzon"
        //   - AdminCasino / SupervisorCasino / SuperAdmin: los 3
        //   - AdminEmpresa: NO entra a la app (rechazado en LoginScreen)
        composable(Routes.MODE_SELECT) {
            ModeSelectScreen(
                usuario = usuario,
                onSettings = { nav.navigate(Routes.CONFIGURACION) },
                onSelectModo = { modo ->
                    when (modo) {
                        "TOTEM" -> nav.navigate(Routes.TOTEM) {
                            popUpTo(Routes.MODE_SELECT) { inclusive = true }
                        }
                        "POS" -> nav.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.MODE_SELECT) { inclusive = true }
                        }
                        "GARZON" -> nav.navigate(Routes.GARZON) {
                            popUpTo(Routes.MODE_SELECT) { inclusive = true }
                        }
                    }
                },
            )
        }

        // ====== TOTEM (asume JWT ya cargado del login) ======
        composable(Routes.TOTEM) {
            TotemScreen(
                onSettings = { nav.navigate(Routes.CONFIGURACION) },
                onCambiarModo = cambiarModo,
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
