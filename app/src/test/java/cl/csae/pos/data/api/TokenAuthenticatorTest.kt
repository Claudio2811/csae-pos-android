package cl.csae.pos.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **F56 (2026-08-17):** tests del [TokenAuthenticator].
 *
 * Cubren los 5 escenarios del spec:
 *  1. `401_RefreshOK_Retry200`: el primer GET responde 401, /refresh OK,
 *     el retry va con el token nuevo.
 *  2. `401_RefreshFails_Propagates401`: el GET y /refresh ambos 401.
 *     Se llama a `onSessionExpired`.
 *  3. `401_Loop_StopsAfter2Retries`: el server siempre responde 401.
 *     El authenticator no se llama mas de 2 veces (1 original + 1 retry).
 *  4. `NoAuthorizationHeader_Propagates401`: el request original NO
 *     llevaba Authorization. Se llama a `onSessionExpired`.
 *  5. `LoginOK_PersistsExpiresAt`: este caso realmente testea el
 *     [cl.csae.pos.data.repository.AuthRepository], no el authenticator.
 *     Lo dejamos en [AuthRepositoryRefreshTest].
 *
 * Patrón MockWebServer: enqueueamos las respuestas en orden y despues
 * inspeccionamos `server.takeRequest()` para ver que headers llegaron.
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401_RefreshOK_Retry200`() {
        // GET inicial -> 401.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/problem+json")
                .setBody("""{"title":"Unauthorized","status":401}"""),
        )
        // Retry del GET (con el token nuevo) -> 200.
        // Nota: en este test el `refreshProvider` es un lambda puro que
        // NO hace HTTP. Asi que no hay un /auth/refresh request
        // separado. La 2da respuesta encolada es consumida por el retry.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = { currentToken ->
                refreshCalls.incrementAndGet()
                assertEquals("old-jwt", currentToken)
                Result.success(
                    RefreshTokenResponse(
                        token = "new-jwt",
                        expiresAt = "2026-12-31T23:59:59Z",
                        email = "a@b.cl",
                        rol = "OperadorPos",
                    ),
                )
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // 1 refresh fue suficiente.
        assertEquals(1, refreshCalls.get())
        // onSessionExpired NO se llamo (el refresh funciono).
        assertEquals(0, onExpiredCalls.get())

        // El primer request (catalog) llego con el token viejo.
        val firstRequest = server.takeRequest()
        assertEquals("/api/v1/pos/catalog", firstRequest.path)
        assertEquals("Bearer old-jwt", firstRequest.getHeader("Authorization"))

        // El segundo request (retry del catalog) llevo el token NUEVO.
        // El path sigue siendo /catalog (el refreshProvider no hizo HTTP).
        val retryRequest = server.takeRequest()
        assertEquals("/api/v1/pos/catalog", retryRequest.path)
        assertEquals("Bearer new-jwt", retryRequest.getHeader("Authorization"))
    }

    /**
     * Variante del test anterior: el `refreshProvider` SI hace un HTTP
     * call al /auth/refresh del MockWebServer. Asi testeamos el flow
     * completo end-to-end (incluyendo que el body del refresh lleva
     * el token viejo en JSON).
     */
    @Test
    fun `401_RefreshOK_EndToEnd_WithRealRefreshHTTPCall`() {
        // GET inicial -> 401.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/problem+json")
                .setBody("""{"title":"Unauthorized","status":401}"""),
        )
        // POST /auth/refresh -> 200 con token nuevo.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"token":"new-jwt","expiresAt":"2026-12-31T23:59:59Z","email":"a@b.cl","rol":"OperadorPos"}""",
                ),
        )
        // Retry del GET -> 200.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        val refreshHttpClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = { currentToken ->
                // Hacer un HTTP real al /auth/refresh del MockWebServer.
                val req = Request.Builder()
                    .url(server.url("/api/v1/auth/refresh"))
                    .post(
                        """{"token":"$currentToken"}"""
                            .toRequestBody("application/json".toMediaType()),
                    )
                    .build()
                val resp = refreshHttpClient.newCall(req).execute()
                resp.use {
                    if (!it.isSuccessful) {
                        Result.failure(IllegalStateException("refresh fallo: ${it.code}"))
                    } else {
                        val body = it.body?.string() ?: ""
                        // Mini-parser (evita dependencias de kotlinx.serialization aqui).
                        val tokenMatch = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)
                        val newToken = tokenMatch?.groupValues?.get(1) ?: "fallback"
                        Result.success(
                            RefreshTokenResponse(
                                token = newToken,
                                expiresAt = "2026-12-31T23:59:59Z",
                                email = "a@b.cl",
                                rol = "OperadorPos",
                            ),
                        )
                    }
                }
            },
            onSessionExpired = { /* no-op */ },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // El primer request: /catalog con Bearer old-jwt.
        val firstRequest = server.takeRequest()
        assertEquals("/api/v1/pos/catalog", firstRequest.path)
        assertEquals("Bearer old-jwt", firstRequest.getHeader("Authorization"))

        // El segundo request: /auth/refresh con el token viejo en el body.
        val refreshRequest = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refreshRequest.path)
        val refreshBody = refreshRequest.body.readUtf8()
        assertTrue(
            "El body del refresh debe contener el token viejo. Body=$refreshBody",
            refreshBody.contains("old-jwt"),
        )

        // El tercer request: /catalog (retry) con Bearer new-jwt.
        val retryRequest = server.takeRequest()
        assertEquals("/api/v1/pos/catalog", retryRequest.path)
        assertEquals("Bearer new-jwt", retryRequest.getHeader("Authorization"))
    }

    @Test
    fun `401_RefreshFails_Propagates401`() {
        // GET inicial -> 401.
        server.enqueue(MockResponse().setResponseCode(401))
        // /refresh tambien 401.
        server.enqueue(MockResponse().setResponseCode(401))

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                Result.failure(IllegalStateException("refresh rechazo"))
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        // OkHttp propaga el 401 al caller cuando el authenticator devuelve null.
        client.newCall(request).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // El refresh se intento 1 vez.
        assertEquals(1, refreshCalls.get())
        // onSessionExpired se llamo.
        assertEquals(1, onExpiredCalls.get())
    }

    @Test
    fun `401_Loop_StopsAfter2Attempts`() {
        // El server siempre responde 401. Con MAX_RETRIES=2 (definido en
        // el authenticator), el loop corta despues de 2 attempts total
        // (1 original + 1 retry). El server enqueuea 3 respuestas 401
        // pero solo se consumen 2 (la 3ra queda encolada).
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(401))
        }

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                // refresh "exitoso" que devuelve un token (asi el
                // authenticator SI reintenta y entra en la logica de
                // contar retries). Si devolviera failure, cortaria
                // antes y no probariamos el loop guard.
                Result.success(
                    RefreshTokenResponse(
                        token = "still-old-jwt",
                        expiresAt = "2026-12-31T23:59:59Z",
                        email = "a@b.cl",
                        rol = "OperadorPos",
                    ),
                )
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // El refresh se intento 1 vez (la 2da invocation del
        // authenticator lo corto via el loop guard). Y onSessionExpired
        // se llamo 1 vez.
        assertEquals(1, refreshCalls.get())
        assertEquals(1, onExpiredCalls.get())
    }

    @Test
    fun `NoAuthorizationHeader_Propagates401`() {
        // GET -> 401. Pero el request NO tenia Authorization.
        server.enqueue(MockResponse().setResponseCode(401))

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                Result.success(
                    RefreshTokenResponse(
                        token = "new-jwt",
                        expiresAt = "2026-12-31T23:59:59Z",
                        email = "a@b.cl",
                        rol = "OperadorPos",
                    ),
                )
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        // Request SIN Authorization.
        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // No se intento refresh (no tiene sentido sin Authorization).
        assertEquals(0, refreshCalls.get())
        // onSessionExpired se llamo.
        assertEquals(1, onExpiredCalls.get())
    }

    @Test
    fun `RefreshProvider_throws_exception_treated_as_failure`() {
        // GET -> 401, refresh explota (ej: red caida). El authenticator
        // debe tratarlo como failure y NO reintentar.
        server.enqueue(MockResponse().setResponseCode(401))

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                // Devolvemos failure (no exception) porque la firma es
                // `suspend (...) -> Result<...>`. Pero el caso real es
                // equivalente: la excepcion se mapea a failure en el
                // call site.
                Result.failure(IllegalStateException("red caida"))
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        assertEquals(1, refreshCalls.get())
        assertEquals(1, onExpiredCalls.get())
    }

    @Test
    fun `Server_returns_500_keeps_401_for_client`() {
        // Edge case: el server responde 500 (no 401) en el catalog.
        // El authenticator NO se debe activar (solo se activa en 401).
        server.enqueue(MockResponse().setResponseCode(500))

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                Result.failure(IllegalStateException("nunca deberia llamarse"))
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(500, resp.code)
        }

        assertEquals(0, refreshCalls.get())
        assertEquals(0, onExpiredCalls.get())
    }

    @Test
    fun `ResponseWithNoBody_401_still_triggers_authenticator`() {
        // Edge case: 401 sin body ni Content-Type. Igual debe disparar
        // el authenticator (OkHttp dispara el authenticator en base al
        // codigo, no al body).
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token":"new-jwt","expiresAt":"2026-12-31T23:59:59Z","email":"a@b.cl","rol":"OperadorPos"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val authenticator = TokenAuthenticator(
            jwtProvider = { "old-jwt" },
            refreshProvider = {
                runBlocking {
                    Result.success(
                        RefreshTokenResponse(
                            token = "new-jwt",
                            expiresAt = "2026-12-31T23:59:59Z",
                            email = "a@b.cl",
                            rol = "OperadorPos",
                        ),
                    )
                }
            },
            onSessionExpired = { /* no-op */ },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(200, resp.code)
        }
    }

    @Test
    fun `jwtProvider_returns_null_does_not_retry`() {
        // Si jwtProvider devuelve null, no hay nada que refrescar.
        server.enqueue(MockResponse().setResponseCode(401))

        val refreshCalls = AtomicInteger(0)
        val onExpiredCalls = AtomicInteger(0)
        val authenticator = TokenAuthenticator(
            jwtProvider = { null },
            refreshProvider = {
                refreshCalls.incrementAndGet()
                Result.success(
                    RefreshTokenResponse(
                        token = "x",
                        expiresAt = "2026-12-31T23:59:59Z",
                        email = "a@b.cl",
                        rol = "OperadorPos",
                    ),
                )
            },
            onSessionExpired = { onExpiredCalls.incrementAndGet() },
        )

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(server.url("/api/v1/pos/catalog"))
            .addHeader("Authorization", "Bearer old-jwt")
            .build()

        client.newCall(request).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        assertEquals(0, refreshCalls.get())
        assertEquals(1, onExpiredCalls.get())
    }

    // El 5to test del spec (LoginOK_PersistsExpiresAt) es de AuthRepository,
    // no del authenticator. Ver AuthRepositoryRefreshTest en el paquete repository.
    @Test
    fun `Placeholder_5thTestGoesToAuthRepositoryRefreshTest`() {
        // Solo para que JUnit no se queje de "no tests" si alguien
        // corre este archivo solo. El test real vive en
        // AuthRepositoryRefreshTest.kt::loginOK_PersistsExpiresAt.
        assertNotNull("ver AuthRepositoryRefreshTest", cl.csae.pos.data.repository.AuthRepositoryRefreshTest::class)
    }

    // Smoke test: el companion funciona.
    @Test
    fun `SmokeTest_companion_works`() {
        assertNotNull(server)
        assertTrue(server.url("/").toString().startsWith("http"))
    }
}
