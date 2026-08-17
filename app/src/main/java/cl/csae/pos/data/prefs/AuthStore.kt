package cl.csae.pos.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistencia minima del JWT y datos del usuario actual.
 *
 * Por que DataStore y no SharedPreferences:
 * - SharedPreferences hace I/O en el main thread.
 * - DataStore usa coroutines y Flow, que ya tenemos en el proyecto.
 *
 * El "DataStore" se delega a una extension property de Context, lo que crea
 * una sola instancia por proceso.
 *
 * Sprint 3.2: se agrega [modoPreferido] para que el dispositivo recuerde el
 * modo (TOTEM / POS / GARZON) seleccionado al abrir la app. Si esta set,
 * el AppNavHost salta el selector de modo y va directo a la pantalla.
 *
 * Sprint 3.3: se agrega [lastSeenVersionCode] para forzar logout automatico
 * cuando cambia el versionCode de la app. Asi, al actualizar la APK, el
 * usuario SIEMPRE ve el login primero aunque tenga un JWT valido en
 * DataStore. Esto resuelve el bug "la app va directo al modo preferido sin
 * pedir login tras una actualizacion".
 *
 * **Sprint F16 (2026-08-11):** se agregan campos de marca del casino
 * (colorPrimario, colorAcento, logoUrl, casinoId, casinoNombre, casinoRut)
 * para que la app aplique el tema del casino actual (F16) sin pegarle al
 * backend en cada render. Los valores se persisten al hacer login (via
 * AuthRepository.saveCasinoTheme) y se limpian al logout.
 */
private val Context.authDataStore by preferencesDataStore(name = "csae_pos_auth")

class AuthStore(private val context: Context) {

    val token: Flow<String?> = context.authDataStore.data.map { it[KEY_TOKEN] }
    val email: Flow<String?> = context.authDataStore.data.map { it[KEY_EMAIL] }
    val displayName: Flow<String?> = context.authDataStore.data.map { it[KEY_NAME] }
    val rol: Flow<String?> = context.authDataStore.data.map { it[KEY_ROL] }
    val restauranteId: Flow<String?> = context.authDataStore.data.map { it[KEY_RESTAURANTE] }
    /**
     * F3: sucursal activa del OperadorPos. Persiste entre sesiones (igual
     * que restauranteId). null = "casino completo" (cuando el casino no
     * tiene sucursales o el user eligio "ver todo el casino").
     */
    val sucursalId: Flow<String?> = context.authDataStore.data.map { it[KEY_SUCURSAL] }
    val modoPreferido: Flow<String?> = context.authDataStore.data.map { it[KEY_MODO_PREFERIDO] }

    /**
     * MAC address de la impresora Bluetooth preferida (sprint 3.2). Opcional.
     */
    val impresoraMac: Flow<String?> = context.authDataStore.data.map { it[KEY_IMPRESORA_MAC] }
    val impresoraNombre: Flow<String?> = context.authDataStore.data.map { it[KEY_IMPRESORA_NOMBRE] }
    /**
     * **F55 (2026-08-17):** Si esta en true, el TicketScreen auto-imprime
     * el ticket al mostrarse usando la MAC persistida en [impresoraMac].
     * Si no hay MAC guardada o la impresora no responde, no rompe: el
     * operador puede seguir apretando "Imprimir" manualmente.
     * Default: false (comportamiento manual original).
     */
    val autoImprimirTickets: Flow<Boolean> = context.authDataStore.data.map { it[KEY_AUTO_IMPRIMIR] ?: false }

    // ===== F16: Tema del casino actual =====
    val casinoId: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_ID] }
    val casinoNombre: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_NOMBRE] }
    val casinoRut: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_RUT] }
    val casinoColorPrimario: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_COLOR_PRIMARIO] }
    val casinoColorAcento: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_COLOR_ACENTO] }
    val casinoLogoUrl: Flow<String?> = context.authDataStore.data.map { it[KEY_CASINO_LOGO_URL] }

    // ===== F19: Dispositivo POS seleccionado =====
    // A diferencia del casino y la sucursal (que viven en el JWT), el dispositivo
    // POS es una eleccion LOCAL del operador: que dispositivo fisico esta usando.
    // Se persiste en DataStore (equivalente al localStorage de la web) y vive
    // en memoria via DispositivoPosActual (singleton en ServiceLocator).
    // El patron es el mismo que la web: DispositivoPosActual.cs (Sprint 5.4).
    val dispositivoId: Flow<String?> = context.authDataStore.data.map { it[KEY_DISPOSITIVO_ID] }
    val dispositivoNombre: Flow<String?> = context.authDataStore.data.map { it[KEY_DISPOSITIVO_NOMBRE] }
    val dispositivoCodigo: Flow<String?> = context.authDataStore.data.map { it[KEY_DISPOSITIVO_CODIGO] }
    val dispositivoTipo: Flow<Int?> = context.authDataStore.data.map { it[KEY_DISPOSITIVO_TIPO] }

    suspend fun getToken(): String? = context.authDataStore.data.first()[KEY_TOKEN]

    suspend fun getModoPreferido(): String? = context.authDataStore.data.first()[KEY_MODO_PREFERIDO]

    suspend fun getImpresoraMac(): String? = context.authDataStore.data.first()[KEY_IMPRESORA_MAC]

    /**
     * **F55 (2026-08-17):** lee el flag de auto-imprimir. Snapshot actual
     * (no reactivo). Llamado desde el LaunchedEffect del TicketScreen.
     */
    suspend fun getAutoImprimirTickets(): Boolean = context.authDataStore.data.first()[KEY_AUTO_IMPRIMIR] ?: false

    /**
     * Devuelve el versionCode de la app que vio la sesion actual. 0 si es la
     * primera vez que se abre (instalacion limpia). Usado por
     * [CsaePosApplication] para forzar logout cuando cambia el versionCode.
     */
    suspend fun getLastSeenVersionCode(): Int = context.authDataStore.data.first()[KEY_VERSION_CODE] ?: 0

    suspend fun save(
        token: String,
        email: String,
        displayName: String,
        rol: String,
        restauranteId: String?,
        sucursalId: String? = null,
    ) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_EMAIL] = email
            prefs[KEY_NAME] = displayName
            prefs[KEY_ROL] = rol
            if (restauranteId != null) prefs[KEY_RESTAURANTE] = restauranteId
            // F3: si el login devuelve sucursalId (sucursal default del user),
            // la persistimos. Si es null, NO borramos la anterior — eso lo
            // hace `setSucursal(null)` cuando el user elige "casino completo".
            if (sucursalId != null) prefs[KEY_SUCURSAL] = sucursalId
        }
    }

    /**
     * F3: cambia la sucursal activa. Llamado desde [AuthRepository.cambiarSucursal]
     * despues de un POST /api/v1/auth/cambiar-sucursal exitoso. Tambien desde
     * el SucursalSelectScreen si el user quiere volver a "casino completo"
     * (pasando null).
     */
    suspend fun setSucursal(sucursalId: String?) {
        context.authDataStore.edit { prefs ->
            if (sucursalId == null) prefs.remove(KEY_SUCURSAL)
            else prefs[KEY_SUCURSAL] = sucursalId
        }
    }

    /**
     * **Sprint F16 (2026-08-11):** persiste los datos de marca del casino
     * (id, nombre, RUT, colorPrimario, colorAcento, logoUrl) leidos del
     * endpoint `GET /api/v1/casino` despues del login. La UI los lee via
     * [casinoId] / [casinoColorPrimario] / etc para aplicar el theme
     * dinamico y mostrar el logo.
     *
     * El backend siempre devuelve colores en formato `#RRGGBB` (6 chars, sin
     * alpha) segun F14 fix. Si el casino no tiene logo, [casinoLogoUrl]
     * queda en null y la UI usa el logo del producto (CSAE) como fallback.
     */
    suspend fun saveCasinoTheme(
        casinoId: String,
        casinoNombre: String,
        casinoRut: String?,
        colorPrimario: String?,
        colorAcento: String?,
        logoUrl: String?,
    ) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_CASINO_ID] = casinoId
            prefs[KEY_CASINO_NOMBRE] = casinoNombre
            if (casinoRut != null) prefs[KEY_CASINO_RUT] = casinoRut
            // Guardamos el color aunque sea null (no se aplicaria el override
            // y la UI usa el default). En la practica el backend siempre
            // devuelve un color (default #1976d2 / #ff9800).
            if (colorPrimario != null) prefs[KEY_CASINO_COLOR_PRIMARIO] = colorPrimario
            if (colorAcento != null) prefs[KEY_CASINO_COLOR_ACENTO] = colorAcento
            if (logoUrl != null) prefs[KEY_CASINO_LOGO_URL] = logoUrl
        }
    }

    suspend fun setModoPreferido(modo: String?) {
        context.authDataStore.edit { prefs ->
            if (modo == null) prefs.remove(KEY_MODO_PREFERIDO)
            else prefs[KEY_MODO_PREFERIDO] = modo
        }
    }

    suspend fun setImpresora(mac: String?, nombre: String?) {
        context.authDataStore.edit { prefs ->
            if (mac == null) {
                prefs.remove(KEY_IMPRESORA_MAC)
                prefs.remove(KEY_IMPRESORA_NOMBRE)
            } else {
                prefs[KEY_IMPRESORA_MAC] = mac
                if (nombre != null) prefs[KEY_IMPRESORA_NOMBRE] = nombre
            }
        }
    }

    /**
     * **F55 (2026-08-17):** persiste el flag de auto-imprimir. Llamado desde
     * el Switch en ConfiguracionScreen. Default false (manual).
     */
    suspend fun setAutoImprimirTickets(enabled: Boolean) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_AUTO_IMPRIMIR] = enabled
        }
    }

    /**
     * Sprint 3.3: persistir el versionCode actual para que la proxima vez
     * que arranque la app sepamos que ya "vimos" esta version.
     */
    suspend fun setLastSeenVersionCode(code: Int) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_VERSION_CODE] = code
        }
    }

    /**
     * Sprint 3.3: alias semantico de [clear] usado cuando se fuerza logout
     * al cambiar de version. Mantiene el nombre generico por si en el futuro
     * queremos un clearSesion() que NO borre preferencias de UI (tema,
     * idioma, etc).
     */
    suspend fun clearSesion() {
        clear()
    }

    /**
     * F19: persiste el dispositivo POS seleccionado. Llamado desde
     * [cl.csae.pos.data.selection.DispositivoPosActual.setDispositivo] cuando
     * el operador elige un dispositivo (vía UI) o cuando se reconcilia con la
     * lista del casino al login.
     *
     * Si los parametros son null, se borran los 4 campos.
     */
    suspend fun setDispositivo(
        id: String?,
        nombre: String?,
        codigo: String?,
        tipo: Int?,
    ) {
        context.authDataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(KEY_DISPOSITIVO_ID)
                prefs.remove(KEY_DISPOSITIVO_NOMBRE)
                prefs.remove(KEY_DISPOSITIVO_CODIGO)
                prefs.remove(KEY_DISPOSITIVO_TIPO)
            } else {
                prefs[KEY_DISPOSITIVO_ID] = id
                if (nombre != null) prefs[KEY_DISPOSITIVO_NOMBRE] = nombre
                if (codigo != null) prefs[KEY_DISPOSITIVO_CODIGO] = codigo
                if (tipo != null) prefs[KEY_DISPOSITIVO_TIPO] = tipo
            }
        }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("jwt")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_ROL = stringPreferencesKey("rol")
        val KEY_RESTAURANTE = stringPreferencesKey("restaurante_id")
        val KEY_SUCURSAL = stringPreferencesKey("sucursal_id")
        val KEY_MODO_PREFERIDO = stringPreferencesKey("modo_preferido")
        val KEY_IMPRESORA_MAC = stringPreferencesKey("impresora_mac")
        val KEY_IMPRESORA_NOMBRE = stringPreferencesKey("impresora_nombre")
        // F55: toggle de auto-impresion del ticket.
        val KEY_AUTO_IMPRIMIR = booleanPreferencesKey("auto_imprimir_tickets")
        val KEY_VERSION_CODE = intPreferencesKey("last_seen_version_code")
        // F16
        val KEY_CASINO_ID = stringPreferencesKey("casino_id")
        val KEY_CASINO_NOMBRE = stringPreferencesKey("casino_nombre")
        val KEY_CASINO_RUT = stringPreferencesKey("casino_rut")
        val KEY_CASINO_COLOR_PRIMARIO = stringPreferencesKey("casino_color_primario")
        val KEY_CASINO_COLOR_ACENTO = stringPreferencesKey("casino_color_acent")
        val KEY_CASINO_LOGO_URL = stringPreferencesKey("casino_logo_url")
        // F19: Dispositivo POS (seleccion local, no en JWT)
        val KEY_DISPOSITIVO_ID = stringPreferencesKey("dispositivo_id")
        val KEY_DISPOSITIVO_NOMBRE = stringPreferencesKey("dispositivo_nombre")
        val KEY_DISPOSITIVO_CODIGO = stringPreferencesKey("dispositivo_codigo")
        val KEY_DISPOSITIVO_TIPO = intPreferencesKey("dispositivo_tipo")
    }
}
