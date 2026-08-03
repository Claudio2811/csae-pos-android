package cl.csae.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * TopAppBar estandar con boton "Volver / Cambiar modo" (izquierda) + logout
 * opcional (derecha). Usado en todas las pantallas operativas (Totem, POS,
 * Garzon, Consumos, Configuracion, Ticket) para que el usuario siempre
 * tenga a mano como salir al selector de modo.
 *
 * Sprint 3.2.1: centralizamos el top bar para no repetir el codigo en
 * cada screen y para que el comportamiento sea consistente.
 *
 * @param title titulo principal
 * @param subtitle subtitulo opcional debajo del titulo
 * @param onCambiarModo callback que se conecta a navController.navigate
 *                    hacia "mode_select" con popUpTo(0)
 * @param onLogout callback opcional para cerrar sesion
 * @param actions slot para acciones adicionales (ej. boton refresh)
 * @param colors permite customizar colores (ej. Totem usa azul de marca)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CambiarModoTopBar(
    title: String,
    onCambiarModo: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLogout: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onPrimary,
        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
    ),
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(title)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onCambiarModo) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cambiar modo",
                )
            }
        },
        actions = {
            actions()
            if (onLogout != null) {
                IconButton(onClick = onLogout) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Cerrar sesion",
                    )
                }
            }
        },
        colors = colors,
    )
}

/** Helper para crear colors con un container custom (ej. azul del Totem). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun topBarColorsFor(container: Color): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = container,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)
