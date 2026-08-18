package cl.csae.pos.util

import java.text.NumberFormat
import java.util.Locale

/**
 * **F60 (2026-08-18):** helpers de formato monetario.
 *
 * El POS opera en CLP (peso chileno) y la convencion local es usar puntos
 * como separador de miles, sin decimales. Kotlin no trae un NumberFormat
 * para CLP out-of-the-box, asi que armamos uno reusable.
 */
object FormatUtil {
    private val clp: NumberFormat = NumberFormat.getInstance(Locale("es", "CL")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }

    /**
     * Formatea un monto en CLP. Acepta Int o Long.
     * Ej: `formatClp(1234)` -> "1.234"
     *     `formatClp(0)`    -> "0"
     */
    fun formatClp(monto: Int): String = clp.format(monto.toLong())
    fun formatClp(monto: Long): String = clp.format(monto)
}
