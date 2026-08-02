package cl.csae.pos.ui

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.screens.DashboardScreen
import cl.csae.pos.ui.screens.LoginScreen
import cl.csae.pos.ui.screens.POSScreen
import cl.csae.pos.ui.screens.TicketScreen

object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val POS = "pos"
    const val TICKET = "ticket/{numero}/{comensalId}/{servicioId}"
    fun ticket(numero: String, comensalId: String, servicioId: String) = "ticket/$numero/$comensalId/$servicioId"
}

@Composable
fun CsaeNavHost() {
    val nav = rememberNavController()
    // remember (no rememberSaveable) porque UsuarioPos no es Parcelable y no
    // vale la pena agregar el codigo del Saver para un POS donde la rotacion
    // de pantalla no deberia pasar.
    var usuario by remember { mutableStateOf<UsuarioPos?>(null) }
    var kiosko by remember { mutableStateOf(false) }

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
                    nav.navigate(Routes.ticket(t.numero, t.comensal.id, t.servicio.id))
                },
            )
        }
        composable(Routes.TICKET) { entry ->
            val args = entry.arguments ?: return@composable
            val numero = args.getString("numero") ?: return@composable
            val comensalId = args.getString("comensalId") ?: return@composable
            val servicioId = args.getString("servicioId") ?: return@composable

            val comensal = cl.csae.pos.data.MockRepository.listarComensales()
                .firstOrNull { it.id == comensalId } ?: return@composable
            val servicio = comensal.serviciosHoy.firstOrNull { it.id == servicioId } ?: return@composable
            val u = usuario ?: return@composable
            val ticket = Ticket(
                numero = numero,
                comensal = comensal,
                servicio = servicio,
                fechaHora = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale("es", "CL"))
                    .format(java.util.Date()),
                operador = u.displayName,
            )
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
