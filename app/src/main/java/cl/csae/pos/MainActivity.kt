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
 * **Modo kiosko (Sprint 3.0 simple):**
 * Por ahora la app arranca normal. Para activar modo kiosko real
 * (lock task mode), en Sprint 3.1 usamos DevicePolicyManager + propietario
 * del dispositivo. Por ahora el comportamiento kiosko es solo UX (auto-volver
 * al POS despues de 10s sin accion).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Mantener la pantalla encendida mientras la app esta al frente
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CsaePosTheme {
                CsaeNavHost()
            }
        }
    }
}
