package cl.csae.pos.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import cl.csae.pos.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builder del cliente HTTP. Aceptamos el JWT como parametro porque
 * viene del AuthStore (DataStore) y queremos poder cambiarlo sin recrear el cliente.
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true   // el API puede agregar campos sin romper la app
        coerceInputValues = true    // si llega un null, lo tratamos como default
        isLenient = true
        explicitNulls = false
    }

    fun build(baseUrl: String = BuildConfig.API_BASE_URL, jwtProvider: () -> String?): PosApiService {
        val authInterceptor = Interceptor { chain ->
            val token = jwtProvider()
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addConverterFactory(retrofit2.converter.scalars.ScalarsConverterFactory.create())
            .build()

        return retrofit.create(PosApiService::class.java)
    }
}
