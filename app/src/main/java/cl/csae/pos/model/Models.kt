package cl.csae.pos.model

/**
 * Modelos de dominio del POS.
 *
 * Sprint 3.1.2: los datos vienen de la API real (CSAE.Api). Se elimina
 * MockRepository; la fuente de verdad es CatalogRepository + ConsumoRepository.
 *
 * Nota: `Comensal.servicios` contiene los servicios habilitados para el
 * comensal en la membresia actual (lo que el POS puede entregar). Ya no
 * usamos el termino `serviciosHoy` porque la regla "un servicio por dia"
 * la valida el backend (ConsumoService) y el POS no la duplica.
 */

data class UsuarioPos(
    val email: String,
    val displayName: String,
    val rol: String,           // "OperadorPOS" o "AdminCasino"
    val restauranteId: String?, // null para AdminEmpresa (no es de un casino especifico)
    // Compat: el codigo mock usaba `username`. Lo dejamos mapeado.
    val username: String = email,
)

data class Comensal(
    val id: String,
    val membresiaId: String,
    val rut: String,
    val nombre: String,
    val apellido: String?,
    val empresa: String,
    val servicios: List<Servicio>,
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
    val fechaHora: String,     // local CL
    val operador: String,
    // IDs de la API (no nulos cuando el ticket viene de ConsumoRepository).
    val consumoId: String? = null,
    val ticketId: String? = null,
    val precio: Int = 0,
)

data class Kpi(
    val label: String,
    val value: String,
    val icono: String,         // emoji
)
