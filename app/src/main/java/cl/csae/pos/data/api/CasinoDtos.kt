package cl.csae.pos.data.api

import kotlinx.serialization.Serializable

/**
 * **Sprint F16 (2026-08-11):** DTO con la info de marca del casino actual.
 *
 * El backend devuelve esto desde `GET /api/v1/casino` (mismo endpoint que
 * usa la web). En la app mobile lo persistimos en `AuthStore` al hacer
 * login y lo leemos via `AuthRepository.currentCasinoTheme` (Flow) para
 * aplicar el `MaterialTheme` dinamico y el logo en cascada.
 *
 * Los colores son siempre formato `#RRGGBB` (6 chars, sin alpha) despues
 * del F14 fix. Si el casino no personaliza, el backend devuelve los
 * defaults `#1976d2` (primary) y `#ff9800` (accent) — esos llegan aca
 * directamente y la UI los aplica sin logica adicional.
 *
 * Si `logoUrl` es null, la UI usa el logo del producto (CSAE) como
 * fallback. Si `colorPrimario`/`colorAcento` es null, la UI usa los
 * colores default del `CsaePosTheme` (verde + naranja actuales).
 */
@Serializable
data class CasinoThemeDto(
    val id: String,
    val razonSocial: String,
    val rut: String? = null,
    val colorPrimario: String? = null,
    val colorAcento: String? = null,
    val logoUrl: String? = null,
)
