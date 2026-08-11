package cl.csae.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.UsuarioPos
import kotlinx.coroutines.launch

/**
 * Pantalla de login. Sprint 3.1.2: contra la API real de CSAE.
 *
 * Sprint F8 (2026-08-11): rediseño segun wireframes del usuario. Ahora
 * tiene solo 2 campos (Usuario + Contrasena), sin card wrapper ni
 * titulo grande de marca. Los parametros `headerTitle`, `headerSubtitle`
 * y `brandColor` se mantienen para compatibilidad con AppNavHost pero
 * ya no se renderizan en el layout — la UI es la misma para TOTEM /
 * GARZON / POS.
 */
@Composable
fun LoginScreen(
    onLoginOk: (UsuarioPos) -> Unit,
    // Parametros legacy mantenidos por compatibilidad con AppNavHost, ya
    // no se renderizan en el layout minimalista del Sprint F8.
    @Suppress("unused") headerTitle: String = "CSAE POS",
    @Suppress("unused") headerSubtitle: String = "Control de Servicios de Alimentacion",
    @Suppress("unused") brandColor: Color? = null,
) {
    var usuario by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (loading) return
        if (usuario.isBlank() || password.isBlank()) {
            error = "Ingresa usuario y contrasena."
            return
        }
        loading = true
        error = null
        scope.launch {
            val result = ServiceLocator.authRepo.login(usuario.trim(), password)
            result
                .onSuccess { u ->
                    // Sprint F9 (2026-08-11): rechazar usuarios empresa. Esta
                    // app POS es solo para operadores del casino (AdminCasino,
                    // OperadorPos, Garzon, etc). Los AdminEmpresa /
                    // GestorComensales de empresa deben usar el Portal Web.
                    val rol = u.rol
                    if (rol == "AdminEmpresa" || rol == "GestorComensales") {
                        ServiceLocator.authRepo.logout()
                        loading = false
                        error = "Esta app es solo para operadores del casino. " +
                            "Los usuarios de empresa usan el Portal Web."
                        return@onSuccess
                    }
                    // Bajar el catalog en background (no bloquea el login).
                    val cat = ServiceLocator.catalogRepo.refresh()
                    if (cat.isFailure) {
                        // No bloqueamos el login por esto: el operador puede ir
                        // al POS y reintentar la descarga desde ahi.
                    }
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
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Top),
        ) {
            Spacer(Modifier.height(48.dp))

            // Titulo "Iniciar sesion" (wireframe 1)
            Text(
                "Iniciar sesion",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(24.dp))

            // Campo Usuario
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Usuario",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it; error = null },
                    singleLine = true,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            // Campo Contrasena
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Contrasena",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    singleLine = true,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Ocultar" else "Mostrar",
                            )
                        }
                    },
                )
            }

            // Error
            error?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Boton Ingresar
            OutlinedButton(
                onClick = { submit() },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Conectando...", fontSize = 18.sp)
                } else {
                    Text("Ingresar", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}
