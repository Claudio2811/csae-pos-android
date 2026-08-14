package cl.csae.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.ui.components.CasinoLogoImage
import cl.csae.pos.ui.components.MinimalTopBar

/**
 * Pantalla de seleccion de modo (sprint 3.2 + F9).
 *
 * Sprint F23 (2026-08-14): UI Polish v1.
 *   - Cada modo ahora es una CARD con icono circular + titulo + descripcion
 *     + flecha indicadora, en vez de un boton outlined gigante con solo
 *     texto. Mucho mas profesional y da contexto al operador sobre que
 *     hace cada modo.
 *   - Header con logo del casino + nombre + subtitulo "Elige modo".
 *   - Empty state con icono y mensaje claro cuando el rol no tiene modos.
 *   - Espaciado consistente con el resto de la app (16dp entre cards).
 */
private data class ModoOpcion(
    val key: String,
    val titulo: String,
    val descripcion: String,
    val icono: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectScreen(
    onSelectModo: (String) -> Unit,
    onSettings: () -> Unit = {},
    usuario: UsuarioPos? = null,
) {
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)
    val modosDisponibles = remember(usuario?.rol) {
        val allModos = listOf(
            ModoOpcion(
                key = "TOTEM",
                titulo = "Totem",
                descripcion = "Kiosko self-service para que el comensal busque su RUT y consuma.",
                icono = Icons.Filled.Devices,
            ),
            ModoOpcion(
                key = "POS",
                titulo = "Caja (POS)",
                descripcion = "Operador atiende al comensal: busca, elige servicio, genera ticket.",
                icono = Icons.Filled.PhonelinkSetup,
            ),
            ModoOpcion(
                key = "GARZON",
                titulo = "Garzon",
                descripcion = "Escanea el QR del ticket del comensal para validar el consumo.",
                icono = Icons.Filled.Hub,
            ),
        )
        when (usuario?.rol) {
            "OperadorPos" -> allModos.filter { it.key == "POS" }
            "Garzon" -> allModos.filter { it.key == "GARZON" }
            "AdminCasino", "SupervisorCasino", "SuperAdmin" -> allModos
            else -> emptyList()
        }
    }

    Scaffold(
        topBar = { MinimalTopBar(onSettings = onSettings) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: logo del casino.
            Spacer(Modifier.height(24.dp))
            CasinoLogoImage(
                logoUrl = casinoTheme?.logoUrl,
                contentDescription = casinoTheme?.razonSocial ?: "CSAE",
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))

            casinoTheme?.razonSocial?.let { nombre ->
                Text(
                    nombre,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "Elige el modo de operacion",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(32.dp))

            if (modosDisponibles.isEmpty()) {
                EmptyStateSinModos()
            } else {
                // Cards verticales con icono + titulo + descripcion + flecha.
                modosDisponibles.forEachIndexed { i, modo ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    ModoCard(modo = modo, onClick = { onSelectModo(modo.key) })
                }
            }
        }
    }
}

/**
 * F23: card de modo con icono circular tinted, titulo bold, descripcion
 * secundaria, y flecha indicadora a la derecha. Toda la card es clickeable.
 */
@Composable
private fun ModoCard(modo: ModoOpcion, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icono circular tinted.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    modo.icono,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            // Titulo + descripcion.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    modo.titulo,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modo.descripcion,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 18.sp,
                )
            }
            // Flecha indicadora.
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * F23: empty state cuando el rol del usuario no tiene modos disponibles.
 * Por seguridad muestra icono + mensaje claro (LoginScreen deberia haber
 * bloqueado usuarios empresa, pero por si acaso).
 */
@Composable
private fun EmptyStateSinModos() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Sin permisos para esta app",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tu cuenta no tiene acceso a ningun modo de operacion. " +
                "Contacta al administrador del casino para pedir acceso.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
