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
 * El operador ingresa su email + contrasena. El `AuthRepository` llama a
 * `POST /api/v1/auth/login` y guarda el JWT en DataStore si es OK.
 *
 * Despues del login se dispara `catalogRepo.refresh()` para bajar el catalog
 * completo (empresas, servicios, comensales) que se usara en el POS.
 *
 * Sprint 3.2: la pantalla es reutilizada por modo Garzon (header "Modo Garzon")
 * y modo TOTEM (header "Modo Totem", con un campo RUT adicional que NO se usa
 * para login del operador, queda como nota de UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginOk: (UsuarioPos) -> Unit,
    headerTitle: String = "CSAE POS",
    headerSubtitle: String = "Control de Servicios de Alimentacion",
    brandColor: Color? = null,
) {
    val effectiveBrand = brandColor ?: MaterialTheme.colorScheme.primary
    var email by remember { mutableStateOf("admin@casino-demo.cl") }
    var password by remember { mutableStateOf("Demo123!") }
    var rutOperador by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (loading) return
        if (email.isBlank() || password.isBlank()) {
            error = "Ingresa email y contrasena."
            return
        }
        loading = true
        error = null
        scope.launch {
            val result = ServiceLocator.authRepo.login(email.trim(), password)
            result
                .onSuccess { usuario ->
                    // Bajar el catalog en background (no bloquea el login).
                    // Si falla, el POS mostrara un error al buscar comensal.
                    val cat = ServiceLocator.catalogRepo.refresh()
                    if (cat.isFailure) {
                        // No bloqueamos el login por esto: el operador puede ir al POS
                        // y reintentar la descarga desde ahi.
                    }
                    loading = false
                    onLoginOk(usuario)
                }
                .onFailure { e ->
                    loading = false
                    error = e.message ?: "No se pudo iniciar sesion."
                }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = effectiveBrand) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = headerTitle,
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = headerSubtitle,
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(48.dp))

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Iniciar sesion", style = MaterialTheme.typography.headlineSmall)

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    // RUT del operador (opcional, sprint 3.2). El tótem no requiere
                    // login, pero dejamos el campo en LoginScreen con teclado
                    // numérico por si después se usa para auditoría del operador.
                    OutlinedTextField(
                        value = rutOperador,
                        onValueChange = { v ->
                            // Acepta solo [0-9Kk.-], sin espacios ni letras.
                            val filtrado = v.filter { it.isDigit() || it == '-' || it == '.' || it == 'k' || it == 'K' }
                            rutOperador = filtrado
                            error = null
                        },
                        label = { Text("RUT (opcional)") },
                        placeholder = { Text("12345678-5") },
                        singleLine = true,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Contrasena") },
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

                    error?.let { msg ->
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Button(
                        onClick = { submit() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Conectando...", fontSize = 16.sp)
                        } else {
                            Text("Entrar", fontSize = 18.sp)
                        }
                    }

                    Text(
                        text = "Demo Casino: admin@casino-demo.cl / Demo123!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}
