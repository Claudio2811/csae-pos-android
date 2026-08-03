package cl.csae.pos

import android.app.Application
import cl.csae.pos.di.ServiceLocator
import kotlinx.coroutines.runBlocking

/**
 * Application class del POS. Inicializa el [ServiceLocator] una sola vez al
 * arrancar el proceso. Toda la app obtiene sus dependencias desde aca.
 *
 * Por que un ServiceLocator y no Hilt/Koin:
 * - La app es chica (4 pantallas, 3 repos). Hilt suma KSP/kapt y tiempo de build.
 * - Para Salamanca no necesitamos scope de ViewModel ni grafos complejos.
 * - Si despues crece, migrar a Hilt es directo (mismas interfaces).
 *
 * Sprint 3.3: ademas de inicializar el ServiceLocator, este onCreate compara
 * el [BuildConfig.VERSION_CODE] actual contra el que vio la sesion anterior
 * (persistido en DataStore). Si difieren, fuerza un clearSesion() para que
 * la app SIEMPRE arranque en Login despues de una actualizacion. Esto
 * resuelve el bug "instalo la nueva APK pero la app salta directo a Totem
 * porque el JWT sigue en DataStore".
 *
 * El chequeo se hace SINCRONAMENTE (runBlocking) a proposito: si fuera async
 * el NavHost leeria `isLoggedIn` con el JWT viejo antes de que la coroutine
 * lo limpie, y el usuario veria la pantalla anterior igual. La latencia
 * de un read+write a DataStore en main thread es <10ms, aceptable para un
 * chequeo que se hace UNA vez por arranque del proceso.
 */
class CsaePosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        enforceCleanLoginOnUpgrade()
    }

    /**
     * Sprint 3.3: bumpear versionCode dispara un clearSesion() automatico
     * al primer onCreate con el codigo nuevo. Asi la proxima vez que el
     * operador abra la app vera SIEMPRE el Login primero, aunque tenga
     * un JWT valido de la version anterior.
     */
    private fun enforceCleanLoginOnUpgrade() {
        val current = BuildConfig.VERSION_CODE
        runBlocking {
            val lastSeen = ServiceLocator.authStore.getLastSeenVersionCode()
            if (lastSeen != current) {
                // Version nueva (o primera instalacion) -> forzar logout limpio.
                ServiceLocator.authStore.clearSesion()
            }
            // Persistir SIEMPRE la version actual, incluso si fue una
            // instalacion limpia (lastSeen == 0) -> asi no releemos el DataStore
            // en cada arranque para nada.
            ServiceLocator.authStore.setLastSeenVersionCode(current)
        }
    }
}
