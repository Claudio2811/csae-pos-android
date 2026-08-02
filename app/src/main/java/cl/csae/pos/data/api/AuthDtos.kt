package cl.csae.pos.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= AUTH =============

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponseDto(
    val token: String,
    val expiresAt: String,
    val email: String,
    val rol: String,
    val restauranteId: String? = null,
    val empresaId: String? = null,
)

@Serializable
data class ApiError(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null,
)
