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
    val observaciones: String? = null,
    val idempotente: Boolean = false,
)

@Serializable
data class MarcarTicketImpresoRequestDto(
    val numeroSerieImpresora: String? = null,
)
