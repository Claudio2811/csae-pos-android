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

    // F18.2: mapa en memoria de que servicios ya consumio cada comensal HOY.
    // Key = membresiaId, value = set de servicioId. Se popula via
    // marcarConsumido() despues de cada consumo OK. Se limpia en clear() (logout).
    //
    // Trade-off: el cache vive en memoria, asi que si el operador cierra la
    // app y la reabre, este mapa se pierde y yaConsumido vuelve a false. El
    // backend igual valida la regla unicoPorDia con 409 como red de seguridad,
    // asi que no es bug — solo pierde el feedback visual de "ya consumido"
    // entre reinicios. Si en el futuro queremos que sobreviva, lo movemos
    // a DataStore.
    @Volatile private var consumidosHoy: Map<String, Set<String>> = emptyMap()

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
        val consumidos = consumidosHoy[comensal.membresiaId].orEmpty()
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(
                comensalesHabilitados = c.serviciosHabilitados,
                servicios = c.servicios,
                consumidos = consumidos,
            ),
            serviciosConsumidosHoy = consumidos,
        )
    }

    /** Busca un comensal por membresiaId. */
    fun buscarComensalPorMembresia(membresiaId: String): Comensal? {
        val c = cache ?: return null
        val comensal = c.comensales.firstOrNull { it.membresiaId == membresiaId } ?: return null
        val empresaRazon = c.empresas.firstOrNull { it.id == comensal.empresaRestauranteId }?.razonSocial
            ?: comensal.empresaRestauranteId
        val consumidos = consumidosHoy[membresiaId].orEmpty()
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(
                comensalesHabilitados = c.serviciosHabilitados,
                servicios = c.servicios,
                consumidos = consumidos,
            ),
            serviciosConsumidosHoy = consumidos,
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
        val consumidos = consumidosHoy[comensal.membresiaId].orEmpty()
        return Comensal(
            id = comensal.comensalId,
            membresiaId = comensal.membresiaId,
            rut = comensal.rut.replace(".", "").replace("-", "").uppercase(),
            nombre = comensal.nombre,
            apellido = comensal.apellido,
            empresa = empresaRazon,
            servicios = comensal.servicios(
                comensalesHabilitados = c.serviciosHabilitados,
                servicios = c.servicios,
                consumidos = consumidos,
            ),
            serviciosConsumidosHoy = consumidos,
        )
    }

    /**
     * F18.2: marca un servicio como consumido hoy para una membresia. Llamado
     * desde ConsumoRepository.registrar() despues de un 201 OK. Thread-safe
     * via Mutex (puede ser llamado desde varios coroutines en paralelo).
     */
    suspend fun marcarConsumido(membresiaId: String, servicioId: String) {
        mutex.withLock {
            val current = consumidosHoy.toMutableMap()
            val set = current[membresiaId].orEmpty().toMutableSet()
            set.add(servicioId)
            current[membresiaId] = set
            consumidosHoy = current
        }
    }

    /**
     * F21 (2026-08-13): consulta el endpoint `servicios-disponibles` del
     * backend, que SIEMPRE devuelve el estado actual de la BD (no un
     * cache local). Pensado para llamarse al seleccionar un comensal en
     * POS/Totem y despues de cada ticket, asi el mobile SIEMPRE ve el
     * flag `yaConsumido` correcto.
     *
     * Si la API falla (sin red, timeout), retorna el `Comensal` del
     * cache local como fallback (con el flag `yaConsumido` del cache
     * local, que puede estar desincronizado). El backend igual valida
     * la regla unicoPorDia con 409, asi que no es un bug, solo pierde
     * feedback visual entre reinicios / sin red.
     *
     * @param rutInput RUT del comensal (con o sin formato, se normaliza).
     * @param fecha formato yyyy-MM-dd. Si es null, el backend usa
     *              DateTime.Today del server.
     */
    suspend fun buscarComensalServiciosFrescos(
        rutInput: String,
        fecha: String? = null,
    ): Result<Comensal> {
        val rutCanonico = rutInput.trim().replace(".", "").replace("-", "").uppercase()
        return try {
            val resp = api.serviciosDisponibles(rutCanonico, fecha)
            if (!resp.isSuccessful) {
                val msg = parseError(resp.errorBody()?.string(), resp.code())
                return Result.failure(IllegalStateException(msg))
            }
            val data = resp.body() ?: return Result.failure(
                IllegalStateException("Servicios disponibles vacio.")
            )
            val servicios = data.servicios.map { s ->
                Servicio(
                    id = s.id,
                    nombre = s.nombre,
                    tipo = s.tipo,
                    precio = s.precioClp,
                    yaConsumido = s.yaConsumido,
                )
            }
            val consumidosSet = data.servicios
                .filter { it.yaConsumido }
                .map { it.id }
                .toSet()
            val c = Comensal(
                id = data.comensalId,
                membresiaId = data.membresiaId,
                rut = data.rut,
                nombre = data.nombreCompleto.substringBefore(" "),
                apellido = data.nombreCompleto.substringAfter(" ", "").ifEmpty { null },
                empresa = data.empresaRazonSocial,
                servicios = servicios,
                serviciosConsumidosHoy = consumidosSet,
            )
            // F21: sincronizar el cache local para que la UI siga
            // funcionando offline despues de esta llamada. Si falla la
            // red, el cache local sigue con los valores que tenia.
            mutex.withLock {
                val current = consumidosHoy.toMutableMap()
                current[c.membresiaId] = consumidosSet
                consumidosHoy = current
            }
            Result.success(c)
        } catch (t: Throwable) {
            // Fallback: usar el cache local.
            val fallback = buscarComensal(rutInput)
            if (fallback != null) Result.success(fallback)
            else Result.failure(t)
        }
    }

    /**
     * F18.2: lista de servicios para un comensal, dados sus servicios habilitados
     * y el catalog. Tambien recibe el set de servicios YA consumidos hoy por
     * este comensal (sacado de `consumidosHoy` por membresiaId) y setea el
     * flag `yaConsumido` en cada Servicio. Asi la UI puede deshabilitar el
     * boton sin pegarle al backend.
     */
    private fun ComensalCatalogItemDto.servicios(
        comensalesHabilitados: List<ServicioHabilitadoCatalogItemDto>,
        servicios: List<ServicioCatalogItemDto>,
        consumidos: Set<String>,
    ): List<Servicio> {
        val sh = comensalesHabilitados.filter { it.empresaRestauranteId == empresaRestauranteId }
        val sids = sh.map { it.servicioId }.toSet()
        return servicios.filter { sids.contains(it.id) }.map { s ->
            val precio = sh.firstOrNull { it.servicioId == s.id }?.precioClp ?: 0
            Servicio(
                id = s.id,
                nombre = s.nombre,
                tipo = s.tipo,
                precio = precio,
                yaConsumido = s.id in consumidos,
            )
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
        // F18.2: tambien limpiar el mapa de consumidos hoy. Se llama en
        // logout (ServiceLocator.resetSession) para que un nuevo login
        // empiece con el cache fresco.
        consumidosHoy = emptyMap()
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
