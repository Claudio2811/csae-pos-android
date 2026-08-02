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

    // ============= CATALOG =============

    @GET("api/v1/pos/catalog")
    suspend fun getCatalog(): Response<PosCatalogResponseDto>

    // ============= COMENSAL =============

    @GET("api/v1/pos/comensales/buscar")
    suspend fun buscarComensal(@Query("rut") rut: String): Response<ComensalPosServiciosResponseDto>

    // ============= CONSUMOS =============

    @POST("api/v1/pos/consumos")
    suspend fun registrarConsumo(@Body body: RegistrarConsumoRequestDto): Response<RegistrarConsumoResponseDto>

    @POST("api/v1/pos/tickets/{id}/impreso")
    suspend fun marcarTicketImpreso(
        @Path("id") ticketId: String,
        @Body body: MarcarTicketImpresoRequestDto,
    ): Response<Unit>
}
