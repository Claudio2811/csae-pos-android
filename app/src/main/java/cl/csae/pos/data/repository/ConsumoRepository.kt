package cl.csae.pos.data.repository

import cl.csae.pos.data.api.ApiError
import cl.csae.pos.data.api.ConsumoListItemDto
import cl.csae.pos.data.api.MarcarTicketImpresoRequestDto
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.api.RegistrarConsumoRequestDto
import cl.csae.pos.data.api.RegistrarConsumoResponseDto
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Consumo: registra el consumo + ticket contra la API.
 *
 * Offline-first:
 * - Generamos un `IdempotencyKey` local ANTES de enviar.
 * - Si la red falla, el consumo queda encolado y se reintenta al recuperar conexion.
 * - Si el server ya vio el key (porque llego nuestra request anterior pero perdimos
 *   la respuesta), devuelve el mismo ticket sin crear duplicado.
 *
 * Para MVP, NO encolamos offline: si la red falla, retornamos error y el operador
 * re-intenta manualmente. Esto es suficiente para casinos con red estable.
 */
class ConsumoRepository(
    private val api: PosApiService,
    private val catalog: CatalogRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()
    private val contador = AtomicLong(0)
    private val fmtNumero = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val fmtFechaHora = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("es", "CL"))
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Registra un consumo + ticket contra la API.
     *
     * @param membresiaId la membresia del comensal en este casino.
     * @param servicioId el servicio a consumir.
     * @param operador nombre del operador (para el ticket fisico).
     * @param numeroSerieImpresora opcional, se manda despues de imprimir.
     */
    suspend fun registrar(
        membresiaId: String,
        servicioId: String,
        operador: String,
    ): Result<Ticket> {
        return try {
            val ahora = Date()
            val idem = generarIdempotencyKey(ahora)
            val req = RegistrarConsumoRequestDto(
                membresiaId = membresiaId,
                servicioId = servicioId,
                fechaConsumoUtc = isoUtc.format(ahora),
                idempotencyKey = idem,
                generarTicket = true,
                observaciones = null,
            )
            val resp = api.registrarConsumo(req)
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))

            // Para el ticket local, necesitamos los datos del comensal. Los tenemos
            // en el cache del catalog. Si no esta, intentamos re-bajarlo una vez.
            var comensal = catalog.buscarComensalPorId(body.comensalId)
            if (comensal == null) {
                catalog.refresh().getOrNull()
                comensal = catalog.buscarComensalPorId(body.comensalId)
            }

            if (comensal == null) {
                throw IllegalStateException("Comensal no esta en el catalog. Re-sincroniza.")
            }

            val ticketNumero = body.ticketNumero
                ?: generarNumeroLocal(ahora) // fallback si el server no devuelve ticket
            val servicio = comensal.servicios.firstOrNull { it.id == body.servicioId }
                ?: Servicio(body.servicioId, "(servicio)", "?", body.precioClp)
            Result.success(
                Ticket(
                    numero = ticketNumero,
                    comensal = comensal,
                    servicio = servicio,
                    fechaHora = fmtFechaHora.format(ahora),
                    operador = operador,
                    consumoId = body.consumoId,
                    ticketId = body.ticketId,
                    precio = body.precioClp,
                    qrToken = body.qrToken,
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Marca el ticket como impreso fisicamente (despues de imprimir por Bluetooth). */
    suspend fun marcarImpreso(ticketId: String, numeroSerieImpresora: String? = null): Result<Unit> {
        return try {
            val resp = api.marcarTicketImpreso(ticketId, MarcarTicketImpresoRequestDto(numeroSerieImpresora))
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Lista los consumos del turno actual: desde 00:00:00 UTC de hoy.
     * Usado por [ConsumosScreen] (sprint 3.2).
     */
    suspend fun listarConsumosDelTurno(): Result<List<ConsumoListItemDto>> {
        return try {
            val desdeUtc = hoyUtcMedianoche()
            val resp = api.listarConsumos(desdeUtc, pageSize = 500)
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val body = resp.body() ?: return Result.failure(IllegalStateException("Respuesta vacia del API."))
            Result.success(body.items)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun hoyUtcMedianoche(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return isoUtc.format(cal.time)
    }

    private fun generarIdempotencyKey(fecha: Date): String {
        // UUID v4 (random) + timestamp local para evitar colisiones.
        // El server lo guarda en Consumo.IdempotencyKey y dedupica.
        return java.util.UUID.randomUUID().toString()
    }

    private fun generarNumeroLocal(fecha: Date): String {
        val n = contador.incrementAndGet()
        return "T-${fmtNumero.format(fecha)}-${n.toString().padStart(6, '0')}"
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
