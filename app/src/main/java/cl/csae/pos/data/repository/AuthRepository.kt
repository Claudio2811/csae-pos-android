package cl.csae.pos.data.repository

import cl.csae.pos.data.api.ApiError
import cl.csae.pos.data.api.CasinoThemeDto
import cl.csae.pos.data.api.LoginRequest
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.prefs.AuthStore
import cl.csae.pos.model.UsuarioPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Auth: login contra la API + persistencia del JWT.
 *
 * Cache sincrónico del JWT en memoria:
 * - Al login OK, el JWT se guarda en DataStore Y se cachea en memoria.
 * - El ApiClient lee el cache sincrónicamente en el interceptor (no puede ser suspend).
 * - Al logout, se limpia el cache y DataStore.
 * - Al iniciar la app, una coroutine en background carga el JWT de DataStore al cache.
 *
 * Esto evita el `runBlocking` en cada llamada HTTP y respeta el main thread.
 *
 * **Sprint F16 (2026-08-11):** despues del login OK, hace un fetch a
 * `GET /api/v1/casino` para obtener los datos de marca del casino
 * (colorPrimario, colorAcento, logoUrl, nombre, RUT) y los persiste
 * en [AuthStore]. Esos valores son los que usa el `CsaePosTheme` para
 * aplicar el tema del casino (F16) y los logos en cascada.
 *
 * Si el fetch del casino falla (ej: el user es AdminEmpresa y el endpoint
 * devuelve 404 porque no tiene casino), el login sigue OK pero los campos
 * de casino quedan en null y la UI usa los colores default + logo del
 * producto (CSAE).
 */
class AuthRepository(
    private val api: PosApiService,
    private val authStore: AuthStore,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile private var jwtCache: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Pre-cargar el JWT de DataStore en background al iniciar.
        scope.launch { jwtCache = authStore.getToken() }
    }

    /** Provider del JWT actual, para pasarselo al `ApiClient.build`. */
    val jwtProvider: () -> String? = { jwtCache ?: runBlocking { authStore.getToken() } }

    /** Flow con el perfil del usuario (null si no esta logueado). */
    val currentUser: Flow<UsuarioPos?> = combine(
        authStore.email, authStore.displayName, authStore.rol, authStore.restauranteId, authStore.token
    ) { email, name, rol, restId, token ->
        if (token == null) null
        else UsuarioPos(
            username = email ?: "",
            displayName = name ?: email ?: "",
            email = email ?: "",
            rol = rol ?: "",
            restauranteId = restId,
        )
    }

    val isLoggedIn: Flow<Boolean> = authStore.token.map { it != null }

    /**
     * Sprint F16: Flow con el CasinoTheme actual (null si no hay login o si
     * el user es AdminEmpresa y no tiene casino asociado). La UI lo
     * `collectAsState` y aplica el `MaterialTheme` con esos colores.
     *
     * Flow.combine solo tiene overloads hasta 5 args. Para 6+ hay que
     * pasar un Array (combineTransform). Lo construimos asi.
     */
    val currentCasinoTheme: Flow<CasinoThemeDto?> = combine(
        authStore.casinoId,
        authStore.casinoNombre,
        authStore.casinoRut,
        authStore.casinoColorPrimario,
        authStore.casinoColorAcento,
        authStore.casinoLogoUrl,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val id = values[0] as String?
        @Suppress("UNCHECKED_CAST")
        val nombre = values[1] as String?
        @Suppress("UNCHECKED_CAST")
        val rut = values[2] as String?
        @Suppress("UNCHECKED_CAST")
        val colorP = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val colorA = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val logo = values[5] as String?
        if (id == null || nombre == null) null
        else CasinoThemeDto(
            id = id,
            razonSocial = nombre,
            rut = rut,
            colorPrimario = colorP,
            colorAcento = colorA,
            logoUrl = logo,
        )
    }

    suspend fun login(email: String, password: String): Result<UsuarioPos> {
        return try {
            val resp = api.login(LoginRequest(email, password))
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
            authStore.save(body.token, body.email, body.email, body.rol, body.restauranteId)
            jwtCache = body.token

            // Sprint F16: despues del login, bajar el casino y guardar su tema.
            // Si falla (ej: AdminEmpresa que no tiene casino, o 404), no es
            // fatal: el login sigue OK y la UI usa colores default.
            try {
                val casinoResp = api.getCasino()
                if (casinoResp.isSuccessful) {
                    val c = casinoResp.body()
                    if (c != null) {
                        authStore.saveCasinoTheme(
                            casinoId = c.id,
                            casinoNombre = c.razonSocial,
                            casinoRut = c.rut,
                            colorPrimario = c.colorPrimario.takeIf { !it.isNullOrBlank() },
                            colorAcento = c.colorAcento.takeIf { !it.isNullOrBlank() },
                            logoUrl = c.logoUrl.takeIf { !it.isNullOrBlank() },
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignorar: el user podria no tener casino (AdminEmpresa)
            }

            Result.success(
                UsuarioPos(
                    username = body.email,
                    displayName = body.email,
                    email = body.email,
                    rol = body.rol,
                    restauranteId = body.restauranteId,
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun logout() {
        // Sprint F16: clear() ya limpia todos los campos del DataStore,
        // incluyendo los de casino (casinoId, colorPrimario, etc).
        authStore.clear()
        jwtCache = null
    }

    /**
     * Sprint F16: re-baja el casino del backend y actualiza el theme en
     * DataStore. Util para refrescar el tema sin pedir re-login (ej: el
     * admin del casino cambio el colorPrimario y el operador quiere ver
     * el cambio sin desloguearse). Llamado desde el boton "Refrescar tema"
     * de Configuracion (F17).
     */
    suspend fun refreshCasinoTheme(): Result<Unit> {
        return try {
            val resp = api.getCasino()
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val c = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
            authStore.saveCasinoTheme(
                casinoId = c.id,
                casinoNombre = c.razonSocial,
                casinoRut = c.rut,
                colorPrimario = c.colorPrimario.takeIf { !it.isNullOrBlank() },
                colorAcento = c.colorAcento.takeIf { !it.isNullOrBlank() },
                logoUrl = c.logoUrl.takeIf { !it.isNullOrBlank() },
            )
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parseError(errorBody: String?, code: Int): String {
        if (errorBody.isNullOrBlank()) return "Error $code"
        return try {
            val err = json.decodeFromString(ApiError.serializer(), errorBody)
            err.detail ?: err.title ?: "Error $code"
        } catch (e: Exception) {
            "Error $code"
        }
    }
}
