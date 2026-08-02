package cl.csae.pos.data.repository

import cl.csae.pos.data.api.ApiError
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
        authStore.clear()
        jwtCache = null
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
