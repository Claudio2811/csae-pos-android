package cl.csae.pos

import android.app.Application
import cl.csae.pos.di.ServiceLocator

/**
 * Application class del POS. Inicializa el [ServiceLocator] una sola vez al
 * arrancar el proceso. Toda la app obtiene sus dependencias desde aca.
 *
 * Por que un ServiceLocator y no Hilt/Koin:
 * - La app es chica (4 pantallas, 3 repos). Hilt suma KSP/kapt y tiempo de build.
 * - Para Salamanca no necesitamos scope de ViewModel ni grafos complejos.
 * - Si despues crece, migrar a Hilt es directo (mismas interfaces).
 */
class CsaePosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
