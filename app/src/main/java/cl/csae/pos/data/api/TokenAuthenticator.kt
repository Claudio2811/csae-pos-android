package cl.csae.pos.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * **F56 (2026-08-17):** OkHttp [Authenticator] que se activa cuando el
 * server devuelve 401. Toma el JWT actual, lo manda a
 * `POST /api/v1/auth/refresh` para obtener uno nuevo, y reintenta el
 * request original con el token fresco.
 *
 * **Por que [Authenticator] y no un [okhttp3.Interceptor]:**
 * - El [okhttp3.Interceptor] se ejecuta en CADA request, sin saber si fallo
 *   antes. Si uno agrega un retry-on-401 ahi, se mete en un loop infinito:
 *   el primer intento falla, se reintenta, vuelve a fallar, etc. Ademas el
 *   [Authenticator] es el unico patron que OkHttp documenta para este caso
 *   (ver `okhttp3.Authenticator` Javadoc).
 * - El [Authenticator] solo se invoca cuando:
 *     1. La respuesta es 401.
 *     2. OkHttp ya intento el request al menos 1 vez.
 *     3. El `response.priorResponse` se inspecciona para evitar loop.
 *
 * **Por que `runBlocking` aca:**
 * OkHttp no soporta coroutines en su [Authenticator] (la interfaz es
 * sincronica, definida en Java). La unica opcion es bloquear. En la
 * practica el bloqueo dura ~100-300ms (1 round-trip al backend de Azure
 * en chilecentral), asi que es aceptable. NO se hace en el main thread:
 * OkHttp invoca el [Authenticator] en su thread pool interno, asi que el
 * main thread sigue responsive.
 *
 * **Reentrancy guard:**
 * Contamos cuantos `priorResponse` lleva la cadena. Si ya hubo 2+ retries,
 * paramos. Esto previene el caso de un backend mal configurado que
 * siempre devuelva 401 (bucle infinito).
 *
 * **Refs:**
 * - https://square.github.io/okhttp/4.x/okhttp/okhttp3/Authenticator.html
 * - Reporte Azure 2026-08-17: 96.4% de los 76,605 requests del mobile son
 *   401 porque el JWT cacheado expiro y el mobile lo sigue mandando.
 */
class TokenAuthenticator(
    private val jwtProvider: () -> String?,
    private val refreshProvider: suspend (currentToken: String) -> Result<RefreshTokenResponse>,
    private val onSessionExpired: () -> Unit,
) : Authenticator {

    /**
     * Llamado por OkHttp cuando el server responde 401. Devuelve:
     * - `Request?` con el header `Authorization` actualizado -> OkHttp reintenta.
     * - `null` -> OkHttp propaga el 401 al caller.
     *
     * @param route la ruta que se intento (puede ser null segun Javadoc).
     * @param response la respuesta 401 (con `priorResponse` poblado si ya hubo retries).
     */
    override fun authenticate(route: Route?, response: Response): Request? {
        // 1) Reentrancy guard: si ya hubo 2+ retries, no intentamos mas.
        if (responseCount(response) >= MAX_RETRIES) {
            onSessionExpired()
            return null
        }

        // 2) Si el request original NO tenia Authorization, no tiene sentido
        // refrescar (el 401 es por otra razon, no por token expirado).
        val originalAuth = response.request.header("Authorization")
        if (originalAuth.isNullOrBlank()) {
            onSessionExpired()
            return null
        }

        // 3) Sacar el token actual del provider (lee del cache en memoria
        // del AuthRepository). Si esta null, no podemos refrescar.
        val currentToken = jwtProvider()?.removePrefix(BEARER_PREFIX)?.trim() ?: run {
            onSessionExpired()
            return null
        }
        if (currentToken.isBlank()) {
            onSessionExpired()
            return null
        }

        // 4) Llamar al endpoint de refresh SINCRONICAMENTE. El provider
        // devuelve Result<RefreshTokenResponse>; si falla, propagamos el 401.
        val newToken = runBlocking {
            refreshProvider(currentToken)
        }.getOrNull()?.token

        if (newToken.isNullOrBlank()) {
            onSessionExpired()
            return null
        }

        // 5) Reintentar el request original con el token nuevo.
        //    OkHttp detecta que `Authorization` cambio y NO va a re-llamar
        //    a este Authenticator otra vez por el mismo request (siempre
        //    que el nuevo valor sea distinto al anterior — ver
        //    `okhttp3.internal.connection.RealConnection`).
        return response.request.newBuilder()
            .header("Authorization", "$BEARER_PREFIX$newToken")
            .build()
    }

    /**
     * Cuenta cuantos intentos llevo esta cadena de responses. Un response
     * "fresco" (sin prior) cuenta como 1; cada `priorResponse` suma uno mas.
     */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior: Response? = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        // 2 = intentamos UNA vez despues del 401 original. Si ese tambien
        // falla, paramos. Subir a 3+ es riesgoso si el backend esta caido.
        const val MAX_RETRIES = 2
    }
}
