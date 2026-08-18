package cl.csae.pos.di

import android.content.Context
import cl.csae.pos.BuildConfig
import cl.csae.pos.data.api.ApiClient
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.api.RefreshTokenResponse
import cl.csae.pos.data.api.TokenAuthenticator
import cl.csae.pos.data.bluetooth.PrinterService
import cl.csae.pos.data.prefs.AuthStore
import cl.csae.pos.data.repository.AuthRepository
import cl.csae.pos.data.selection.DispositivoPosActual
import cl.csae.pos.data.repository.CatalogRepository
import cl.csae.pos.data.repository.ConsumoRepository
import cl.csae.pos.data.repository.TicketCacheRepository
import cl.csae.pos.data.repository.TicketValidarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * ServiceLocator: punto unico de acceso a las dependencias de la app.
 *
 * Inicializado en [CsaePosApplication.onCreate]. Todos los singletons viven
 * en este objeto; las pantallas los piden por nombre segun necesidad.
 *
 * **Por que singleton:** las pantallas son stateless (no usamos ViewModel)
 * y los repos tienen estado compartido (cache de catalog, cache de JWT).
 * Una sola instancia por proceso es lo correcto.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    /**
     * Scope de coroutines a nivel aplicacion. NO esta atado al ciclo de vida
     * de la UI, asi que sobrevive a navegaciones que destruyen composables
     * (ej: `popUpTo(0) { inclusive = true }` en logout). Usar para
     * operaciones que deben completarse aunque la UI se destruya.
     *
     * **Por que se necesita (fix 2026-08-12):** antes el logout se hacia en
     * un `rememberCoroutineScope()` dentro de `CsaeNavHost`. Cuando el
     * NavHost se destruia por el `popUpTo(0)`, el scope se cancelaba y
     * `authRepo.logout()` quedaba a medias, lo que podia dejar la app en
     * estado inconsistente (DataStore con token pero UI en login) o crashear
     * al navegar.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    val authStore: AuthStore by lazy { AuthStore(appContext) }

    val authRepo: AuthRepository by lazy {
        AuthRepository(posApiService, authStore, dispositivoPosActual, appContext)
    }

    /**
     * F56: alias publico del [AuthRepository.sessionExpired] para que la
     * UI (LoginScreen) lo observe con un path corto. Es un SharedFlow
     * (no StateFlow) — solo emite eventos cuando la sesion expira; no
     * tiene valor "inicial" que chequear.
     */
    val sessionExpiredEvent get() = authRepo.sessionExpired

    /**
     * F56: ApiClient con TokenAuthenticator que, en caso de 401, llama a
     * `authRepo.refreshToken()` para obtener un JWT nuevo y reintentar
     * el request original.
     *
     * **Orden de inicializacion (lazy):** `posApiService` depende del
     * `authRepo` (vía el lambda `refreshProvider`), pero el `authRepo`
     * depende del `posApiService` (parametro del constructor). Esto NO
     * es un ciclo: ambos son `by lazy` y la primera vez que se accede a
     * uno, el otro ya esta inicializado (o se inicializa al toque). La
     * clave es que el lambda `refreshProvider` se evalua DENTRO del
     * authenticator, en el momento del 401, NO en la construccion.
     *
     * El `onSessionExpired` se cablea a `authRepo` también por lazy, asi
     * que cuando el authenticator lo invoca, ambos ya estan vivos.
     */
    val posApiService: PosApiService by lazy {
        val authenticator = TokenAuthenticator(
            jwtProvider = { authRepo.jwtProvider() },
            refreshProvider = { currentToken ->
                // runBlocking porque OkHttp Authenticator no es suspend.
                // Se ejecuta en el thread pool de OkHttp (no en main).
                runBlocking { authRepo.refreshToken() }
                    .map { it as RefreshTokenResponse }
            },
            onSessionExpired = {
                // El authenticator corrio y no pudo refrescar. Avisamos al
                // repo para que emita el evento a la UI. Tambien lo hace
                // refreshToken() en el camino de failure, pero por si
                // llegamos aca sin pasar por ahi (ej: token ya era null).
                applicationScope.launch {
                    try {
                        // Si el operador ya estaba deslogueado, no pasa
                        // nada. Si no, esto dispara el evento.
                        if (authRepo.jwtProvider() != null) {
                            // Forzamos el emit: refreshToken() ya fallo,
                            // pero queremos que la UI reaccione.
                            // authRepo.refreshToken() emite el evento por
                            // su cuenta, asi que llamamos al mismo.
                            authRepo.refreshToken()
                        }
                    } catch (_: Throwable) { /* swallow */ }
                }
            },
        )
        ApiClient.build(
            baseUrl = BuildConfig.API_BASE_URL,
            jwtProvider = { authRepo.jwtProvider() },
            tokenAuthenticator = authenticator,
        )
    }

    val catalogRepo: CatalogRepository by lazy { CatalogRepository(posApiService) }

    val consumoRepo: ConsumoRepository by lazy { ConsumoRepository(posApiService, catalogRepo) }

    val ticketValidarRepo: TicketValidarRepository by lazy { TicketValidarRepository(posApiService) }

    val ticketCache: TicketCacheRepository by lazy { TicketCacheRepository() }

    val printerService: PrinterService by lazy { PrinterService(appContext) }

    // F19: dispositivo POS actualmente seleccionado (DataStore + singleton,
    // replica el patron de la web DispositivoPosActual.cs Sprint 5.4).
    val dispositivoPosActual: DispositivoPosActual by lazy { DispositivoPosActual(authStore) }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** Llamado desde el flujo de logout para limpiar caches en memoria. */
    fun resetSession() {
        catalogRepo.clear()
        ticketCache.clear()
    }

    /**
     * Logout + reset atomico en el [applicationScope] (sobrevive a la
     * destruccion del NavHost por `popUpTo(0)`). Reemplaza el patron anterior
     * `scope.launch { logout(); resetSession() }` que se cancelaba al
     * destruirse el composable del NavHost.
     */
    fun logoutAndReset() {
        applicationScope.launch {
            try {
                authRepo.logout()
            } catch (_: Throwable) { /* swallow: logout es best-effort */ }
            resetSession()
        }
    }
}
