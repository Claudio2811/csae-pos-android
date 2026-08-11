package cl.csae.pos.di

import android.content.Context
import cl.csae.pos.BuildConfig
import cl.csae.pos.data.api.ApiClient
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.bluetooth.PrinterService
import cl.csae.pos.data.prefs.AuthStore
import cl.csae.pos.data.repository.AuthRepository
import cl.csae.pos.data.selection.DispositivoPosActual
import cl.csae.pos.data.repository.CatalogRepository
import cl.csae.pos.data.repository.ConsumoRepository
import cl.csae.pos.data.repository.TicketCacheRepository
import cl.csae.pos.data.repository.TicketValidarRepository

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

    val authStore: AuthStore by lazy { AuthStore(appContext) }

    val posApiService: PosApiService by lazy {
        ApiClient.build(
            baseUrl = BuildConfig.API_BASE_URL,
            jwtProvider = { authRepo.jwtProvider() },
        )
    }

    val authRepo: AuthRepository by lazy { AuthRepository(posApiService, authStore) }

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
}
