package cl.csae.pos.data.prefs

import android.content.Context
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
 */
private val Context.authDataStore by preferencesDataStore(name = "csae_pos_auth")

class AuthStore(private val context: Context) {

    val token: Flow<String?> = context.authDataStore.data.map { it[KEY_TOKEN] }
    val email: Flow<String?> = context.authDataStore.data.map { it[KEY_EMAIL] }
    val displayName: Flow<String?> = context.authDataStore.data.map { it[KEY_NAME] }
    val rol: Flow<String?> = context.authDataStore.data.map { it[KEY_ROL] }
    val restauranteId: Flow<String?> = context.authDataStore.data.map { it[KEY_RESTAURANTE] }
    val modoPreferido: Flow<String?> = context.authDataStore.data.map { it[KEY_MODO_PREFERIDO] }

    /**
     * MAC address de la impresora Bluetooth preferida (sprint 3.2). Opcional.
     */
    val impresoraMac: Flow<String?> = context.authDataStore.data.map { it[KEY_IMPRESORA_MAC] }
    val impresoraNombre: Flow<String?> = context.authDataStore.data.map { it[KEY_IMPRESORA_NOMBRE] }

    suspend fun getToken(): String? = context.authDataStore.data.first()[KEY_TOKEN]

    suspend fun getModoPreferido(): String? = context.authDataStore.data.first()[KEY_MODO_PREFERIDO]

    suspend fun getImpresoraMac(): String? = context.authDataStore.data.first()[KEY_IMPRESORA_MAC]

    /**
     * Devuelve el versionCode de la app que vio la sesion actual. 0 si es la
     * primera vez que se abre (instalacion limpia). Usado por
     * [CsaePosApplication] para forzar logout cuando cambia el versionCode.
     */
    suspend fun getLastSeenVersionCode(): Int = context.authDataStore.data.first()[KEY_VERSION_CODE] ?: 0

    suspend fun save(token: String, email: String, displayName: String, rol: String, restauranteId: String?) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_EMAIL] = email
            prefs[KEY_NAME] = displayName
            prefs[KEY_ROL] = rol
            if (restauranteId != null) prefs[KEY_RESTAURANTE] = restauranteId
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

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("jwt")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_ROL = stringPreferencesKey("rol")
        val KEY_RESTAURANTE = stringPreferencesKey("restaurante_id")
        val KEY_MODO_PREFERIDO = stringPreferencesKey("modo_preferido")
        val KEY_IMPRESORA_MAC = stringPreferencesKey("impresora_mac")
        val KEY_IMPRESORA_NOMBRE = stringPreferencesKey("impresora_nombre")
        val KEY_VERSION_CODE = intPreferencesKey("last_seen_version_code")
    }
}
