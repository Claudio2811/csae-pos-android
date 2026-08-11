package cl.csae.pos.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= CATALOG =============

@Serializable
data class PosCatalogResponseDto(
    val casinoId: String,
    val generatedAt: String,
    val empresas: List<EmpresaCatalogItemDto>,
    val servicios: List<ServicioCatalogItemDto>,
    val serviciosHabilitados: List<ServicioHabilitadoCatalogItemDto>,
    val comensales: List<ComensalCatalogItemDto>,
)

@Serializable
data class EmpresaCatalogItemDto(
    val id: String,
    val razonSocial: String,
    val rut: String,
)

@Serializable
data class ServicioCatalogItemDto(
    val id: String,
    val nombre: String,
    val tipo: String,
    val unicoPorDia: Boolean,
)

@Serializable
data class ServicioHabilitadoCatalogItemDto(
    val id: String,
    val empresaRestauranteId: String,
    val servicioId: String,
    val precioClp: Int,
    // El backend envia 'precioIvaClp' (con C mayuscula + Clp al final).
    // Para no chocar con futuras convenciones C# (xxxClp), lo aceptamos
    // como nullable con default 0.
    @SerialName("precioIvaClp") val precioIva: Int = 0,
)

@Serializable
data class ComensalCatalogItemDto(
    val comensalId: String,
    val membresiaId: String,
    val rut: String,
    val nombre: String,
    val apellido: String? = null,
    val empresaRestauranteId: String,
    val activo: Boolean,
)

// ============= COMENSAL BUSCAR =============

@Serializable
data class ComensalPosServiciosResponseDto(
    val comensalId: String,
    val membresiaId: String,
    val comensalNombreCompleto: String,
    val rut: String,
    val empresaRazonSocial: String,
    val servicios: List<ServicioPosItemDto>,
)

@Serializable
data class ServicioPosItemDto(
    val servicioId: String,
    val nombre: String,
    val tipo: String,
    val precio: Int,
    val precioIva: Int,
)

// ============= CONSUMO =============

@Serializable
data class RegistrarConsumoRequestDto(
    val membresiaId: String,
    val servicioId: String,
    val fechaConsumoUtc: String,
    val idempotencyKey: String? = null,
    val generarTicket: Boolean = true,
    val observaciones: String? = null,
)

@Serializable
data class RegistrarConsumoResponseDto(
    val consumoId: String,
    val membresiaId: String,
    val comensalId: String,
    val servicioId: String,
    val precioClp: Int,
    val ivaClp: Int,
    val fechaConsumoUtc: String,
    val ticketId: String? = null,
    val ticketNumero: String? = null,
    @SerialName("qrToken") val qrToken: String? = null,
    val observaciones: String? = null,
    val idempotente: Boolean = false,
)

@Serializable
data class MarcarTicketImpresoRequestDto(
    val numeroSerieImpresora: String? = null,
)

// ============= CONSUMO LISTADO (sprint 3.2) =============

/**
 * GET /api/v1/pos/consumos?desde=YYYY-MM-DDT00:00:00Z&pageSize=500
 * Cada item es un consumo del turno actual con datos desnormalizados.
 */
@Serializable
data class ConsumoListItemDto(
    val consumoId: String,
    val ticketId: String? = null,
    val ticketNumero: String? = null,
    val qrToken: String? = null,
    val fechaConsumoUtc: String,
    val comensalRut: String,
    val comensalNombre: String,
    val servicioNombre: String,
    val precioClp: Int,
)

@Serializable
data class ConsumosListResponseDto(
    val items: List<ConsumoListItemDto>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

// ============= TICKET VALIDAR / CONSUMIR (sprint 3.2) =============
// Usado por modo Garzon: el garzon escanea el QR del comensal y el backend
// responde si el ticket es valido, ya fue consumido, o no existe.

@Serializable
data class ValidarTicketRequest(
    val qrToken: String,
)

@Serializable
data class ValidarTicketResponse(
    val valido: Boolean,
    val mensaje: String,
    val ticket: TicketInfoDto? = null,
)

@Serializable
data class ConsumirTicketRequest(
    val qrToken: String,
)

@Serializable
data class ConsumirTicketResponse(
    val ok: Boolean,
    val mensaje: String,
    val consumidoEnUtc: String? = null,
)

@Serializable
data class TicketInfoDto(
    val ticketId: String,
    val qrToken: String,
    val numero: String,
    val comensalRut: String,
    val comensalNombre: String,
    val servicioNombre: String,
    val generadoUtc: String,
    val consumidoEnUtc: String? = null,
)

// ============= DISPOSITIVOS POS (F19) =============

/**
 * F19: dispositivo POS del casino. El operador selecciona uno de la lista
 * que devuelve GET /api/v1/dispositivos-pos, y la eleccion se persiste en
 * DataStore (via DispositivoPosActual). El backend matchea el AndroidId
 * del telefono contra esta lista al hacer login.
 *
 * Campos principales:
 * - id: GUID del dispositivo (establecido por el admin del casino).
 * - nombre: nombre amigable (ej: "POS Caja 1", "TOTEM Entrada").
 * - androidId: codigo unico del dispositivo (ANDROID_ID). El reconciliador
 *   al login matchea este campo contra ANDROID_ID.Settings.Secure.
 * - tipo: 1 = OperadorPos (POS atendido), 2 = Kiosko (TOTEM self-service).
 * - activo: false = soft-deleted, se filtra por defecto en la lista.
 */
@Serializable
data class DispositivoPosDto(
    val id: String,
    val restauranteId: String,
    val nombre: String,
    val androidId: String,
    val modelo: String? = null,
    val tipo: Int,
    val tipoNombre: String,
    val usuarioAsignadoId: String? = null,
    val sucursalId: String? = null,
    val sucursalNombre: String? = null,
    val kioskoToken: String? = null,
    val activo: Boolean,
    val createdAt: String,
    val updatedAt: String,
)
