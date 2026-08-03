package cl.csae.pos.data.repository

import cl.csae.pos.data.api.ApiError
import cl.csae.pos.data.api.ConsumirTicketRequest
import cl.csae.pos.data.api.ConsumirTicketResponse
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.api.TicketInfoDto
import cl.csae.pos.data.api.ValidarTicketRequest
import cl.csae.pos.data.api.ValidarTicketResponse
import kotlinx.serialization.json.Json

/**
 * Repositorio para validar y consumir tickets (sprint 3.2, modo Garzon).
 *
 * Flujo del garzon:
 *   1. Escanea el QR del ticket del comensal.
 *   2. Llama a [validar] con el token del QR. El backend responde si el
 *      ticket existe, si ya fue consumido, o si no existe.
 *   3. Si es valido y no consumido, el garzon confirma el consumo
 *      llamando a [consumir] con el mismo token.
 *
 * Ambos endpoints viven en el API de CSAE (ApiPosController).
 */
class TicketValidarRepository(private val api: PosApiService) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Valida un ticket por su qrToken. */
    suspend fun validar(qrToken: String): Result<ValidarTicketResponse> {
        return try {
            val resp = api.validarTicket(ValidarTicketRequest(qrToken))
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                // Si el server responde 404 o 410, devolvemos un ValidarTicketResponse
                // con valido=false para que la UI muestre el mensaje correcto en vez de un error.
                Result.success(
                    ValidarTicketResponse(
                        valido = false,
                        mensaje = msg,
                        ticket = null,
                    )
                )
            } else {
                val body = resp.body()
                    ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
                Result.success(body)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Confirma el consumo de un ticket por su qrToken. */
    suspend fun consumir(qrToken: String): Result<ConsumirTicketResponse> {
        return try {
            val resp = api.consumirTicket(ConsumirTicketRequest(qrToken))
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                Result.success(
                    ConsumirTicketResponse(
                        ok = false,
                        mensaje = msg,
                        consumidoEnUtc = null,
                    )
                )
            } else {
                val body = resp.body()
                    ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
                Result.success(body)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Helper: extrae el TicketInfoDto del response de validar (o null). */
    fun ticketDeRespuesta(resp: ValidarTicketResponse): TicketInfoDto? = resp.ticket

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
