package cl.csae.pos.data.repository

import cl.csae.pos.model.Ticket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache en memoria de los tickets generados en el turno.
 *
 * Lo usa el dashboard para mostrar "ultimos tickets" + KPIs (total, monto).
 *
 * Por que en memoria y no en Room: el turno dura horas, no dias. Si la app
 * se reinicia el operador hace login de nuevo (catalog se re-baja) y los
 * tickets del turno anterior son historico del backend, no del POS local.
 *
 * Al cerrar sesion (logout) se limpia con [clear].
 */
class TicketCacheRepository {

    private val _tickets = MutableStateFlow<List<Ticket>>(emptyList())
    val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

    fun agregar(ticket: Ticket) {
        _tickets.value = _tickets.value + ticket
    }

    fun montoTotalClp(): Int = _tickets.value.sumOf {
        if (it.precio > 0) it.precio else it.servicio.precio
    }

    fun comensalesUnicos(): Int = _tickets.value.map { it.comensal.id }.distinct().size

    fun clear() {
        _tickets.value = emptyList()
    }
}
