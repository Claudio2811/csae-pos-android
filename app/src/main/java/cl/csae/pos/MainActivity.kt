package cl.csae.pos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import cl.csae.pos.ui.CsaeNavHost
import cl.csae.pos.ui.theme.CsaePosTheme

/**
 * MainActivity del POS.
 *
 * Sprint 3.1.2: el ServiceLocator se inicializa en [CsaePosApplication.onCreate],
 * asi que al llegar aca ya tenemos AuthRepository, CatalogRepository, etc.
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
            CsaePosTheme {
                CsaeNavHost()
            }
        }
    }
}
