package cl.csae.pos.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= AUTH =============

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

/**
 * Sprint 2.9 + 3.3: respuesta del POST /api/v1/auth/login.
 *
 * - `token` JWT firmado por la API.
 * - `expiresAt` ISO 8601.
 * - `rol` string (OperadorPos, AdminCasino, Garzon, AdminEmpresa, GestorComensales).
 * - `restauranteId` para usuarios del casino, `empresaId` para usuarios empresa.
 *   Solo uno de los dos viene populated.
 * - `sucursalId` (F3) opcional: si el user del casino tiene una sucursal default
 *   asignada al login, viene populated. Si es null, el JWT no lleva el claim
 *   `sucursal_id` y el cliente debe llamar a GET /api/v1/auth/me para mostrar
 *   el selector (si hay >1) o auto-skip (si hay 0 o 1).
 */
@Serializable
data class LoginResponseDto(
    val token: String,
    val expiresAt: String,
    val email: String,
    val rol: String,
    val restauranteId: String? = null,
    val empresaId: String? = null,
    val sucursalId: String? = null,
)

/**
 * F3: respuesta del GET /api/v1/auth/me.
 *
 * Devuelve el perfil completo del usuario autenticado, incluyendo:
 *  - sus claims (userId, email, rol, restauranteId, sucursalActualId)
 *  - la lista de sucursales del casino (si es AdminCasino/OperadorPos/Garzon)
 *  - la lista de casinos a los que el user tiene acceso (si es AdminEmpresa)
 *
 * El cliente mobile (POS) usa esto en 2 lugares:
 *  1. Post-login: si el user tiene >1 sucursal, mostrar el selector.
 *  2. Desde Configuracion: para listar las sucursales disponibles y permitir
 *     cambiar manualmente.
 */
@Serializable
data class MeResponseDto(
    val userId: String,
    val email: String,
    val rol: String,
    val restauranteId: String? = null,
    val restauranteNombre: String? = null,
    val empresaId: String? = null,
    val sucursalActualId: String? = null,
    val sucursales: List<SucursalDto> = emptyList(),
    val restaurantes: List<RestauranteDto> = emptyList(),
)

/**
 * F3: item de la lista de sucursales disponibles del casino.
 * El operador del POS lo ve en el SucursalSelectScreen.
 */
@Serializable
data class SucursalDto(
    val id: String,
    val nombre: String,
    val codigo: String,
    val direccion: String? = null,
)

/**
 * F3: item de la lista de casinos a los que el user AdminEmpresa tiene acceso.
 * NO se usa en la app POS (es para el portal web AdminEmpresa).
 * Incluido aca para tener el DTO completo del endpoint /me.
 */
@Serializable
data class RestauranteDto(
    val id: String,
    val razonSocial: String,
    val rut: String,
    val totalSucursales: Int? = null,
)

/**
 * F3: body del POST /api/v1/auth/cambiar-sucursal.
 */
@Serializable
data class CambiarSucursalRequestDto(
    val sucursalId: String,
)

/**
 * F3: respuesta del POST /api/v1/auth/cambiar-sucursal.
 * El `token` nuevo reemplaza al JWT cacheado. El `sucursalId` viene en el claim
 * del token pero se devuelve explicito para que el cliente no tenga que parsearlo.
 */
@Serializable
data class CambiarSucursalResponseDto(
    val token: String,
    val expiresAt: String,
    val sucursalId: String,
)

/**
 * **F56 (2026-08-17):** body del POST /api/v1/auth/refresh.
 *
 * El endpoint acepta un token (incluso expirado, siempre que la firma sea
 * valida) y emite uno nuevo sin pedir password. Es la pieza clave para
 * que el mobile no acumule 401 cuando el JWT expira mientras el operador
 * esta logueado.
 *
 * El backend (`CSAE.Api` branch `main` commit `fa593ce`) devuelve 401 con
 * `code: INVALID_TOKEN` si la firma no matchea, o `code: TOKEN_EXPIRED` si
 * el token vencio (aunque esto ultimo NO deberia pasar con la firma, ver
 * la nota en el comentario del endpoint).
 */
@Serializable
data class RefreshTokenRequest(
    val token: String,
)

/**
 * **F56 (2026-08-17):** respuesta del POST /api/v1/auth/refresh.
 *
 * Misma forma que [LoginResponseDto] para que el cliente reutilice el
 * flujo de cache. El `token` reemplaza al JWT cacheado (sincrónicamente,
 * porque el [TokenAuthenticator] corre fuera de cualquier coroutine).
 *
 * `expiresAt` viene en ISO 8601 (mismo formato que login y cambiar-sucursal).
 * El cliente lo parsea a millis Unix para persistirlo en
 * [cl.csae.pos.data.prefs.AuthStore.getTokenExpiresAt] y asi correr el
 * refresh proactivo.
 *
 * `email`/`rol`/`restauranteId`/`empresaId`/`sucursalId` se devuelven
 * redundantes (ya estan en el JWT) para que un caller que solo tiene el
 * token pueda refrescar sin tener que parsear el claim.
 */
@Serializable
data class RefreshTokenResponse(
    val token: String,
    val expiresAt: String,
    val email: String,
    val rol: String,
    val restauranteId: String? = null,
    val empresaId: String? = null,
    val sucursalId: String? = null,
)

@Serializable
data class ApiError(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null,
)
