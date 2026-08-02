package cl.csae.pos.data

import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import cl.csae.pos.model.UsuarioPos
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Repositorio mock LEGACY de Sprint 3.0.
 *
 * **Ya no se usa.** En Sprint 3.1.2 se reemplazo por Retrofit + llamadas
 * a CSAE.Api. Este archivo se mantiene solo para no romper git history
 * y se eliminara en el proximo commit.
 *
 * El modelo de dominio cambio:
 *  - `UsuarioPos` ahora tiene `email` como campo principal (antes `username`).
 *  - `Comensal.serviciosHoy` se renombro a `Comensal.servicios`.
 *  - `Comensal` ahora incluye `membresiaId`.
 *
 * Este archivo no compila si los modelos se actualizan sin actualizarlo,
 * asi que se debe eliminar junto con la migracion al API real.
 */
@Suppress("unused", "UNUSED_VARIABLE")
object MockRepository {

    val usuarios = listOf(
        UsuarioPos(
            email = "operador@csae.cl",
            displayName = "Operador Salamanca",
            rol = "OperadorPOS",
            restauranteId = "11111111-1111-1111-1111-111111111111",
        ),
        UsuarioPos(
            email = "admin@csae.cl",
            displayName = "Admin Casino",
            rol = "AdminCasino",
            restauranteId = "11111111-1111-1111-1111-111111111111",
        ),
    )

    fun login(email: String, password: String): UsuarioPos? {
        val expected = when (email) {
            "operador@csae.cl" -> "demo123"
            "admin@csae.cl" -> "admin123"
            else -> return null
        }
        if (password != expected) return null
        return usuarios.firstOrNull { it.email == email }
    }

    val servicios = listOf(
        Servicio(id = "s-alm", nombre = "Almuerzo",   tipo = "Almuerzo",  precio = 4500),
        Servicio(id = "s-cen", nombre = "Cena",       tipo = "Cena",      precio = 5200),
        Servicio(id = "s-des", nombre = "Desayuno",   tipo = "Desayuno",  precio = 2800),
        Servicio(id = "s-col", nombre = "Colacion",   tipo = "Colacion",  precio = 1800),
    )

    private val comensales = mutableListOf(
        Comensal(
            id = "c-001", membresiaId = "m-001", rut = "12345678-5", nombre = "Juan", apellido = "Perez",
            empresa = "Minera Salamanca",
            servicios = listOf(servicios[0], servicios[2]),
        ),
        Comensal(
            id = "c-002", membresiaId = "m-002", rut = "11111111-1", nombre = "Maria", apellido = "Gonzalez",
            empresa = "Minera Salamanca",
            servicios = listOf(servicios[0]),
        ),
        Comensal(
            id = "c-003", membresiaId = "m-003", rut = "22222222-2", nombre = "Pedro", apellido = "Ramirez",
            empresa = "Minera Salamanca",
            servicios = listOf(servicios[1], servicios[3]),
        ),
        Comensal(
            id = "c-004", membresiaId = "m-004", rut = "33333333-3", nombre = "Ana", apellido = "Silva",
            empresa = "Constructora XYZ",
            servicios = listOf(servicios[0], servicios[1]),
        ),
        Comensal(
            id = "c-005", membresiaId = "m-005", rut = "44444444-4", nombre = "Luis", apellido = "Morales",
            empresa = "Minera Salamanca",
            servicios = listOf(servicios[2], servicios[3]),
        ),
    )

    fun buscarPorRut(rut: String): Comensal? {
        val canonico = rut.trim().replace(".", "").uppercase()
        return comensales.firstOrNull { it.rut.uppercase() == canonico }
    }

    fun listarComensales(): List<Comensal> = comensales.toList()

    private val ticketsGenerados = mutableListOf<Ticket>()
    private val contador = AtomicLong(0)
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("es", "CL"))

    fun generarTicket(comensal: Comensal, servicio: Servicio, operador: String): Result<Ticket> {
        if (comensal.servicios.none { it.id == servicio.id }) {
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

    private val hoy = java.util.Calendar.getInstance()
}
