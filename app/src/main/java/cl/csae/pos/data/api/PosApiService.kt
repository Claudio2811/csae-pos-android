package cl.csae.pos.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoints del POS contra la API real de CSAE.
 * La autorizacion (JWT) la agrega un interceptor de OkHttp, no se envia en cada llamada.
 */
interface PosApiService {

    // ============= AUTH =============

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponseDto>

    /**
     * F3: perfil completo del usuario autenticado, incluyendo la lista de
     * sucursales del casino y la lista de casinos (para AdminEmpresa).
     *
     * Lo consume el [SucursalSelectScreen] post-login (auto-skip si <=1) y
     * desde el menu de Configuracion para cambiar manualmente.
     */
    @GET("api/v1/auth/me")
    suspend fun me(): Response<MeResponseDto>

    /**
     * F3: cambia la sucursal activa del user. Emite un nuevo JWT con el
     * claim `sucursal_id` actualizado. El cliente debe reemplazar el token
     * cacheado por el retornado y re-bajar el catalog.
     */
    @POST("api/v1/auth/cambiar-sucursal")
    suspend fun cambiarSucursal(
        @Body body: CambiarSucursalRequestDto,
    ): Response<CambiarSucursalResponseDto>

    /**
     * **F56 (2026-08-17):** refresca el JWT sin pedir password. Acepta un
     * token (incluso expirado, siempre que la firma sea valida) y emite
     * uno nuevo con la misma identidad + claims.
     *
     * Es el endpoint que consume el [TokenAuthenticator] cuando OkHttp
     * recibe un 401: llama aca, reemplaza el JWT cacheado, y reintenta
     * el request original con el token nuevo.
     *
     * Tambien lo consume el refresh proactivo del [cl.csae.pos.data.repository.AuthRepository]:
     * si el JWT esta a <5 min de expirar, pega aca para renovarlo antes
     * de que el operador meta un 401 accidental.
     *
     * Backend: `CSAE.Api` branch `main` commit `fa593ce`.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): Response<RefreshTokenResponse>

    // ============= CASINO TEMA (sprint F16) =============

    /**
     * Devuelve la info del casino actual (incluye colorPrimario,
     * colorAcento, logoUrl, razonSocial). Sprint F16: el operador la
     * consume al login para aplicar el tema del casino.
     */
    @GET("api/v1/casino")
    suspend fun getCasino(): Response<CasinoThemeDto>

    // ============= CATALOG =============

    @GET("api/v1/pos/catalog")
    suspend fun getCatalog(): Response<PosCatalogResponseDto>

    // ============= COMENSAL =============

    @GET("api/v1/pos/comensales/buscar")
    suspend fun buscarComensal(@Query("rut") rut: String): Response<ComensalPosServiciosResponseDto>

    /**
     * F21 (2026-08-13): endpoint dedicado para refrescar el estado de los
     * servicios disponibles del comensal. Se llama al seleccionarlo en
     * POS/Totem y despues de cada ticket, para que el mobile SIEMPRE
     * vea el estado actual de la BD (no un cache local desincronizado).
     *
     * @param rut RUT del comensal (con o sin formato).
     * @param fecha fecha a consultar en formato ISO yyyy-MM-dd. Si es
     *              null, el backend usa `DateTime.Today` del server.
     */
    @GET("api/v1/pos/comensales/{rut}/servicios-disponibles")
    suspend fun serviciosDisponibles(
        @Path("rut") rut: String,
        @Query("fecha") fecha: String? = null,
    ): Response<ComensalServiciosDisponiblesResponseDto>

    // ============= CONSUMOS =============

    @POST("api/v1/pos/consumos")
    suspend fun registrarConsumo(@Body body: RegistrarConsumoRequestDto): Response<RegistrarConsumoResponseDto>

    @POST("api/v1/pos/tickets/{id}/impreso")
    suspend fun marcarTicketImpreso(
        @Path("id") ticketId: String,
        @Body body: MarcarTicketImpresoRequestDto,
    ): Response<Unit>

    // ============= CONSUMO LISTADO (sprint 3.2) =============

    /**
     * Lista los consumos en un rango de fechas UTC. Usado por ConsumosScreen
     * para mostrar el turno actual (Sprint 3.2) o un rango historico
     * (Sprint 3.4).
     * - desdeUtc: inicio (inclusivo). Si null, el backend no filtra下限.
     * - hastaUtc: fin (exclusivo). Si null, el backend no filtra上限.
     */
    @GET("api/v1/pos/consumos")
    suspend fun listarConsumos(
        @Query("desde") desdeUtc: String? = null,
        @Query("hasta") hastaUtc: String? = null,
        @Query("pageSize") pageSize: Int = 500,
    ): Response<ConsumosListResponseDto>

    // ============= TICKET VALIDAR / CONSUMIR (sprint 3.2, modo Garzon) =============

    @POST("api/v1/pos/tickets/validar")
    suspend fun validarTicket(@Body req: ValidarTicketRequest): Response<ValidarTicketResponse>

    @POST("api/v1/pos/tickets/consumir")
    suspend fun consumirTicket(@Body req: ConsumirTicketRequest): Response<ConsumirTicketResponse>

    // ============= DISPOSITIVOS POS (F19) =============

    /**
     * F19: lista los dispositivos POS/kiosko del casino actual. Usado por:
     * - El reconciliador al login para auto-seleccionar el dispositivo del
     *   operador por AndroidId.
     * - La UI de ConfiguracionScreen (dropdown "Dispositivo POS") para
     *   que el operador elija manualmente si el reconciliador no matcheo.
     *
     * `incluirInactivos=false` (default) filtra los soft-deleted.
     */
    @GET("api/v1/dispositivos-pos")
    suspend fun listarDispositivos(
        @Query("incluirInactivos") incluirInactivos: Boolean = false,
    ): Response<List<DispositivoPosDto>>
}
