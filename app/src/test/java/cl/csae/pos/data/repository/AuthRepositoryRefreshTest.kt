package cl.csae.pos.data.repository

import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.prefs.IAuthStore
import cl.csae.pos.data.selection.DispositivoPosActual
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

/**
 * **F56 (2026-08-17):** tests del [AuthRepository.refreshToken].
 *
 * Cubre los 3 escenarios del spec + 1 extra (login persiste expiresAt):
 *  1. `Refresh_OK_ReemplazaJwtCacheYPersisteToken`: el backend devuelve
 *     200 con un token nuevo. El repo debe reemplazar `jwtCache` y
 *     persistir el nuevo token + expiresAt.
 *  2. `Refresh_4xx_EmiteSessionExpiredYCleanup`: el backend rechaza el
 *     refresh (firma invalida, etc). El repo debe emitir
 *     `sessionExpired` y limpiar el cache.
 *  3. `Refresh_5xx_NoEmiteEvent_ReintentaMasTarde`: error de red /
 *     5xx. NO emite session expired (es transitorio).
 *  4. `LoginOK_PersistsExpiresAt`: el login exitoso persiste el
 *     `expiresAt` en el AuthStore (para que el refresh proactivo
 *     funcione).
 *
 * **Patrón:** MockWebServer provee la API real (vía Retrofit). El
 * [AuthStore] es un `FakeAuthStore` que NO toca DataStore (en tests
 * unitarios no hay Context). Solo overridea los métodos que el repo
 * necesita para refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PosApiService
    private lateinit var fakeStore: FakeAuthStore
    private lateinit var dispositivoPosActual: DispositivoPosActual
    private lateinit var repo: AuthRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        // F56: necesario para que cualquier launch en el repo corra en
        // nuestro dispatcher de test (asi runTest puede esperarlos).
        Dispatchers.setMain(testDispatcher)

        server = MockWebServer()
        server.start()

        // Retrofit simple, sin Authenticators ni Interceptors raros — solo
        // nos importa la capa HTTP para el refresh.
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(
                testJson.asConverterFactory(
                    "application/json".toMediaType(),
                ),
            )
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()

        api = retrofit.create(PosApiService::class.java)

        fakeStore = FakeAuthStore()
        // No inicializamos dispositivoPosActual con AuthStore real.
        // El repo solo lo usa en `reconciliarDispositivoPos()` (login)
        // que no ejercitamos en estos tests.
        dispositivoPosActual = DispositivoPosActual(fakeStore)

        // appContext = null es OK: solo se usa en
        // `reconciliarDispositivoPos()` (login path), que no testeamos.
        // El constructor de AuthRepository lo acepta como nullable.
        repo = AuthRepository(api, fakeStore, dispositivoPosActual, appContext = null)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `Refresh_OK_ReemplazaJwtCacheYPersisteToken`() = runTest {
        // Mockear el refresh: devuelve 200 con token nuevo.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"token":"new-jwt-123","expiresAt":"2026-12-31T23:59:59Z","email":"a@b.cl","rol":"OperadorPos","restauranteId":"r-1"}""",
                ),
        )

        // Simular que el operador ya esta logueado con un token viejo.
        fakeStore.setTokenForTest("old-jwt-abc")
        repo.setJwtCacheForTest("old-jwt-abc")
        fakeStore.setTokenExpiresAtForTest(1_700_000_000_000L) // viejo

        val result = repo.refreshToken()
        assertTrue("Refresh debe ser exitoso: $result", result.isSuccess)

        // El jwtCache del repo debe ser el nuevo.
        assertEquals("new-jwt-123", repo.jwtProvider())
        // El fakeStore debe haber persistido el nuevo token + expiresAt.
        assertEquals("new-jwt-123", fakeStore.lastSavedToken)
        val expectedMillis = java.time.Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()
        assertEquals(expectedMillis, fakeStore.lastSavedExpiresAt)
        // El expiresAt anterior fue sobreescrito.
        assertEquals(expectedMillis, fakeStore.getTokenExpiresAtForTest())

        // El body del request llego con el token viejo.
        val recorded = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", recorded.path)
        assertTrue(
            "Body del refresh debe contener el token viejo",
            recorded.body.readUtf8().contains("old-jwt-abc"),
        )

        // El SharedFlow NO emitio session expired.
        // (No hay forma facil de "drain" un SharedFlow con replay=0
        // despues de emitted, asi que simplemente verificamos que el
        // jwtCache quedo vivo — si emitio y limpio, jwtCache seria null.)
        assertNotNull(repo.jwtProvider())
    }

    @Test
    fun `Refresh_4xx_EmiteSessionExpiredYCleanup`() = runTest {
        // El backend rechaza el refresh.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/problem+json")
                .setBody("""{"title":"Invalid token","status":401,"code":"INVALID_TOKEN"}"""),
        )

        // Operador logueado.
        fakeStore.setTokenForTest("old-jwt-abc")
        repo.setJwtCacheForTest("old-jwt-abc")
        fakeStore.setEmailForTest("a@b.cl")

        // Coleccionar el evento (tomamos el primer valor con timeout).
        val eventReceived = MutableStateFlow(false)
        val job = GlobalScope.launch {
            repo.sessionExpired.collect { eventReceived.value = true }
        }
        // UnconfinedTestDispatcher corre todo en el mismo thread; con
        // runTest el eventReceived deberia actualizarse antes del
        // runTest de retornar. Pero SharedFlow con replay=0 + collect
        // suspending es tricky — usamos un canal intermediario.
        // Workaround: polleamos brevemente.
        val result = repo.refreshToken()
        assertTrue("Refresh debe ser failure: $result", result.isFailure)

        // Esperar hasta 1 segundo por el evento.
        val deadline = System.currentTimeMillis() + 1000L
        while (!eventReceived.value && System.currentTimeMillis() < deadline) {
            delay(10L)
        }

        // jwtCache limpiado.
        assertNull(repo.jwtProvider())
        // AuthStore limpio (clear() fue llamado).
        assertTrue(fakeStore.clearedAtLeastOnce)
        // Evento emitido.
        assertTrue("El evento sessionExpired debio emitirse", eventReceived.value)

        job.cancel()
    }

    @Test
    fun `Refresh_5xx_NoEmiteEvent_ReintentaMasTarde`() = runTest {
        // El backend tiene un blip: 503.
        server.enqueue(MockResponse().setResponseCode(503))

        fakeStore.setTokenForTest("old-jwt-abc")
        repo.setJwtCacheForTest("old-jwt-abc")

        val eventReceived = MutableStateFlow(false)
        val job = GlobalScope.launch {
            repo.sessionExpired.collect { eventReceived.value = true }
        }
        val result = repo.refreshToken()
        assertTrue("Refresh debe ser failure: $result", result.isFailure)

        // Esperar 300ms y verificar que el evento NO se emitio.
        delay(300L)
        assertTrue("El evento sessionExpired NO debio emitirse en 5xx", !eventReceived.value)

        // jwtCache sigue vivo (no se limpio en 5xx — es transitorio).
        assertEquals("old-jwt-abc", repo.jwtProvider())
        // AuthStore NO fue limpiado.
        assertTrue(!fakeStore.clearedAtLeastOnce)

        job.cancel()
    }

    @Test
    fun `LoginOK_PersistsExpiresAt`() = runTest {
        // El login devuelve un token con expiresAt.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"token":"login-jwt","expiresAt":"2026-12-31T23:59:59Z","email":"op@casino.cl","rol":"OperadorPos","restauranteId":"r-1"}""",
                ),
        )
        // El login tambien pega /api/v1/casino y /api/v1/auth/me. Los
        // devolvemos vacios para que no fallen.
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"title":"Not found","status":404}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"userId":"u-1","email":"op@casino.cl","rol":"OperadorPos","restauranteId":"r-1","sucursales":[],"restaurantes":[]}""",
                ),
        )
        // listarDispositivos (reconciliador) -> []
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[]"),
        )

        val result = repo.login("op@casino.cl", "password123")
        assertTrue("Login debe ser exitoso: $result", result.isSuccess)

        // El expiresAt persistido debe matchear el ISO parseado.
        val expectedMillis = java.time.Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()
        val actualExpiresAt = fakeStore.getTokenExpiresAtForTest()
        assertEquals("getTokenExpiresAt debe estar persistido", expectedMillis, actualExpiresAt)

        // El expiresAt debe ser FUTURO (en 2026+).
        val now = System.currentTimeMillis()
        assertTrue(
            "expiresAt ($actualExpiresAt) debe ser futuro respecto a now ($now)",
            actualExpiresAt!! > now,
        )

        // El email se persitio.
        assertEquals("op@casino.cl", fakeStore.getEmailForTest())
    }

    @Test
    fun `Refresh_sin_token_en_cache_emite_session_expired`() = runTest {
        // No seteamos nada en el cache ni en el store. refreshToken
        // debe fallar y emitir session expired.
        val eventReceived = MutableStateFlow(false)
        val job = GlobalScope.launch {
            repo.sessionExpired.collect { eventReceived.value = true }
        }

        val result = repo.refreshToken()
        assertTrue(result.isFailure)

        val deadline = System.currentTimeMillis() + 1000L
        while (!eventReceived.value && System.currentTimeMillis() < deadline) {
            delay(10L)
        }
        assertTrue("El evento debio emitirse aun sin token", eventReceived.value)
        job.cancel()
    }
}

/**
 * **F56 (2026-08-17):** `IAuthStore` fake para tests unitarios.
 *
 * Implementa la interfaz delgada [IAuthStore] directamente. NO extiende
 * la clase concreta [cl.csae.pos.data.prefs.AuthStore] porque esa
 * depende de Android `Context` (DataStore), lo que haria los tests
 * impuros (necesitarian Robolectric o un Context real).
 *
 * La interfaz [IAuthStore] es chica a proposito: solo tiene los
 * metodos que el [AuthRepository] consume. Asi el fake es ~30 lineas
 * en vez de tener que mockear los 30+ miembros de la clase concreta.
 */
class FakeAuthStore : IAuthStore {
    private var _token: String? = null
    private var _expiresAt: Long? = null
    private var _email: String? = null

    var lastSavedToken: String? = null
        private set
    var lastSavedExpiresAt: Long? = null
        private set
    var clearedAtLeastOnce: Boolean = false
        private set

    override val email: Flow<String?> get() = flowOf(_email)
    override val displayName: Flow<String?> get() = flowOf(_email)
    override val rol: Flow<String?> get() = flowOf(null)
    override val restauranteId: Flow<String?> get() = flowOf(null)
    override val sucursalId: Flow<String?> get() = flowOf(null)
    override val token: Flow<String?> get() = flowOf(_token)
    override val casinoId: Flow<String?> get() = flowOf(null)
    override val casinoNombre: Flow<String?> get() = flowOf(null)
    override val casinoRut: Flow<String?> get() = flowOf(null)
    override val casinoColorPrimario: Flow<String?> get() = flowOf(null)
    override val casinoColorAcento: Flow<String?> get() = flowOf(null)
    override val casinoLogoUrl: Flow<String?> get() = flowOf(null)
    override val dispositivoId: Flow<String?> get() = flowOf(null)
    override val dispositivoNombre: Flow<String?> get() = flowOf(null)
    override val dispositivoCodigo: Flow<String?> get() = flowOf(null)
    override val dispositivoTipo: Flow<Int?> get() = flowOf(null)

    override suspend fun getToken(): String? = _token

    override suspend fun getTokenExpiresAt(): Long? = _expiresAt

    suspend fun getTokenExpiresAtForTest(): Long? = _expiresAt

    override suspend fun save(
        token: String,
        email: String,
        displayName: String,
        rol: String,
        restauranteId: String?,
        sucursalId: String?,
        expiresAt: Long?,
    ) {
        _token = token
        _email = email
        if (expiresAt != null) _expiresAt = expiresAt
    }

    override suspend fun saveTokenOnly(token: String, expiresAt: Long) {
        _token = token
        _expiresAt = expiresAt
        lastSavedToken = token
        lastSavedExpiresAt = expiresAt
    }

    override suspend fun setSucursal(sucursalId: String?) {
        // No-op para los tests de refresh.
    }

    override suspend fun saveCasinoTheme(
        casinoId: String,
        casinoNombre: String,
        casinoRut: String?,
        colorPrimario: String?,
        colorAcento: String?,
        logoUrl: String?,
    ) {
        // No-op para los tests de refresh.
    }

    override suspend fun clear() {
        _token = null
        _expiresAt = null
        _email = null
        clearedAtLeastOnce = true
    }

    override suspend fun clearSesion() {
        clear()
    }

    override suspend fun setDispositivo(id: String?, nombre: String?, codigo: String?, tipo: Int?) {
        // No-op para los tests de refresh.
    }

    // ===== helpers para setear estado desde los tests =====

    fun setTokenForTest(token: String) { _token = token }
    fun setTokenExpiresAtForTest(expiresAt: Long) { _expiresAt = expiresAt }
    fun setEmailForTest(email: String) { _email = email }
    suspend fun getEmailForTest(): String? = _email
}
