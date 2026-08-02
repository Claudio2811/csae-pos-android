package cl.csae.pos.ui

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.screens.DashboardScreen
import cl.csae.pos.ui.screens.LoginScreen
import cl.csae.pos.ui.screens.POSScreen
import cl.csae.pos.ui.screens.TicketScreen
import kotlinx.coroutines.launch

object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val POS = "pos"
    const val TICKET = "ticket/{numero}"
    fun ticket(numero: String) = "ticket/$numero"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun CsaeNavHost() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    // remember (no rememberSaveable) porque UsuarioPos no es Parcelable y el
    // POS se mantiene en portrait: la rotacion no deberia pasar.
    var usuario by remember { mutableStateOf<UsuarioPos?>(null) }
    var kiosko by remember { mutableStateOf(false) }
    // Ultimo ticket generado en el turno: vive en el cache del TicketCacheRepository,
    // pero necesitamos navegar a el pasando solo el numero. Lo recuperamos desde ahi.
    var ultimoTicketNumero by remember { mutableStateOf<String?>(null) }

    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoginOk = { u ->
                usuario = u
                nav.navigate(Routes.DASHBOARD) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.DASHBOARD) {
            val u = usuario ?: run { return@composable }
            DashboardScreen(
                usuario = u,
                onLogout = {
                    scope.launch {
                        ServiceLocator.authRepo.logout()
                        ServiceLocator.resetSession()
                    }
                    usuario = null
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onIrPos = { nav.navigate(Routes.POS) },
            )
        }
        composable(Routes.POS) {
            val u = usuario ?: run { return@composable }
            POSScreen(
                usuario = u,
                onBack = { nav.popBackStack() },
                onTicketGenerado = { t ->
                    ultimoTicketNumero = t.numero
                    nav.navigate(Routes.ticket(t.numero))
                },
            )
        }
        composable(Routes.TICKET) { entry ->
            val args = entry.arguments ?: return@composable
            val numero = args.getString("numero") ?: return@composable
            val u = usuario ?: return@composable
            // Recuperamos el ticket del cache. Si no esta (caso raro: app
            // se reincio en medio del flujo), volvemos al POS.
            val ticket: Ticket = ServiceLocator.ticketCache.tickets.value
                .firstOrNull { it.numero == numero }
                ?: return@composable
            TicketScreen(
                ticket = ticket,
                esKiosko = kiosko,
                onNuevo = {
                    nav.popBackStack(Routes.POS, inclusive = false)
                },
                onVolver = { nav.popBackStack() },
            )
        }
    }
}
