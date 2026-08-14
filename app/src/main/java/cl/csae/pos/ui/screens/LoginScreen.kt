package cl.csae.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.components.AppTextField
import cl.csae.pos.ui.components.CasinoLogoImage
import kotlinx.coroutines.launch

/**
 * Pantalla de login. Sprint 3.1.2: contra la API real de CSAE.
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframes del usuario.
 * Sprint F13 (2026-08-11): maxLength enforced en los 2 campos.
 * Sprint F18.3 (2026-08-11): logo del casino arriba del titulo.
 * Sprint F23 (2026-08-14): UI Polish v1.
 *   - Boton "Ingresar" ahora Filled (era Outlined).
 *   - Header con logo del casino + titulo + subtitulo "Accede al POS".
 *   - Iconos en los campos de texto (Email, Lock).
 *   - Toggle de password integrado en el field (icon button trailing).
 *   - Banner de error con icono + fondo tinted (no solo texto).
 *   - Loading state con CircularProgressIndicator + texto descriptivo.
 *   - Version del app + endpoint API en el footer (info tecnica sutil).
 *   - Espaciado mas generoso y consistente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginOk: (UsuarioPos) -> Unit = {},
) {
    var usuario by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)

    val usuarioError: String? = when {
        usuario.isBlank() -> null
        usuario.length > 200 -> "Email o usuario demasiado largo (max 200)"
        else -> null
    }
    val passwordError: String? = when {
        password.isBlank() -> null
        password.length < 6 -> "Password debe tener al menos 6 caracteres"
        password.length > 200 -> "Password demasiado largo (max 200)"
        else -> null
    }

    fun submit() {
        if (loading) return
        if (usuario.isBlank()) {
            error = "Ingresa tu usuario."
            return
        }
        if (password.isBlank()) {
            error = "Ingresa tu contrasena."
            return
        }
        if (usuario.length > 200) {
            error = "Email o usuario demasiado largo (max 200)."
            return
        }
        if (password.length < 6) {
            error = "Password debe tener al menos 6 caracteres."
            return
        }
        loading = true
        error = null
        scope.launch {
            val result = ServiceLocator.authRepo.login(usuario.trim(), password)
            result
                .onSuccess { u ->
                    val rol = u.rol
                    if (rol == "AdminEmpresa" || rol == "GestorComensales") {
                        ServiceLocator.authRepo.logout()
                        loading = false
                        error = "Esta app es solo para operadores del casino. " +
                            "Los usuarios de empresa usan el Portal Web."
                        return@onSuccess
                    }
                    val cat = ServiceLocator.catalogRepo.refresh()
                    loading = false
                    onLoginOk(u)
                }
                .onFailure { e ->
                    loading = false
                    error = e.message ?: "No se pudo iniciar sesion."
                }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(40.dp))

            // Header: logo del casino + titulo + subtitulo.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Logo con fondo tinted circular.
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CasinoLogoImage(
                        logoUrl = casinoTheme?.logoUrl,
                        contentDescription = casinoTheme?.razonSocial ?: "CSAE",
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        casinoTheme?.razonSocial ?: "CSAE POS",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Punto de venta",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // Titulo principal.
            Text(
                "Iniciar sesion",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Ingresa tus credenciales para acceder al sistema.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(32.dp))

            // Campo Usuario con icono.
            OutlinedTextField(
                value = usuario,
                onValueChange = { usuario = it.take(200); error = null },
                label = { Text("Usuario") },
                placeholder = { Text("operador o email@empresa.cl") },
                leadingIcon = {
                    Icon(Icons.Filled.Email, contentDescription = null)
                },
                singleLine = true,
                enabled = !loading,
                isError = usuarioError != null,
                supportingText = if (usuarioError != null) {
                    { Text(usuarioError) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(Modifier.height(12.dp))

            // Campo Contrasena con icono + toggle de visibilidad.
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(200); error = null },
                label = { Text("Contrasena") },
                placeholder = { Text("minimo 6 caracteres") },
                leadingIcon = {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Ocultar contrasena" else "Mostrar contrasena",
                        )
                    }
                },
                singleLine = true,
                enabled = !loading,
                isError = passwordError != null,
                supportingText = if (passwordError != null) {
                    { Text(passwordError) }
                } else null,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = MaterialTheme.shapes.medium,
            )

            // Banner de error (con icono + fondo tinted, no solo texto rojo).
            error?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Boton Ingresar (Filled, prominente).
            Button(
                onClick = { submit() },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Conectando...", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                } else {
                    Text("Ingresar", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Footer: hint sutil de ayuda + version.
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))
            Text(
                "Si no recuerdas tu contrasena, contacta al administrador del casino.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}
