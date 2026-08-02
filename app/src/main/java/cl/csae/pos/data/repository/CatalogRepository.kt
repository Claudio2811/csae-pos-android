package cl.csae.pos.data.repository

import cl.csae.pos.data.api.ApiError
import cl.csae.pos.data.api.ComensalCatalogItemDto
import cl.csae.pos.data.api.EmpresaCatalogItemDto
import cl.csae.pos.data.api.PosApiService
import cl.csae.pos.data.api.PosCatalogResponseDto
import cl.csae.pos.data.api.ServicioCatalogItemDto
import cl.csae.pos.data.api.ServicioHabilitadoCatalogItemDto
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Catalog: mantiene en memoria el snapshot del casino (empresas, servicios,
 * servicios habilitados, comensales) que se descarga al login.
 *
 * Por que en memoria y no en BD local (Room):
 * - El catalog es chico: 1 casino, 4 servicios, ~4 empresas, ~20 comensales.
 * - Se baja completo en una llamada `GET /pos/catalog` (4 KB aprox).
 * - Si la app se reinicia, el operador hace login de nuevo y se re-baja.
 * - Para un POS donde el operador esta logueado todo el turno, no necesitamos
 *   persistencia local.
 *
 * El Mutex protege contra doble descarga concurrente desde varias pantallas.
 */
class CatalogRepository(private val api: PosApiService) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var cache: PosCatalogResponseDto? = null
    @Volatile private var lastSync: Long = 0

    /** Trae el catalog del API (forzando descarga) y lo cachea. */
    suspend fun refresh(): Result<PosCatalogResponseDto> {
        return try {
            val resp = api.getCatalog()
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val data = resp.body() ?: return Result.failure(IllegalStateException("Catalog vacio."))
            mutex.withLock {
                cache = data
                lastSync = System.currentTimeMillis()
            }
            Result.success(data)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Devuelve el catalog cacheado; si no hay, lo descarga. */
    suspend fun getOrLoad(): Result<PosCatalogResponseDto> {
        cache?.let { return Result.success(it) }
        return refresh()
    }

    /** Busca un comensal por ID (comensalId). */
    fun buscarComensalPorId(comensalId: String): Comensal? {
        val c = cache ?: return null
        val comensal = c.comensales.firstOrNull { it.comensalId == comensalId } ?: return null
        val empresaRazon = c.empresas.firstOrNull { it.id == comensal.empresaRestauranteId }?.razonSocial
            ?: comensal.empresaRestauranteId
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(comensalesHabilitados = c.serviciosHabilitados, servicios = c.servicios),
        )
    }

    /** Busca un comensal por membresiaId. */
    fun buscarComensalPorMembresia(membresiaId: String): Comensal? {
        val c = cache ?: return null
        val comensal = c.comensales.firstOrNull { it.membresiaId == membresiaId } ?: return null
        val empresaRazon = c.empresas.firstOrNull { it.id == comensal.empresaRestauranteId }?.razonSocial
            ?: comensal.empresaRestauranteId
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(comensalesHabilitados = c.serviciosHabilitados, servicios = c.servicios),
        )
    }

    /**
     * Busca un comensal por RUT canonico (sin puntos, sin guion, mayusculas).
     *
     * Normaliza AMBOS lados: el RUT del operador puede llegar con/sin guion
     * y el RUT guardado en cache puede venir del backend con/sin guion
     * (mismo pitfall que el endpoint `/pos/comensales/buscar` del backend:
     * `c.Rut.Replace(".", "").Replace("-", "").ToUpper() == canonico`).
     */
    fun buscarComensal(rutInput: String): Comensal? {
        val c = cache ?: return null
        val canonico = rutInput.trim().replace(".", "").replace("-", "").uppercase()
        val comensal = c.comensales.firstOrNull {
            it.rut.replace(".", "").replace("-", "").uppercase() == canonico
        } ?: return null
        val empresaRazon = c.empresas.firstOrNull { it.id == comensal.empresaRestauranteId }?.razonSocial
            ?: comensal.empresaRestauranteId
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(comensalesHabilitados = c.serviciosHabilitados, servicios = c.servicios),
        )
    }

    /** Lista de servicios para un comensal, dados sus servicios habilitados y el catalog. */
    private fun ComensalCatalogItemDto.servicios(
        comensalesHabilitados: List<ServicioHabilitadoCatalogItemDto>,
        servicios: List<ServicioCatalogItemDto>
    ): List<Servicio> {
        val sh = comensalesHabilitados.filter { it.empresaRestauranteId == empresaRestauranteId }
        val sids = sh.map { it.servicioId }.toSet()
        return servicios.filter { sids.contains(it.id) }.map { s ->
            val precio = sh.firstOrNull { it.servicioId == s.id }?.precioClp ?: 0
            Servicio(id = s.id, nombre = s.nombre, tipo = s.tipo, precio = precio)
        }
    }

    /** Lista todos los servicios del casino (para el dropdown de seleccion en el POS). */
    fun listarServicios(): List<Servicio> {
        val c = cache ?: return emptyList()
        return c.servicios.map { Servicio(it.id, it.nombre, it.tipo, 0) }
    }

    fun getCached(): PosCatalogResponseDto? = cache

    fun getLastSync(): Long = lastSync

    fun clear() {
        cache = null
        lastSync = 0
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
