package cl.csae.pos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.screens.ConfiguracionScreen
import cl.csae.pos.ui.screens.ConsumosScreen
import cl.csae.pos.ui.screens.DashboardScreen
import cl.csae.pos.ui.screens.GarzonScreen
import cl.csae.pos.ui.screens.LoginScreen
import cl.csae.pos.ui.screens.ModeSelectScreen
import cl.csae.pos.ui.screens.POSScreen
import cl.csae.pos.ui.screens.SucursalSelectScreen
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
    // F3: selector de sucursal para OperadorPos. Se muestra post-login si
    // el casino tiene >1 sucursal. Tambien accesible desde Configuracion
    // para cambiar manualmente.
    const val SUCURSAL_SELECT = "sucursal_select"
    const val TICKET = "ticket/{numero}"
    fun ticket(numero: String) = "ticket/$numero"
}

/**
 * **Sprint F16 (2026-08-11):** entry point de la UI mobile. Aplica el
 * [CsaePosTheme] con los colores del casino actual (leidos via
 * [ServiceLocator.authRepo.currentCasinoTheme]) y luego monta el NavHost
 * con todas las rutas de la app.
 *
 * **Fixes 2026-08-12 (ronda post-video):**
 *  - `currentUser` ahora se lee del Flow `authRepo.currentUser` (DataStore
 *    reactivo) en vez de `var usuario by remember` que se perdia al
 *    destruirse el NavHost en logout (`popUpTo(0)`). Eso causaba que
 *    `ModeSelectScreen` mostrara "Tu cuenta no tiene permisos" aunque
 *    el user fuera AdminCasino valido.
 *  - `initRoute` se evalua UNA vez al inicio (con splash mientras se lee
 *    el JWT del DataStore) y se pasa al `NavHost` como `startDestination`.
 *    Antes era `LaunchedEffect(Unit)` + var nullable que tenia un flash
 *    visible de LOGIN antes de redirigir al destino real.
 *  - `LaunchedEffect(isLoggedIn, modoPreferido)` se re-evalua reactivamente
 *    para cambios (cambiar modo en Configuracion, logout, etc). Si la ruta
 *    activa ya es la target, no hace nada.
 *  - Logout usa `ServiceLocator.logoutAndReset()` (applicationScope) en vez
 *    de `rememberCoroutineScope()` que se cancelaba con el NavHost y
 *    dejaba el DataStore inconsistente.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun CsaeNavHost() {
    val nav = rememberNavController()

    // Theme dinamico del casino. Leemos del AuthRepository (que ya esta
    // persistido en DataStore desde el login). Si no hay casino o el
    // user es AdminEmpresa, casinoTheme queda en null y el theme usa los
    // colores default.
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme.collectAsState(initial = null)

    // F20 (2026-08-12): currentUser reactivo. Antes era `var usuario by remember`
    // que quedaba en null despues de un logout con `popUpTo(0)`, causando que
    // ModeSelectScreen mostrara "Tu cuenta no tiene permisos". Ahora se lee
    // del Flow `authRepo.currentUser` que emite null cuando DataStore se limpia
    // en `authRepo.logout()`.
    val currentUser by ServiceLocator.authRepo.currentUser.collectAsState(initial = null)
    val modoPreferido by ServiceLocator.authStore.modoPreferido.collectAsState(initial = null)
    val isLoggedIn by ServiceLocator.authRepo.isLoggedIn.collectAsState(initial = false)

    // F20 (2026-08-12): ruta inicial resuelta UNA vez al montar (splash
    // mientras tanto). Evita el flash de LOGIN que se veia antes cuando el
    // operador ya estaba logueado.
    var initRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val logged = ServiceLocator.authRepo.isLoggedIn.first()
        val modo = ServiceLocator.authStore.modoPreferido.first()
        initRoute = when {
            !logged -> Routes.LOGIN
            modo == "TOTEM" -> Routes.TOTEM
            modo == "POS" -> Routes.DASHBOARD
            modo == "GARZON" -> Routes.GARZON
            else -> Routes.MODE_SELECT
        }
    }

    // F20 (2026-08-12) + F3 (2026-08-13): navegacion REACTIVA para cambios
    // posteriores (cambio de modo, logout, login sin sucursal seleccionada).
    // Si la ruta activa ya es la target, no hace nada para evitar navegaciones
    // innecesarias.
    //
    // F3: si el user esta logueado, NO tiene sucursal asignada, y el casino
    // tiene >1 sucursales, ir a SUCURSAL_SELECT antes del destino del modo
    // preferido. Esto cubre el post-login (sucursalId=null porque el user no
    // tiene default) y el caso de "cambiar de casino" (que limpia sucursalId).
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    LaunchedEffect(isLoggedIn, modoPreferido, currentUser?.sucursalId) {
        // F3: si el user esta logueado sin sucursal y el casino tiene >1,
        // primero a SUCURSAL_SELECT. Si solo tiene 1 o 0, no mostramos
        // selector y vamos directo al destino del modo preferido.
        val sucursalesCache = ServiceLocator.authRepo.sucursalesDisponibles.value
        val target = when {
            !isLoggedIn -> Routes.LOGIN
            currentUser?.sucursalId == null && sucursalesCache.size > 1 -> Routes.SUCURSAL_SELECT
            modoPreferido == "TOTEM" -> Routes.TOTEM
            modoPreferido == "POS" -> Routes.DASHBOARD
            modoPreferido == "GARZON" -> Routes.GARZON
            else -> Routes.MODE_SELECT
        }
        if (currentRoute != null && currentRoute != target) {
            nav.navigate(target) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    CsaePosTheme(
        casinoColorPrimario = casinoTheme?.colorPrimario,
        casinoColorAcento = casinoTheme?.colorAcento,
    ) {
        val start = initRoute
        if (start == null) {
            // Splash minimal mientras se resuelve la ruta inicial (lee
            // `isLoggedIn` y `modoPreferido` del DataStore). Esto evita el
            // flash de LOGIN que se veia antes cuando el operador ya estaba
            // logueado al abrir la app.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@CsaePosTheme
        }

        val cambiarModo = remember(nav) {
            { nav.navigate(Routes.MODE_SELECT) { popUpTo(0) { inclusive = true } } }
        }
        val doLogout = remember(nav) {
            {
                // applicationScope: sobrevive a la destruccion del NavHost
                // por popUpTo(0). Antes el scope del composable se cancelaba
                // y authRepo.logout() quedaba a medias, causando crash o
                // estado inconsistente (DataStore con token pero UI en login).
                ServiceLocator.logoutAndReset()
            }
        }
        // F3: navegar al destino del modo preferido despues de seleccionar
        // una sucursal. Tambien re-baja el catalog (los comensales/servicios
        // son distintos por sucursal). Se hace en `applicationScope` para
        // que sobreviva a la destruccion del NavHost por popUpTo(0).
        val onSucursalSelected = remember(nav, modoPreferido) {
            {
                // Re-bajar catalog en background. No bloqueamos la navegacion.
                ServiceLocator.applicationScope.launch {
                    try {
                        ServiceLocator.catalogRepo.refresh()
                    } catch (_: Exception) { /* best effort */ }
                }
                // Navegar al destino del modo preferido (o MODE_SELECT si no
                // hay). El LaunchedEffect(isLoggedIn, modoPreferido, ...)
                // podria dispararse tambien, pero `if (currentRoute != target)`
                // previene la doble navegacion.
                val target = when (modoPreferido) {
                    "TOTEM" -> Routes.TOTEM
                    "POS" -> Routes.DASHBOARD
                    "GARZON" -> Routes.GARZON
                    else -> Routes.MODE_SELECT
                }
                nav.navigate(target) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        NavHost(
            navController = nav,
            startDestination = start,
        ) {
            // ====== Login unico (Sprint F9 2026-08-11) ======
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginOk = {
                        // El LaunchedEffect(isLoggedIn, modoPreferido) detecta
                        // el cambio de isLoggedIn a true y navega al destino
                        // correspondiente via el re-route reactivo. No
                        // necesitamos navegar manualmente.
                    },
                )
            }

            // ====== Sprint 3.2: selector de modo (start por defecto) ======
            composable(Routes.MODE_SELECT) {
                ModeSelectScreen(
                    usuario = currentUser,
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
                )
            }

            // ====== GARZON (camara) ======
            composable(Routes.GARZON) {
                val u = currentUser ?: run {
                    // El LaunchedEffect(isLoggedIn) ya nos deberia haber
                    // llevado al login, pero por seguridad hacemos fallback.
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    return@composable
                }
                GarzonScreen(
                    usuario = u,
                    onCambiarModo = cambiarModo,
                    onLogout = doLogout,
                )
            }

            // ====== Dashboard ======
            composable(Routes.DASHBOARD) {
                val u = currentUser ?: run {
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    return@composable
                }
                DashboardScreen(
                    usuario = u,
                    onLogout = doLogout,
                    onIrPos = { nav.navigate(Routes.POS) },
                    onIrConfig = { nav.navigate(Routes.CONFIGURACION) },
                )
            }

            // ====== POS ======
            composable(Routes.POS) {
                val u = currentUser ?: run {
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
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
                ConsumosScreen(
                    onCambiarModo = cambiarModo,
                    onIrConfig = { nav.navigate(Routes.CONFIGURACION) },
                )
            }

            // ====== Configuracion ======
            composable(Routes.CONFIGURACION) {
                ConfiguracionScreen(
                    onCambiarModo = cambiarModo,
                    onIrSucursal = { nav.navigate(Routes.SUCURSAL_SELECT) },
                    // F4.3: cerrar sesion navega a Login y limpia el back stack
                    // (popUpTo(0) inclusive) para que el user no pueda volver
                    // a pantallas autenticadas con el boton Back.
                    onCerrarSesion = {
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            // ====== F3: Selector de sucursal (post-login + desde Configuracion) ======
            composable(Routes.SUCURSAL_SELECT) {
                SucursalSelectScreen(
                    // Wrapper: SucursalSelectScreen devuelve la sucursalId
                    // seleccionada, pero el callback del NavHost ignora el
                    // param (la navegacion ya se hace via el remember del
                    // `onSucursalSelected` que re-baja catalog + navega).
                    onSucursalSelected = { _ -> onSucursalSelected() },
                    onBack = { nav.popBackStack() },
                )
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
                    esKiosko = false,
                    onNuevo = {
                        nav.popBackStack(Routes.POS, inclusive = false)
                    },
                    onCambiarModo = cambiarModo,
                )
            }
        }
    }
}
