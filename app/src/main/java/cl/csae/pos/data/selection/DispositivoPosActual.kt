package cl.csae.pos.data.selection

import cl.csae.pos.data.prefs.IAuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * F19: Servicio singleton que mantiene el dispositivo POS actualmente
 * "seleccionado" en la app. Replica el patron de la web
 * (DispositivoPosActual.cs, Sprint 5.4) pero con el equivalente mobile:
 *
 * - Persistencia: DataStore (vía [AuthStore]) en vez de localStorage.
 *   DataStore ya provee un Flow reactivo, asi que NO necesitamos
 *   un LoadAsync manual como en la web (donde el localStorage es
 *   imperativo).
 * - Estado en memoria: el `StateFlow` derivado del DataStore vive
 *   en memoria mientras el proceso esta activo. Es automaticamente
 *   consistente entre todos los componentes que lo consumen.
 *
 * **Por que NO es parte del JWT (como la sucursal):**
 * El JWT del operador contiene `restaurante_id` y (a veces) `sucursal_id`,
 * pero NO el dispositivo POS especifico. El dispositivo es una eleccion
 * local del operador: "que tablet estoy usando ahora?". Persiste entre
 * sesiones del usuario (logout/login) pero se limpia al cambiar de casino
 * (porque el dispositivo esta asociado al casino).
 *
 * **Por que singleton (en ServiceLocator):**
 * Mismo motivo que la web: el dato es de sesion del proceso, no del
 * request HTTP. En Blazor Server era por el tema de los circuitos
 * SignalR; en Android, es para tener una sola fuente de verdad y
 * evitar pasar el dato por parametro entre componentes.
 */
class DispositivoPosActual(private val authStore: IAuthStore) {

    /**
     * Snapshot actual del dispositivo POS seleccionado.
     * `null` = ninguno (aun no se eligio, o se limpio).
     * Reactivo: cambia automaticamente cuando el DataStore se actualiza.
     */
    val current: StateFlow<DispositivoPos?> = combine(
        authStore.dispositivoId,
        authStore.dispositivoNombre,
        authStore.dispositivoCodigo,
        authStore.dispositivoTipo,
    ) { id, nombre, codigo, tipo ->
        if (id == null) null
        else DispositivoPos(id = id, nombre = nombre, codigo = codigo, tipo = tipo)
    }.stateIn(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    /**
     * Selecciona un dispositivo POS. Persiste en DataStore (vía AuthStore) y
     * el StateFlow `current` se actualiza reactivamente.
     *
     * Llamado desde la UI (selector de dispositivo en Configuracion) o desde
     * el reconciliador post-login que matchea el AndroidId del telefono contra
     * la lista de dispositivos del casino.
     *
     * Para limpiar la seleccion, pasar los 4 parametros en `null` (esto borra
     * los 4 campos en DataStore).
     */
    suspend fun setDispositivo(id: String?, nombre: String?, codigo: String?, tipo: Int?) {
        authStore.setDispositivo(id, nombre, codigo, tipo)
    }
}

/**
 * Snapshot inmutable del dispositivo POS actualmente seleccionado.
 * Solo los 4 campos que necesitamos persistir localmente — para la
 * lista completa (con `activo`, `kioskoToken`, `modelo`, etc) ver
 * el response de la API GET /api/v1/dispositivos-pos.
 */
data class DispositivoPos(
    val id: String,
    val nombre: String?,
    val codigo: String?,    // AndroidId del dispositivo (para mostrar en UI)
    val tipo: Int?,         // 1 = OperadorPos, 2 = Kiosko (mismo que la web)
)
