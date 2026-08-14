package cl.csae.pos.util

/**
 * **Sprint F26 (2026-08-14):** Helper para RUT chileno en el cliente (Android).
 *
 * Replica la logica del value object `CSAE.Domain.ValueObjects.Rut` del
 * backend y del `RutHelper.cs` de la web. Esto evita acoplar el cliente
 * a la DLL del backend y permite validar/formatear RUTs sin hacer una
 * llamada HTTP.
 *
 * Funciones:
 * - isValidFormat: valida que el RUT tenga 1-8 digitos + DV (0-9 o K).
 * - isValid: valida formato + DV real con el algoritmo modulo-11.
 * - calcularDigitoVerificador: calcula el DV esperado.
 * - format: aplica la mascara "12.345.678-9" (con puntos).
 * - normalize: limpia el input (sin puntos, sin guion, uppercase).
 * - errorMessage: devuelve un mensaje legible si el RUT es invalido.
 */
object RutHelper {

    // Regex de formato flexible: acepta con/sin puntos, con/sin guion, K o digito.
    private val INPUT_REGEX = Regex(
        """^\s*([0-9]{1,3}(?:\.[0-9]{3}){0,3}|[0-9]{1,8})\s*-?\s*([0-9Kk])?\s*$"""
    )

    /**
     * Valida que el RUT tenga el formato correcto: 1-8 digitos + DV (0-9 o K).
     * NO valida que el DV sea el correcto (para eso usar [isValid]).
     */
    fun isValidFormat(rut: String?): Boolean {
        if (rut.isNullOrBlank()) return false
        return INPUT_REGEX.matches(rut.trim())
    }

    /**
     * Valida que el RUT tenga formato correcto Y que el DV coincida
     * con el calculado por el algoritmo modulo-11.
     */
    fun isValid(rut: String?): Boolean {
        if (!isValidFormat(rut)) return false
        val match = INPUT_REGEX.matchEntire(rut!!.trim()) ?: return false
        val numero = match.groupValues[1].replace(".", "")
        val dvIngresado = match.groupValues[2]
        if (dvIngresado.isEmpty()) return false
        val dvCalculado = calcularDigitoVerificador(numero)
        return dvIngresado.uppercase().first() == dvCalculado
    }

    /**
     * Calcula el digito verificador de un numero de RUT (sin DV).
     * Algoritmo estandar: invertir el numero, multiplicar por la serie
     * 2,3,4,5,6,7 (ciclica), sumar, calcular 11 - (suma mod 11).
     * 10 -> 'K', 11 -> '0', otro -> el digito.
     *
     * @throws IllegalArgumentException si el numero esta vacio o tiene chars no numericos.
     */
    fun calcularDigitoVerificador(numero: String): Char {
        require(numero.isNotBlank()) { "Numero no puede estar vacio." }
        var suma = 0
        var multiplicador = 2
        for (i in numero.length - 1 downTo 0) {
            val c = numero[i]
            require(c.isDigit()) { "Caracter no numerico en posicion $i." }
            suma += (c.digitToInt()) * multiplicador
            multiplicador = if (multiplicador == 7) 2 else multiplicador + 1
        }
        val resto = suma % 11
        val dv = 11 - resto
        return when (dv) {
            10 -> 'K'
            11 -> '0'
            else -> ('0'.digitToInt() + dv).digitToChar()
        }
    }

    /**
     * Aplica la mascara "12.345.678-9" (con puntos) a un RUT "limpio"
     * (sin puntos ni guion). Si el ultimo char es K, lo respeta.
     * Si el RUT esta vacio, devuelve vacio.
     */
    fun format(clean: String): String {
        if (clean.isEmpty()) return ""
        val isLastCharDV = clean.last() == 'K' || clean.length >= 9
        if (!isLastCharDV) {
            // Aun no llega al DV: solo puntos, sin guion.
            val sb = StringBuilder()
            var count = 0
            for (i in clean.indices.reversed()) {
                sb.insert(0, clean[i])
                count++
                if (count == 3 && i != 0) {
                    sb.insert(0, '.')
                    count = 0
                }
            }
            return sb.toString()
        }
        // El ultimo char es DV (K o >=9 chars total): separarlo con guion.
        val body = clean.dropLast(1)
        val dv = clean.last().toString()
        val sb = StringBuilder()
        var c = 0
        for (i in body.indices.reversed()) {
            sb.insert(0, body[i])
            c++
            if (c == 3 && i != 0) {
                sb.insert(0, '.')
                c = 0
            }
        }
        return "$sb-$dv"
    }

    /**
     * Normaliza el input: saca puntos, guiones, espacios, mayusculas la K.
     * Devuelve el RUT "limpio" listo para pasar a [format].
     * Si el RUT es invalido (formato), devuelve el input limpio sin
     * tocar (no lanza excepcion para que el usuario pueda seguir tipeando).
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        var clean = raw.replace(".", "").replace("-", "").replace(" ", "").uppercase()
        val idxK = clean.indexOf('K')
        if (idxK >= 0) {
            clean = clean.substring(0, idxK + 1) + clean.substring(idxK + 1).replace("K", "")
        }
        return clean
    }

    /**
     * Devuelve un mensaje de error legible para mostrar al usuario cuando
     * el RUT es invalido. Null si el RUT es valido o vacio.
     */
    fun errorMessage(rut: String?): String? {
        if (rut.isNullOrBlank()) return null
        if (!isValidFormat(rut)) {
            return "Formato de RUT invalido. Usa 12345678-9 o 12.345.678-9."
        }
        if (!isValid(rut)) {
            val numero = normalize(rut)
            if (numero.last() == 'K' || numero.length >= 9) {
                val dvCorrecto = calcularDigitoVerificador(numero.dropLast(1))
                return "Digito verificador (DV) incorrecto. El DV correcto es '$dvCorrecto'."
            }
            return "RUT incompleto. Ingresa el digito verificador (0-9 o K)."
        }
        return null
    }
}
