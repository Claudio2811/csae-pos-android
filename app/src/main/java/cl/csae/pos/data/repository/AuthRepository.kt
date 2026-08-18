package cl.csae.pos.data.repository

import android.content.Context
import android.provider.Settings
import cl.csae.pos.data.api.ApiError
import cl.csae.pos.data.api.CambiarSucursalRequestDto
import cl.csae.pos.data.api.CambiarSucursalResponseDto
import cl.csae.pos.data.api.CasinoThemeDto
import cl.csae.pos.data.api.LoginRequest
import cl.csae.pos.data.api.MeResponseDto
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.api.RefreshTokenRequest
import cl.csae.pos.data.api.RefreshTokenResponse
import cl.csae.pos.data.api.SucursalDto
import cl.csae.pos.data.prefs.IAuthStore
import cl.csae.pos.data.selection.DispositivoPosActual
import cl.csae.pos.model.UsuarioPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

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
 *
 * **Sprint F3 (2026-08-13):** selector de sucursal para OperadorPos.
 * - Despues del login, el [currentUser] ahora incluye la `sucursalId` activa
 *   (del DataStore, poblada por el login si el user tiene sucursal default).
 * - [me] re-baja la lista de sucursales del casino y la cachea en
 *   [sucursalesDisponibles] (StateFlow). El [SucursalSelectScreen] consume
 *   ese StateFlow y evita pegarle al API cada vez que se abre.
 * - [cambiarSucursal] llama a POST /api/v1/auth/cambiar-sucursal, reemplaza
 *   el JWT cacheado por el nuevo (que lleva el claim `sucursal_id`
 *   actualizado), persiste en DataStore, y devuelve el [MeResponseDto] para
 *   que el caller actualice la UI.
 *
 * **Sprint F56 (2026-08-17):** auto-refresh del JWT.
 * - [refreshToken] llama a `POST /api/v1/auth/refresh` con el JWT actual
 *   (incluso si ya expiro) y obtiene uno nuevo. Si el backend responde OK,
 *   reemplaza el cache en memoria y el DataStore (vía [AuthStore.saveTokenOnly]).
 *   Si falla, emite [SessionExpiredEvent] para que la UI (LoginScreen)
 *   muestre el formulario otra vez, y limpia la sesion.
 * - [startProactiveRefresh] arranca una coroutine en background que
 *   chequea el timestamp de expiracion cada 60s. Si el JWT esta a
 *   <5 min de expirar, dispara [refreshToken] preventivamente. Asi el
 *   operador no nota la renovacion (no ve 401 transitorios).
 * - El [cl.csae.pos.data.api.TokenAuthenticator] (OkHttp `Authenticator`)
 *   es el segundo frente: si llega un 401 de todos modos (ej: el JWT
 *   vencio justo entre chequeos), OkHttp invoca el authenticator, que
 *   llama a [refreshToken] y reintenta el request original.
 */
class AuthRepository(
    private val api: PosApiService,
    private val authStore: IAuthStore,
    private val dispositivoPosActual: DispositivoPosActual,
    private val appContext: Context?,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile private var jwtCache: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * F56: Evento que la UI (LoginScreen) observa para mostrar el form
     * otra vez cuando el refresh falla. Replay=0 porque solo nos interesa
     * el evento futuro (no re-emitir al rotar el composable). Buffer=16
     * para tolerar collectors lentos sin perder eventos.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 16)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    /**
     * F56: job del refresh proactivo. Vive en el [scope] de la instancia
     * (no del composable). Se cancela en [stopProactiveRefresh] /
     * [logout].
     */
    private var proactiveRefreshJob: Job? = null

    // F3: cache en memoria de las sucursales del casino. Se popula via
    // [me] y se limpia en [logout] (via [resetSession] en ServiceLocator).
    // El StateFlow permite que el SucursalSelectScreen lo observe y se
    // re-renderice cuando el user cambia de sucursal desde Configuracion.
    private val _sucursalesDisponibles = MutableStateFlow<List<SucursalDto>>(emptyList())
    val sucursalesDisponibles: StateFlow<List<SucursalDto>> = _sucursalesDisponibles.asStateFlow()

    init {
        // Pre-cargar el JWT de DataStore en background al iniciar.
        scope.launch { jwtCache = authStore.getToken() }
    }

    /** Provider del JWT actual, para pasarselo al `ApiClient.build`. */
    val jwtProvider: () -> String? = { jwtCache ?: runBlocking { authStore.getToken() } }

    /**
     * **F56 (2026-08-17):** setter de `jwtCache` solo para tests. La
     * forma de uso normal es via [login] (que setea el cache despues
     * del 200) o [refreshToken] (idem). Los tests unitarios lo usan
     * para simular un operador ya logueado sin tener que pasar por
     * todo el flujo de login.
     */
    internal fun setJwtCacheForTest(token: String?) {
        jwtCache = token
    }

    /**
     * F3: perfil del usuario incluyendo la sucursalId activa (del DataStore,
     * poblada por el login si el user tiene sucursal default, o por
     * [cambiarSucursal] cuando el user la cambia despues).
     *
     * El `combine` de coroutines solo tiene overloads hasta 5 args. Para 6+
     * se hace un combine anidado: primero 2 grupos de 3, despues con el
     * token. Asi el tipado queda limpio sin data classes auxiliares.
     */
    private val basicUser: Flow<Triple<String?, String?, String?>> = combine(
        authStore.email,
        authStore.displayName,
        authStore.rol,
    ) { email, name, rol -> Triple(email, name, rol) }

    private val idsUser: Flow<Pair<String?, String?>> = combine(
        authStore.restauranteId,
        authStore.sucursalId,
    ) { restId, sucId -> restId to sucId }

    val currentUser: Flow<UsuarioPos?> = combine(
        basicUser,
        idsUser,
        authStore.token,
    ) { basic, ids, token ->
        if (token == null) null
        else UsuarioPos(
            username = basic.first ?: "",
            displayName = basic.second ?: basic.first ?: "",
            email = basic.first ?: "",
            rol = basic.third ?: "",
            restauranteId = ids.first,
            sucursalId = ids.second,
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
            // F3: si el login devuelve una sucursal default, la persistimos.
            // Si es null, NO borramos la anterior (eso lo hace setSucursal(null)
            // cuando el user elige "casino completo" en el selector).
            // F56: parsear expiresAt ISO 8601 -> millis Unix y persistir.
            val expiresAtMillis = parseExpiresAtToMillis(body.expiresAt)
            authStore.save(
                token = body.token,
                email = body.email,
                displayName = body.email,
                rol = body.rol,
                restauranteId = body.restauranteId,
                sucursalId = body.sucursalId,
                expiresAt = expiresAtMillis,
            )
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

            // F3: re-bajar la lista de sucursales del casino (si es OperadorPos).
            // Se hace en background para no bloquear el login, pero ANTES de
            // retornar para que el [SucursalSelectScreen] post-login ya tenga
            // la lista lista. Si falla, no es fatal: el screen puede llamar
            // [me] de nuevo.
            try {
                val meResp = api.me()
                if (meResp.isSuccessful) {
                    val me = meResp.body()
                    if (me != null) {
                        _sucursalesDisponibles.value = me.sucursales
                    }
                }
            } catch (_: Exception) {
                // Ignorar: el operador puede reintentar desde Configuracion.
            }

            // F19: reconciliador al login. Auto-selecciona el dispositivo POS
            // del operador si el AndroidId del telefono matchea uno del casino.
            // No fatal si falla — el operador puede elegir manualmente.
            reconciliarDispositivoPos()

            // F56: arrancar el refresh proactivo en background. Solo si
            // pudimos parsear el expiresAt del login (si no, no hay forma
            // de saber cuando renovar y queda en manos del TokenAuthenticator
            // reactivo).
            if (expiresAtMillis != null) {
                startProactiveRefresh()
            }

            Result.success(
                UsuarioPos(
                    username = body.email,
                    displayName = body.email,
                    email = body.email,
                    rol = body.rol,
                    restauranteId = body.restauranteId,
                    sucursalId = body.sucursalId,
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun logout() {
        // F56: parar el refresh proactivo (si estaba corriendo). El
        // cancel es no-op si el job ya termino.
        stopProactiveRefresh()
        // Sprint F16: clear() ya limpia todos los campos del DataStore,
        // incluyendo los de casino (casinoId, colorPrimario, etc).
        // F56: clear() tambien limpia KEY_TOKEN_EXPIRES_AT.
        authStore.clear()
        jwtCache = null
        // F3: limpiar la cache de sucursales para que el proximo login
        // empiece con lista vacia (se re-baja via [me] en el login).
        _sucursalesDisponibles.value = emptyList()
    }

    /**
     * F3: consulta el perfil completo del user al backend y actualiza la
     * cache de sucursales. Usado por:
     * - [SucursalSelectScreen] post-login (si [login] no las bajo).
     * - El boton "Refrescar sucursales" de Configuracion.
     */
    suspend fun me(): Result<MeResponseDto> {
        return try {
            val resp = api.me()
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
            _sucursalesDisponibles.value = body.sucursales
            Result.success(body)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * F3: cambia la sucursal activa. Llama a POST /api/v1/auth/cambiar-sucursal,
     * reemplaza el JWT cacheado por el nuevo (que lleva el claim `sucursal_id`
     * actualizado), persiste en DataStore, y devuelve el [CambiarSucursalResponseDto]
     * para que el caller actualice la UI.
     *
     * **Importante:** despues de un cambio de sucursal exitoso, el cliente
     * debe re-bajar el [CatalogRepository] (los comensales/servicios son
     * distintos por sucursal). Esto NO se hace aca para no acoplar el repo
     * de auth al de catalog; el caller (SucursalSelectScreen o ConfiguracionScreen)
     * lo gatilla.
     */
    suspend fun cambiarSucursal(sucursalId: String): Result<CambiarSucursalResponseDto> {
        return try {
            val resp = api.cambiarSucursal(CambiarSucursalRequestDto(sucursalId))
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
            // Reemplazar el JWT cacheado por el nuevo (sincronico, el ApiClient
            // lo lee en el interceptor de OkHttp).
            jwtCache = body.token
            // Persistir la nueva sucursal en DataStore para que sobreviva
            // entre sesiones (igual que el restauranteId).
            authStore.setSucursal(body.sucursalId)
            Result.success(body)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * F19: reconciliador al login. Pregunta al backend la lista de
     * dispositivos POS del casino y matchea el AndroidId del telefono
     * contra `androidId` de cada uno. Si hay match, auto-selecciona.
     * Si no (dispositivo nuevo o no registrado por el admin), deja
     * la seleccion en null y la UI lo muestra como "Sin asignar" —
     * el operador lo puede elegir manualmente en Configuracion.
     *
     * No es fatal si falla: el login sigue OK y el operador puede
     * seleccionar a mano.
     */
    private suspend fun reconciliarDispositivoPos() {
        try {
            // F56 (2026-08-18): appContext es nullable para tests
            // (la interfaz AuthRepository era testeable pero el Context
            // no lo es sin Robolectric). Si no hay context, saltamos
            // la reconciliacion — el operador puede elegir manualmente.
            val ctx = appContext ?: return
            val androidId = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
            if (androidId.isNullOrBlank()) return

            // Si ya hay un dispositivo seleccionado en DataStore, no
            // sobreescribimos — la seleccion del operador gana. Solo
            // auto-seleccionamos si no hay ninguna.
            if (dispositivoPosActual.current.value != null) return

            val resp = api.listarDispositivos()
            if (!resp.isSuccessful) return
            val match = resp.body().orEmpty()
                .firstOrNull { it.activo && it.androidId == androidId }
            if (match != null) {
                dispositivoPosActual.setDispositivo(
                    id = match.id,
                    nombre = match.nombre,
                    codigo = match.androidId,
                    tipo = match.tipo,
                )
            }
        } catch (_: Exception) {
            // Silenciar: es un nice-to-have, no debe romper el login.
        }
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

    /**
     * **F56 (2026-08-17):** refresca el JWT actual contra el backend.
     *
     * Flujo:
     * 1. Lee el token actual del `jwtCache` en memoria. Si esta null, falla
     *    con un error claro.
     * 2. Llama a `POST /api/v1/auth/refresh` con ese token.
     * 3. Si el backend responde 200 OK: reemplaza el `jwtCache`,
     *    persiste el nuevo token + timestamp de expiracion en [AuthStore]
     *    (vía [AuthStore.saveTokenOnly], que NO toca el resto del estado
     *    del operador).
     * 4. Si falla (4xx, 5xx, IOException): emite [SessionExpiredEvent] y
     *    limpia la sesion. La UI (LoginScreen) observa ese evento y
     *    muestra el form otra vez.
     *
     * Es seguro llamar a esta funcion desde el [TokenAuthenticator]
     * (que corre en un thread de OkHttp con `runBlocking`) o desde la
     * coroutine del refresh proactivo: la unica escritura sincronica al
     * cache es `jwtCache = newToken` (volatile), el resto son suspend.
     */
    suspend fun refreshToken(): Result<RefreshTokenResponse> {
        val currentToken = jwtCache
        if (currentToken.isNullOrBlank()) {
            // Sin token, no hay nada que refrescar. Emite el evento igual:
            // si el operador esta en la app, lo devolvemos al login.
            emitSessionExpired()
            return Result.failure(IllegalStateException("No hay token para refrescar."))
        }
        return try {
            val resp = api.refresh(RefreshTokenRequest(currentToken))
            if (!resp.isSuccessful) {
                val code = resp.code()
                // 4xx: token invalido (firma rota, etc). Es error del cliente
                // y no se va a resolver solo. Sesion expirada para el operador.
                // 5xx: backend caido / blip. Es transitorio. NO emitir
                // session expired — el siguiente retry del refresh proactivo
                // lo intentara de nuevo. El TokenAuthenticator (que corre en
                // paralelo por cada request) va a devolver el 5xx al operador
                // si el problema persiste, y ahi la UI va a mostrar el
                // error concreto.
                if (code in 400..499) {
                    emitSessionExpired()
                }
                val msg = parseError(resp.errorBody()?.string(), code)
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: run {
                emitSessionExpired()
                return Result.failure(IllegalStateException("Respuesta vacia del API."))
            }
            val expiresAtMillis = parseExpiresAtToMillis(body.expiresAt)
            if (expiresAtMillis == null) {
                // El backend nos devolvio un expiresAt que no pudimos parsear.
                // Logico seguir (el token funciona) pero sin refresh proactivo.
                // No emitimos session expired porque el token SIRVE.
            } else {
                authStore.saveTokenOnly(body.token, expiresAtMillis)
            }
            // Reemplazar el cache en memoria (volatile) — los interceptors
            // y el TokenAuthenticator lo leen en cada request.
            jwtCache = body.token
            Result.success(body)
        } catch (t: Throwable) {
            // IOException, timeout, etc. NO emitimos session expired porque
            // podria ser un blip transitorio (backend reiniciandose, red
            // intermitente). El siguiente retry del refresh proactivo lo
            // intentara de nuevo. El TokenAuthenticator (que corre en
            // paralelo por cada request) va a devolver un 401 al operador
            // si el problema persiste, y ahi la UI va a mostrar el error
            // concreto.
            Result.failure(t)
        }
    }

    /**
     * **F56 (2026-08-17):** arranca la coroutine que chequea el timestamp
     * de expiracion del JWT cada 60 segundos. Si esta a <5 min de expirar,
     * llama a [refreshToken] preventivamente.
     *
     * Es idempotente: si ya hay un job corriendo, lo reemplaza. Llamar
     * multiples veces es seguro.
     */
    fun startProactiveRefresh() {
        stopProactiveRefresh()
        proactiveRefreshJob = scope.launch {
            while (true) {
                try {
                    val expiresAt = authStore.getTokenExpiresAt()
                    val now = System.currentTimeMillis()
                    val shouldRefresh = expiresAt != null &&
                        expiresAt - now <= PROACTIVE_REFRESH_LEAD_MS
                    if (shouldRefresh) {
                        // refreshToken() es suspend. Si falla, el job sigue
                        // vivo y reintenta en el proximo tick (60s).
                        refreshToken()
                    }
                } catch (_: Throwable) {
                    // Cualquier excepcion (incluyendo CancellationException
                    // cuando stopProactiveRefresh cancela el job) NO debe
                    // matar la coroutine. El try/catch ya absorbe, y el
                    // while(true) sigue.
                }
                delay(PROACTIVE_REFRESH_TICK_MS)
            }
        }
    }

    /**
     * **F56 (2026-08-17):** cancela la coroutine del refresh proactivo.
     * Llamado por [logout] y al destruir el repositorio (no tenemos
     * destructor explicito porque el repos vive lo que vive el proceso).
     */
    fun stopProactiveRefresh() {
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
    }

    /**
     * **F56 (2026-08-17):** emite [SessionExpiredEvent] y limpia la sesion
     * (DataStore + cache de JWT). Usado por [refreshToken] cuando el
     * backend rechaza el refresh, y por el [TokenAuthenticator] cuando
     * el reintento post-refresh tambien falla.
     *
     * Es `private` porque solo este repo debe poder dispararlo (asi
     * mantenemos el acoplamiento bajo y la UI sigue siendo reactiva al
     * SharedFlow publico).
     */
    private suspend fun emitSessionExpired() {
        // Intentar emitir; si no hay collector, el buffer (16) aguanta.
        _sessionExpired.emit(Unit)
        // Limpiar estado local para que la UI que observe `isLoggedIn`
        // tambien reaccione y muestre el Login. NO usamos `logout()` que
        // ademas limpia caches de sucursales — el operador probablemente
        // va a re-loguearse inmediatamente y queremos que el re-login
        // baje la lista de sucursales de nuevo.
        authStore.clear()
        jwtCache = null
    }

    /**
     * **F56 (2026-08-17):** lee el email persistido en DataStore. Lo usa
     * el [cl.csae.pos.ui.screens.LoginScreen] para pre-llenar el campo
     * "Usuario" cuando la sesion expiro (asi el operador solo tiene que
     * escribir el password).
     */
    suspend fun getLastEmail(): String? = authStore.email.first()

    /**
     * **F56 (2026-08-17):** parsea un `expiresAt` ISO 8601 (formato
     * `2026-08-17T23:59:59Z` o `2026-08-17T23:59:59+00:00`) a millis Unix.
     * Devuelve `null` si el string esta vacio o no se puede parsear.
     *
     * Usa `java.time.Instant` (API 26+, nuestro `minSdk = 26`) asi que
     * no necesita desugaring ni ThreeTenABP.
     */
    private fun parseExpiresAtToMillis(expiresAt: String?): Long? {
        if (expiresAt.isNullOrBlank()) return null
        return try {
            Instant.parse(expiresAt).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
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

    private companion object {
        // F56: ventana de "refresca ya" antes de la expiracion. 5 min es
        // suficiente para que el round-trip al backend (~100-300ms) termine
        // antes de que el JWT actual se vuelva inutilizable.
        const val PROACTIVE_REFRESH_LEAD_MS: Long = 5 * 60 * 1000L
        // F56: cada cuanto la coroutine proactiva chequea el timestamp.
        // 60s = balance entre precision y overhead (un read a DataStore por
        // minuto es despreciable).
        const val PROACTIVE_REFRESH_TICK_MS: Long = 60 * 1000L
    }
}
