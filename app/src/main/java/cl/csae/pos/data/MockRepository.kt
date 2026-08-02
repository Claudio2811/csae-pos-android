package cl.csae.pos.data

import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Kpi
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Repositorio mock para Sprint 3.0. Todos los datos viven en memoria.
 *
 * **Por que mock primero:**
 * Salamanca aun no tiene comensales reales. Mockear nos permite iterar la UX
 * del POS sin atarnos a la API ni a la BD. En Sprint 3.1 este repo se
 * reemplaza por Retrofit + llamadas a CSAE.Api.
 */
object MockRepository {

    // ============= USUARIOS (login) =============

    val usuarios = listOf(
        UsuarioPos(
            username = "operador",
            displayName = "Operador Salamanca",
            rol = "OperadorPOS",
            restauranteId = "11111111-1111-1111-1111-111111111111",
        ),
        UsuarioPos(
            username = "admin",
            displayName = "Admin Casino",
            rol = "AdminCasino",
            restauranteId = "11111111-1111-1111-1111-111111111111",
        ),
    )

    fun login(username: String, password: String): UsuarioPos? {
        // Password fijo para demo: operador/demo123, admin/admin123
        val expected = when (username) {
            "operador" -> "demo123"
            "admin" -> "admin123"
            else -> return null
        }
        if (password != expected) return null
        return usuarios.firstOrNull { it.username == username }
    }

    // ============= CATALOGO DE SERVICIOS =============

    val servicios = listOf(
        Servicio(id = "s-alm", nombre = "Almuerzo",   tipo = "Almuerzo",  precio = 4500),
        Servicio(id = "s-cen", nombre = "Cena",       tipo = "Cena",      precio = 5200),
        Servicio(id = "s-des", nombre = "Desayuno",   tipo = "Desayuno",  precio = 2800),
        Servicio(id = "s-col", nombre = "Colacion",   tipo = "Colacion",  precio = 1800),
    )

    // ============= COMENSALES MOCK =============

    private val comensales = mutableListOf(
        Comensal(
            id = "c-001", rut = "12345678-5", nombre = "Juan", apellido = "Perez",
            empresa = "Minera Salamanca",
            serviciosHoy = listOf(servicios[0], servicios[2]),
        ),
        Comensal(
            id = "c-002", rut = "11111111-1", nombre = "Maria", apellido = "Gonzalez",
            empresa = "Minera Salamanca",
            serviciosHoy = listOf(servicios[0]),
        ),
        Comensal(
            id = "c-003", rut = "22222222-2", nombre = "Pedro", apellido = "Ramirez",
            empresa = "Minera Salamanca",
            serviciosHoy = listOf(servicios[1], servicios[3]),
        ),
        Comensal(
            id = "c-004", rut = "12345678-5", nombre = "Ana", apellido = "Silva",
            empresa = "Constructora XYZ",
            serviciosHoy = listOf(servicios[0], servicios[1]),
        ),
        Comensal(
            id = "c-005", rut = "12345678-5", nombre = "Luis", apellido = "Morales",
            empresa = "Minera Salamanca",
            serviciosHoy = listOf(servicios[2], servicios[3]),
        ),
    )

    /**
     * Busca un comensal por RUT. Retorna el primero que matchee (o null).
     * El RUT se compara en formato canonico (sin puntos, con guion).
     */
    fun buscarPorRut(rut: String): Comensal? {
        val canonico = rut.trim().replace(".", "").uppercase()
        return comensales.firstOrNull { it.rut.uppercase() == canonico }
    }

    /**
     * Lista de comensales de una empresa (para dropdowns o busqueda).
     */
    fun listarComensales(): List<Comensal> = comensales.toList()

    // ============= TICKETS =============

    private val ticketsGenerados = mutableListOf<Ticket>()
    private val contador = AtomicLong(0)
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("es", "CL"))

    /**
     * Genera un ticket: valida que el comensal tenga el servicio disponible
     * y que no se haya generado el mismo servicio en el mismo dia (regla
     * "un servicio por comensal por dia").
     */
    fun generarTicket(comensal: Comensal, servicio: Servicio, operador: String): Result<Ticket> {
        if (comensal.serviciosHoy.none { it.id == servicio.id }) {
            return Result.failure(IllegalStateException(
                "El comensal no tiene '${servicio.nombre}' asignado para hoy."
            ))
        }
        if (ticketsGenerados.any { it.comensal.id == comensal.id && it.servicio.id == servicio.id }) {
            return Result.failure(IllegalStateException(
                "El comensal ya recibio '${servicio.nombre}' hoy."
            ))
        }
        val ahora = Date()
        val n = contador.incrementAndGet()
        val ticket = Ticket(
            numero = "T-${dateFormat.format(ahora)}-${n.toString().padStart(6, '0')}",
            comensal = comensal,
            servicio = servicio,
            fechaHora = displayFormat.format(ahora),
            operador = operador,
        )
        ticketsGenerados.add(ticket)
        return Result.success(ticket)
    }

    fun ticketsHoy(): List<Ticket> = ticketsGenerados.toList()

    // ============= KPIs DEL DASHBOARD =============

    fun kpis(): List<Kpi> {
        val total = ticketsGenerados.size
        val monto = ticketsGenerados.sumOf { it.servicio.precio }
        return listOf(
            Kpi("Tickets hoy",   total.toString(),                "🎫"),
            Kpi("Monto total",   "$${monto}",                     "💰"),
            Kpi("Comensales unicos", ticketsGenerados.map { it.comensal.id }.distinct().size.toString(), "👥"),
            Kpi("Servicios disponibles", servicios.size.toString(),"🍽"),
        )
    }
}
