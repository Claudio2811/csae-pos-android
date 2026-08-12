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
    // F18.2: IDs de servicios que el comensal ya consumio HOY en este casino.
    // Lo setea CatalogRepository.marcarConsumido() despues de cada registro OK
    // y se pierde al cerrar la app (cache en memoria). El backend igual rechaza
    // duplicados con 409 (regla unicoPorDia) como red de seguridad.
    val serviciosConsumidosHoy: Set<String> = emptySet(),
)

data class Servicio(
    val id: String,
    val nombre: String,
    val tipo: String,         // "Almuerzo", "Cena", "Colacion", "Desayuno"
    val precio: Int,          // CLP
    // F18.2: true si este comensal ya consumio este servicio HOY.
    // La UI lo usa para deshabilitar el boton y mostrar "Ya consumido".
    val yaConsumido: Boolean = false,
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
    // Sprint 3.2: UUID unico que se imprime como QR en el ticket. El garzon
    // lo escanea con la camara y la app llama a /pos/tickets/validar.
    val qrToken: String? = null,
)

data class Kpi(
    val label: String,
    val value: String,
    val icono: String,         // emoji
)
