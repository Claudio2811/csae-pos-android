package cl.csae.pos.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/**
 * **Sprint F26 (2026-08-14):** Interceptor que reescribe el body de error
 * de las respuestas 4xx/5xx del backend para que el mensaje que ve
 * el operador incluya el `title` y `detail` del RFC 7807 ProblemDetails
 * (en vez del JSON crudo que el backend devuelve por defecto).
 *
 * Sin este interceptor, cuando el backend devuelve:
 *   404 + application/problem+json
 *   {"type":"...","title":"Recurso no encontrado","detail":"No se
 *   encontro comensal con RUT 12.345.678-9 en este casino.","status":404}
 *
 * El operador ve en el Snackbar:
 *   "HTTP 404 Not Found" (sin descripcion)
 *
 * Con este interceptor, ve:
 *   "404 - Recurso no encontrado: No se encontro comensal con RUT
 *   12.345.678-9 en este casino."
 *
 * **Por que RFC 7807:** el backend (CSAE.Api) tiene un
 * `ErrorHandlingMiddleware` que convierte las excepciones en
 * ProblemDetails con `title` + `detail` + `status`. Este interceptor
 * aprovecha eso para mostrar mensajes utiles al operador del POS
 * (que esta en una tablet, sin acceso a logs ni Swagger).
 *
 * **Compatibilidad:** si la respuesta NO es `application/problem+json`
 * (ej: un proxy que devolvio HTML), dejamos el body intacto para que
 * el codigo que llama vea el error original.
 */
object ProblemDetailsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful && isProblemJson(response)) {
            val rawBody = response.body?.string() ?: ""
            val formatted = formatProblemDetails(response.code, rawBody)
            // Cerramos el body original (es one-shot) y creamos uno nuevo
            // con el mensaje formateado. El codigo que llama (Snackbar)
            // ve el texto formateado en ex.message.
            response.body?.close()
            val newBody = formatted.toResponseBody("text/plain; charset=utf-8".toMediaType())
            return response.newBuilder().body(newBody).build()
        }
        return response
    }

    private fun isProblemJson(response: Response): Boolean {
        val contentType = response.header("Content-Type") ?: return false
        return contentType.contains("application/problem+json", ignoreCase = true)
    }

    /**
     * Parsea el body ProblemDetails y devuelve un string legible con formato:
     *   "{status} - {title}: {detail}"
     * Si algun campo falta, lo omite. Si el body no es JSON valido,
     * devuelve el body crudo.
     */
    private fun formatProblemDetails(status: Int, rawBody: String): String {
        if (rawBody.isBlank()) return "Error $status"
        return try {
            val json = JSONObject(rawBody)
            val title = json.optString("title", "").takeIf { it.isNotBlank() }
            val detail = json.optString("detail", "").takeIf { it.isNotBlank() }
            // FluentValidation: { errors: { FieldName: ["msg1","msg2"] } }
            val errorsJson = json.optJSONObject("errors")
            val errorsSummary = if (errorsJson != null) {
                errorsJson.keys().asSequence()
                    .flatMap { field -> errorsJson.getJSONArray(field).let { arr ->
                        (0 until arr.length()).map { "$field: ${arr.getString(it)}" }
                    }}
                    .joinToString(" | ")
            } else null

            val parts = mutableListOf<String>()
            parts.add(status.toString())
            if (title != null) parts.add(title)
            val main = parts.joinToString(" - ")
            when {
                detail != null && errorsSummary != null -> "$main: $detail | $errorsSummary"
                detail != null -> "$main: $detail"
                errorsSummary != null -> "$main: $errorsSummary"
                else -> main
            }
        } catch (e: Exception) {
            // Body no es JSON, devolvemos el texto crudo para no perder info.
            "Error $status: $rawBody"
        }
    }
}
