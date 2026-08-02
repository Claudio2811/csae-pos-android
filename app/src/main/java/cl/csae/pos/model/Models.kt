package cl.csae.pos.model

/**
 * Modelos de dominio del POS (mock data).
 *
 * Sprint 3.0: todos los datos vienen de MockRepository. En Sprint 3.1
 * se reemplaza por llamadas a la API real (CSAE.Api).
 */

data class UsuarioPos(
    val username: String,
    val displayName: String,
    val rol: String,        // "OperadorPOS" o "AdminCasino"
    val restauranteId: String,
)

data class Comensal(
    val id: String,
    val rut: String,
    val nombre: String,
    val apellido: String?,
    val empresa: String,
    val serviciosHoy: List<Servicio>,
)

data class Servicio(
    val id: String,
    val nombre: String,
    val tipo: String,         // "Almuerzo", "Cena", "Colacion", "Desayuno"
    val precio: Int,          // CLP
)

data class Ticket(
    val numero: String,        // ej: "T-20260802-000123"
    val comensal: Comensal,
    val servicio: Servicio,
    val fechaHora: String,     // ISO local
    val operador: String,
)

data class Kpi(
    val label: String,
    val value: String,
    val icono: String,         // emoji
)
