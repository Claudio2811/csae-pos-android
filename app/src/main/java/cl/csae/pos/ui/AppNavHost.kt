package cl.csae.pos.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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
import cl.csae.pos.ui.theme.CsaePosTheme
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

/**
 * **Sprint F16 (2026-08-11):** entry point de la UI mobile. Aplica el
 * [CsaePosTheme] con los colores del casino actual (leidos via
 * [ServiceLocator.authRepo.currentCasinoTheme]) y luego monta el NavHost
 * con todas las rutas de la app.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun CsaeNavHost() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var usuario by remember { mutableStateOf<UsuarioPos?>(null) }
    var kiosko by remember { mutableStateOf(false) }
    var ultimoTicketNumero by remember { mutableStateOf<String?>(null) }
    var startRoute by remember { mutableStateOf<String?>(null) }

    // Theme dinamico del casino. Leemos del AuthRepository (que ya esta
    // persistido en DataStore desde el login). Si no hay casino o el
    // user es AdminEmpresa, casinoTheme queda en null y el theme usa los
    // colores default.
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme.collectAsState(initial = null)

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

    CsaePosTheme(
        casinoColorPrimario = casinoTheme?.colorPrimario,
        casinoColorAcento = casinoTheme?.colorAcento,
    ) {
        if (startRoute == null) return@CsaePosTheme

        val cambiarModo = remember(nav) {
            { nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } } }
        }

        NavHost(
            navController = nav,
            startDestination = startRoute!!,
        ) {
            // ====== Login unico (Sprint F9 2026-08-11) ======
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

            // ====== Dashboard ======
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

            // ====== POS ======
            composable(Routes.POS) {
                val u = usuario ?: run {
                    nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } }
                    return@composable
                }
                POSScreen(
                    usuario = u,
                    onCambiarModo = cambiarModo,
                    onTicketGenerado = { t ->
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
}
