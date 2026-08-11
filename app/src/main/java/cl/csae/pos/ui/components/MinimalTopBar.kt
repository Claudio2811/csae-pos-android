package cl.csae.pos.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Sprint F8 (2026-08-11): TopAppBar minimalista. Solo el icono de settings
 * (engranaje) a la derecha, sin title ni navigation icon. Reemplaza al
 * CambiarModoTopBar con flecha back + titulo + subtitle + LOGIN/CONFIG
 * buttons que se usaba antes en las pantallas del flujo totem y en el
 * selector de modo.
 *
 * Patron de los wireframes del usuario: el operador no necesita salir a
 * cada rato, solo ir a Settings cuando quiere cambiar config del device.
 * Para volver al selector de modo, el sistema operativo lo hace con
 * el back gesture / boton home del kiosko.
 *
 * @param onSettings callback del boton de settings (abrir config del device)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimalTopBar(
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = { /* vacio segun wireframes */ },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Configuracion",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
