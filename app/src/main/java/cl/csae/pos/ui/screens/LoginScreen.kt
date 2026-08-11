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
import cl.csae.pos.ui.components.AppTextField
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
 *
 * Sprint F13 (2026-08-11): maxLength enforced en los 2 campos (200 chars,
 * mismo limite que el LoginRequestValidator) + supportingText con conteo
 * en vivo "X/200". El boton Ingresar valida localmente largo y no-vacio
 * antes de pegarle a la API.
 */
@Composable
fun LoginScreen(
    onLoginOk: (UsuarioPos) -> Unit,
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

    // Validaciones locales (reflejan las del LoginRequestValidator backend).
    val usuarioError: String? = when {
        usuario.isBlank() -> null  // solo mostrar despues del primer submit
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

            // Campo Usuario (Sprint F13: maxLength 200 via AppTextField)
            AppTextField(
                value = usuario,
                onValueChange = { usuario = it; error = null },
                label = "Usuario",
                placeholder = "operador o email@empresa.cl",
                maxLength = 200,
                enabled = !loading,
                isError = usuarioError != null,
                errorMessage = usuarioError,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            // Campo Contrasena (Sprint F13: maxLength 200 via AppTextField)
            AppTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "Contrasena",
                placeholder = "minimo 6 caracteres",
                maxLength = 200,
                enabled = !loading,
                isError = passwordError != null,
                errorMessage = passwordError,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                // Truco para el trailing icon: AppTextField no lo soporta, asi
                // que wrapeamos el field con un Box con el icon al lado.
            )

            // Sprint F13: el toggle de mostrar/ocultar password se movio a una
            // fila aparte debajo del field para no romper el AppTextField.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showPassword) "Ocultar" else "Mostrar")
                }
            }

            // Error general del submit (vs. error local del field).
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
