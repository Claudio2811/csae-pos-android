package cl.csae.pos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import cl.csae.pos.ui.CsaeNavHost

/**
 * MainActivity del POS.
 *
 * Sprint 3.1.2: el ServiceLocator se inicializa en [CsaePosApplication.onCreate],
 * asi que al llegar aca ya tenemos AuthRepository, CatalogRepository, etc.
 *
 * **Sprint F16 (2026-08-11):** el theme dinamico del casino (colores +
 * logo) ahora se aplica dentro de [CsaeNavHost] (que tiene scope de
 * coroutine y puede `collectAsState` el Flow de colores del casino). Antes
 * el `CsaePosTheme` se aplicaba aca sin colores dinamicos; ahora
 * CsaeNavHost se encarga.
 *
 * **Modo kiosko real** (lock task mode con DevicePolicyManager) requiere que
 * la app sea el "device owner" del tablet. Eso se hace con `dpm set-device-owner`
 * por ADB o con un provisioning app. Para MVP Salamanca el modo kiosko es UX
 * (auto-volver al POS despues de 10s + mantener pantalla encendida). En un
 * piloto en tablet dedicado esto es suficiente; si despues se requiere
 * bloqueo fuerte, agregamos un `BootReceiver` + device owner setup.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Mantener la pantalla encendida mientras la app esta al frente.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CsaeNavHost()
        }
    }
}
