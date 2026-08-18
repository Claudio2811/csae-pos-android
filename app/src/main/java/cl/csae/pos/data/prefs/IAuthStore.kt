package cl.csae.pos.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * **F56 (2026-08-17):** interfaz delgada que define los metodos del
 * [AuthStore] que el [cl.csae.pos.data.repository.AuthRepository] usa.
 *
 * **Por que existe:**
 * El [AuthStore] concreto depende de `Context` (DataStore requiere
 * Android `Context` para crear el archivo de preferencias). Eso hace
 * que el repo no sea testeable en unit tests puros (sin Robolectric).
 *
 * Solucion: el repo depende de esta interfaz, no del concreto. La
 * implementacion real ([AuthStore]) la cumple naturalmente porque ya
 * tiene todos los metodos con la misma firma. En los tests, un
 * `FakeAuthStore` implementa la interfaz directamente sin tocar
 * Android.
 *
 * Es deliberadamente pequena: solo lo que el repo necesita. NO
 * replicamos la API completa de [AuthStore] (los flows, los getters
 * auxiliares, los setters de impresora, etc siguen siendo del
 * concreto y se acceden via `ServiceLocator.authStore` desde la UI).
 */
interface IAuthStore {

    val email: Flow<String?>
    val displayName: Flow<String?>
    val rol: Flow<String?>
    val restauranteId: Flow<String?>
    val sucursalId: Flow<String?>
    val token: Flow<String?>

    // F16: casino theme (Flows)
    val casinoId: Flow<String?>
    val casinoNombre: Flow<String?>
    val casinoRut: Flow<String?>
    val casinoColorPrimario: Flow<String?>
    val casinoColorAcento: Flow<String?>
    val casinoLogoUrl: Flow<String?>

    // F19: Dispositivo POS (Flows)
    val dispositivoId: Flow<String?>
    val dispositivoNombre: Flow<String?>
    val dispositivoCodigo: Flow<String?>
    val dispositivoTipo: Flow<Int?>

    suspend fun getToken(): String?

    suspend fun getTokenExpiresAt(): Long?

    suspend fun save(
        token: String,
        email: String,
        displayName: String,
        rol: String,
        restauranteId: String?,
        sucursalId: String? = null,
        expiresAt: Long? = null,
    )

    suspend fun setSucursal(sucursalId: String?)

    suspend fun saveCasinoTheme(
        casinoId: String,
        casinoNombre: String,
        casinoRut: String?,
        colorPrimario: String?,
        colorAcento: String?,
        logoUrl: String?,
    )

    /**
     * Limpia el DataStore por completo. Usado por [AuthRepository.emitSessionExpired]
     * y por `logout` (via [clearSesion]).
     */
    suspend fun clear()

    suspend fun clearSesion()

    suspend fun saveTokenOnly(token: String, expiresAt: Long)

    /**
     * F19: persistir el dispositivo POS seleccionado por el operador.
     * Lo usa [DispositivoPosActual] cuando el operador elige un
     * dispositivo desde la UI. No lo usa directamente el
     * [AuthRepository], pero como [DispositivoPosActual] lo invoca en
     * su constructor / setDispositivo, lo necesitamos en la interfaz
     * para poder inyectar un fake en los tests.
     */
    suspend fun setDispositivo(
        id: String?,
        nombre: String?,
        codigo: String?,
        tipo: Int?,
    )
}
