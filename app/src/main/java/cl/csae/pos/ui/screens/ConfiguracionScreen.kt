package cl.csae.pos.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.csae.pos.BuildConfig
import cl.csae.pos.data.api.DispositivoPosDto
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.ui.components.CambiarModoTopBar
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Pantalla de Configuracion del dispositivo.
 *
 * F4.3: reescrita con scroll vertical, mejor visual (ListItem con icono
 * circular), feedback inmediato via snackbar, y seccion de impresora
 * mejorada con estado de conexion + "Probar conexion" (solo verifica BT)
 * y "Olvidar impresora".
 *
 * Secciones (en orden, todas colapsables implicitamente por scroll):
 *   1. Operador  : nombre, sucursal, dispositivo POS.
 *   2. Modo      : TOTEM / POS / GARZON (radio con icono).
 *   3. Impresora : nombre + MAC + estado + acciones (seleccionar, probar,
 *                  imprimir, olvidar).
 *   4. Tema      : refrescar tema del casino.
 *   5. Sesion    : cerrar sesion.
 *   6. Info      : version + endpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfiguracionScreen(
    onCambiarModo: () -> Unit = {},
    /**
     * F3: navega al SucursalSelectScreen para que el operador cambie la
     * sucursal activa manualmente. Si el casino no tiene >1 sucursal, el
     * callback no se usa (la card de sucursal se oculta).
     */
    onIrSucursal: () -> Unit = {},
    /** Cierra la sesion y vuelve a Login. */
    onCerrarSesion: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ----- Estado local -----
    var modoPreferido by remember { mutableStateOf<String?>(null) }
    var macImpresora by remember { mutableStateOf<String?>(null) }
    var nombreImpresora by remember { mutableStateOf<String?>(null) }
    var showPrinterDialog by remember { mutableStateOf(false) }
    var printStatus by remember { mutableStateOf<String?>(null) }
    var printing by remember { mutableStateOf(false) }
    var probandoConexion by remember { mutableStateOf(false) }
    var permDeniedSnack by remember { mutableStateOf(false) }
    var refreshingTheme by remember { mutableStateOf(false) }
    var dispositivos by remember { mutableStateOf<List<DispositivoPosDto>>(emptyList()) }
    var loadingDispositivos by remember { mutableStateOf(false) }
    var showDispositivoDialog by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }
    var cerrandoSesion by remember { mutableStateOf(false) }

    val dispositivoActual by ServiceLocator.dispositivoPosActual.current
        .collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }

    val (versionName, versionCode) = remember {
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (pInfo.versionName ?: BuildConfig.VERSION_NAME) to
                (pInfo.versionCode?.toLong() ?: BuildConfig.VERSION_CODE.toLong())
        } catch (e: PackageManager.NameNotFoundException) {
            BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong()
        }
    }

    // Cargar estado inicial (modo preferido + MAC impresora).
    LaunchedEffect(Unit) {
        modoPreferido = ServiceLocator.authStore.getModoPreferido()
        macImpresora = ServiceLocator.authStore.getImpresoraMac()
    }

    // F19: cada vez que se abre el dialog, recarga la lista de dispositivos
    // del casino actual. Asi si el admin agrego uno nuevo, el operador lo ve.
    fun cargarDispositivos() {
        loadingDispositivos = true
        dispositivos = emptyList()
        scope.launch {
            try {
                val restauranteId = ServiceLocator.authStore.restauranteId.firstOrNull()
                android.util.Log.d("CsaeConfig", "GET /api/v1/dispositivos-pos?incluirInactivos=true (restauranteId del JWT=$restauranteId)")
                val resp = ServiceLocator.posApiService.listarDispositivos(incluirInactivos = true)
                android.util.Log.d("CsaeConfig", "  -> HTTP ${resp.code()}, body size=${resp.body()?.size ?: "null"}")
                if (resp.isSuccessful) {
                    val raw = resp.body().orEmpty()
                    android.util.Log.d("CsaeConfig", "  total raw=${raw.size}, activos=${raw.count { it.activo }}")
                    if (raw.isEmpty()) {
                        android.util.Log.w("CsaeConfig", "  Lista vacia del backend. Posibles causas: (a) el restaurante del JWT ($restauranteId) no tiene dispositivos registrados, (b) el admin del casino no ha creado dispositivos, (c) el JWT apunta a otro restaurante.")
                    } else {
                        raw.take(3).forEach { d ->
                            android.util.Log.d("CsaeConfig", "  - ${d.nombre} (${d.tipoNombre}, restauranteId=${d.restauranteId}, activo=${d.activo})")
                        }
                    }
                    dispositivos = raw.filter { it.activo }
                } else {
                    val err = resp.errorBody()?.string() ?: "sin body"
                    android.util.Log.e("CsaeConfig", "Error HTTP ${resp.code()}: $err")
                    snackbar.showSnackbar("Error al cargar dispositivos (HTTP ${resp.code()})")
                }
            } catch (t: Throwable) {
                android.util.Log.e("CsaeConfig", "Error de red", t)
                snackbar.showSnackbar("Error de red: ${t.message ?: "desconocido"}")
            } finally {
                loadingDispositivos = false
            }
        }
    }
    LaunchedEffect(showDispositivoDialog, retryTrigger) {
        if (showDispositivoDialog) cargarDispositivos()
    }

    // Re-leer nombre del dispositivo guardado si la MAC cambio.
    LaunchedEffect(macImpresora) {
        if (macImpresora != null) {
            val devices = ServiceLocator.printerService.listarEmparejados()
            val match = devices.firstOrNull { it.address == macImpresora }
            nombreImpresora = match?.let {
                try { it.name } catch (_: SecurityException) { null }
            } ?: macImpresora
        } else {
            nombreImpresora = null
        }
    }

    // ----- Acciones -----
    fun setModo(modo: String?) {
        val oldModo = modoPreferido
        modoPreferido = modo
        scope.launch {
            ServiceLocator.authStore.setModoPreferido(modo)
            val msg = when (modo) {
                "TOTEM" -> "Modo por defecto: Tótem (kiosko). Al abrir iras directo ahi."
                "POS" -> "Modo por defecto: POS (operador). Al abrir iras directo ahi."
                "GARZON" -> "Modo por defecto: Garzón (escanea QR). Al abrir iras directo ahi."
                else -> "Modo por defecto quitado. Al abrir se mostrara el selector de modo."
            }
            if (oldModo != modo) snackbar.showSnackbar(msg)
        }
    }

    fun selectImpresora(device: BluetoothDevice) {
        macImpresora = device.address
        val nombre = try { device.name } catch (_: SecurityException) { null } ?: device.address
        nombreImpresora = nombre
        scope.launch {
            ServiceLocator.authStore.setImpresora(device.address, nombre)
            snackbar.showSnackbar("Impresora guardada: $nombre")
        }
        showPrinterDialog = false
    }

    fun olvidarImpresora() {
        macImpresora = null
        nombreImpresora = null
        scope.launch {
            ServiceLocator.authStore.setImpresora(null, null)
            snackbar.showSnackbar("Impresora olvidada. Selecciona otra cuando quieras.")
        }
    }

    fun pedirPermisoYMostrarDialogo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) showPrinterDialog = true
            else permDeniedSnack = true
        } else {
            showPrinterDialog = true
        }
    }

    /**
     * F4.3: NUEVO. Solo intenta abrir el socket Bluetooth, no imprime nada.
     * Util para diagnosticar si el problema es la conexion BT o la impresora
     * fisica. Muestra resultado claro en snackbar.
     */
    fun probarConexion() {
        val mac = macImpresora
        if (mac == null) {
            scope.launch { snackbar.showSnackbar("Selecciona una impresora primero.") }
            return
        }
        probandoConexion = true
        scope.launch {
            val conn = ServiceLocator.printerService.connectBtPort(mac)
            probandoConexion = false
            conn.onSuccess {
                snackbar.showSnackbar("Conexion OK con ${nombreImpresora ?: mac}. El Bluetooth anda.")
            }.onFailure {
                val err = it.message ?: "desconocido"
                android.util.Log.e("CsaeConfig", "probarConexion fallo: $err", it)
                snackbar.showSnackbar(
                    "No se pudo conectar a $mac. " +
                        "Revisa: (1) la MAC corresponde a esta impresora, " +
                        "(2) la impresora esta encendida y emparejada en Settings > Bluetooth, " +
                        "(3) permiso BLUETOOTH_CONNECT otorgado."
                )
            }
        }
    }

    fun imprimirPrueba() {
        val mac = macImpresora
        if (mac == null) {
            scope.launch { snackbar.showSnackbar("Selecciona una impresora primero.") }
            return
        }
        printing = true
        scope.launch {
            val conn = ServiceLocator.printerService.connectBtPort(mac)
            if (conn.isFailure) {
                printing = false
                val err = conn.exceptionOrNull()?.message ?: "desconocido"
                android.util.Log.e("CsaeConfig", "imprimirPrueba: connectBtPort fallo: $err", conn.exceptionOrNull())
                snackbar.showSnackbar("Error conectando: $err")
                return@launch
            }
            val r = ServiceLocator.printerService.imprimirPrueba()
            printing = false
            r.onSuccess {
                snackbar.showSnackbar("OK: prueba enviada a ${nombreImpresora ?: mac}.")
            }.onFailure {
                val err = it.message ?: "desconocido"
                android.util.Log.e("CsaeConfig", "imprimirPrueba fallo: $err", it)
                snackbar.showSnackbar("Error al imprimir: $err")
            }
        }
    }

    fun seleccionarDispositivo(d: DispositivoPosDto) {
        scope.launch {
            ServiceLocator.dispositivoPosActual.setDispositivo(
                id = d.id,
                nombre = d.nombre,
                codigo = d.androidId,
                tipo = d.tipo,
            )
            showDispositivoDialog = false
            snackbar.showSnackbar("Dispositivo seleccionado: ${d.nombre}")
        }
    }

    fun refrescarTema() {
        if (refreshingTheme) return
        refreshingTheme = true
        scope.launch {
            val r = ServiceLocator.authRepo.refreshCasinoTheme()
            refreshingTheme = false
            r.onSuccess { snackbar.showSnackbar("Tema del casino actualizado.") }
             .onFailure { snackbar.showSnackbar("Error al refrescar: ${it.message ?: "desconocido"}") }
        }
    }

    fun cerrarSesion() {
        if (cerrandoSesion) return
        cerrandoSesion = true
        scope.launch {
            // F4.3: usar ServiceLocator.logoutAndReset() (no authRepo) porque
            // este limpia TODO el estado (DataStore + cache + JWT) y navega
            // via el callback onCerrarSesion (definido en AppNavHost).
            ServiceLocator.logoutAndReset()
            cerrandoSesion = false
            onCerrarSesion()
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showPrinterDialog = true
        } else {
            scope.launch {
                snackbar.showSnackbar(
                    "Sin permiso BLUETOOTH_CONNECT. Otorgalo en " +
                        "Settings > Apps > CSAE POS > Permisos > Dispositivos cercanos."
                )
            }
        }
    }

    LaunchedEffect(permDeniedSnack) {
        if (permDeniedSnack) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                showPrinterDialog = true
            }
            permDeniedSnack = false
        }
    }

    // ----- UI -----
    Scaffold(
        topBar = {
            CambiarModoTopBar(
                title = "Configuracion",
                onCambiarModo = onCambiarModo,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // F4.3: scroll vertical. Sin esto, en pantallas chicas las
                // ultimas cards (Sesion, Version) quedan cortadas debajo
            // del fold.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Seccion: Modo preferido ---
            ConfigCard(
                title = "Modo de operacion",
                subtitle = "Al abrir la app ira directo a este modo.",
                icon = Icons.Filled.Tune,
            ) {
                val opciones = listOf(
                    Triple("TOTEM", "Tótem (kiosko self-service)", Icons.Filled.Devices),
                    Triple("POS", "POS (operador atiende)", Icons.Filled.PhonelinkSetup),
                    Triple("GARZON", "Garzón (escanea QR)", Icons.Filled.Hub),
                )
                opciones.forEach { (m, label, icon) ->
                    ConfigRow(
                        icon = icon,
                        title = label,
                        trailing = {
                            RadioButton(
                                selected = modoPreferido == m,
                                onClick = { setModo(m) },
                            )
                        },
                        onClick = { setModo(m) },
                    )
                }
                if (modoPreferido != null) {
                    TextButton(
                        onClick = { setModo(null) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quitar modo por defecto")
                    }
                }
            }

            // --- Seccion: Sucursal (F3) ---
            val sucursalActualId by ServiceLocator.authStore.sucursalId
                .collectAsState(initial = null)
            val sucursales by ServiceLocator.authRepo.sucursalesDisponibles.collectAsState()
            if (sucursales.isNotEmpty() || sucursalActualId != null) {
                val sucursalActualNombre = sucursales
                    .firstOrNull { it.id == sucursalActualId }
                    ?.nombre
                    ?: "Casino completo"
                ConfigCard(
                    title = "Sucursal activa",
                    subtitle = "En que sucursal del casino operas. El catalog se re-baja al cambiar.",
                    icon = Icons.Filled.Store,
                ) {
                    ConfigRow(
                        icon = Icons.Filled.Restaurant,
                        title = sucursalActualNombre,
                        subtitle = if (sucursales.size > 1)
                            "${sucursales.size} sucursales disponibles"
                        else
                            "Unica sucursal del casino",
                        trailing = if (sucursales.size > 1) {
                            { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Cambiar") }
                        } else null,
                        onClick = if (sucursales.size > 1) onIrSucursal else null,
                    )
                }
            }

            // --- Seccion: Dispositivo POS (F19) ---
            ConfigCard(
                title = "Dispositivo POS",
                subtitle = "Con que dispositivo fisico operas. Lo registra el admin del casino.",
                icon = Icons.Filled.PhonelinkSetup,
            ) {
                val nombreDisp = dispositivoActual?.nombre ?: "Sin asignar"
                val androidId = dispositivoActual?.codigo
                ConfigRow(
                    icon = if (dispositivoActual == null) Icons.Filled.PhonelinkSetup else Icons.Filled.Devices,
                    title = nombreDisp,
                    subtitle = if (androidId != null) "ID: ${androidId.take(8)}" else "Toca para seleccionar",
                    trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    onClick = { showDispositivoDialog = true },
                )
            }

            // --- Seccion: Impresora Bluetooth (F4.3 mejorada) ---
            ConfigCard(
                title = "Impresora Bluetooth",
                subtitle = "Impresora para tickets. Debe estar emparejada en Settings > Bluetooth.",
                icon = Icons.Filled.Bluetooth,
            ) {
                if (macImpresora == null) {
                    ConfigRow(
                        icon = Icons.Filled.BluetoothSearching,
                        title = "Sin impresora seleccionada",
                        subtitle = "Toca para elegir una de las emparejadas",
                        trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                        onClick = { pedirPermisoYMostrarDialogo() },
                    )
                } else {
                    // Estado de la conexion (visual, no es en vivo - solo refleja
                    // si intentamos conectar o no).
                    val estadoIcon = when {
                        printing || probandoConexion -> Icons.Filled.BluetoothSearching
                        else -> Icons.Filled.BluetoothConnected
                    }
                    ConfigRow(
                        icon = estadoIcon,
                        title = nombreImpresora ?: macImpresora!!,
                        subtitle = "MAC: $macImpresora",
                    )
                    // Acciones de la impresora, en columna (no row) para
                    // que se vea bien en pantallas chicas.
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { pedirPermisoYMostrarDialogo() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cambiar impresora")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { probarConexion() },
                        enabled = !probandoConexion && !printing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (probandoConexion) "Probando..." else "Probar conexion (sin imprimir)")
                    }
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = { imprimirPrueba() },
                        enabled = !printing && !probandoConexion,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (printing) "Imprimiendo..." else "Imprimir ticket de prueba")
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { olvidarImpresora() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Olvidar impresora")
                    }
                }
            }

            // --- Seccion: Tema del casino ---
            ConfigCard(
                title = "Tema del casino",
                subtitle = "Si el admin cambio el color o el logo, refresca para ver el cambio.",
                icon = Icons.Filled.Palette,
            ) {
                OutlinedButton(
                    onClick = { refrescarTema() },
                    enabled = !refreshingTheme,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (refreshingTheme) "Actualizando..." else "Refrescar tema")
                }
            }

            // --- Seccion: Sesion ---
            ConfigCard(
                title = "Sesion",
                subtitle = "Cerrar sesion vuelve al login. Los datos locales se borran.",
                icon = Icons.Filled.ExitToApp,
            ) {
                OutlinedButton(
                    onClick = { cerrarSesion() },
                    enabled = !cerrandoSesion,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Filled.ExitToApp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (cerrandoSesion) "Cerrando..." else "Cerrar sesion")
                }
            }

            // --- Footer: Version ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "CSAE POS v$versionName ($versionCode)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }

    // ----- Dialogs -----
    if (showPrinterDialog) {
        PrinterPickerDialog(
            onDismiss = { showPrinterDialog = false },
            onSelect = { selectImpresora(it) },
        )
    }

    if (showDispositivoDialog) {
        AlertDialog(
            onDismissRequest = { showDispositivoDialog = false },
            title = { Text("Seleccionar dispositivo POS") },
            text = {
                if (loadingDispositivos) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Cargando dispositivos del casino...")
                    }
                } else if (dispositivos.isEmpty()) {
                    Column {
                        Text(
                            "No se encontraron dispositivos activos en este casino.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Si pensas que deberia haber dispositivos, revisa la conexion o consulta al admin del casino para que los registre.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dispositivos.forEach { d ->
                            val isSelected = dispositivoActual?.id == d.id
                            OutlinedButton(
                                onClick = { seleccionarDispositivo(d) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    Text(
                                        if (isSelected) "${d.nombre}  - actual" else d.nombre,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    Text(
                                        "${d.tipoNombre}  -  ${d.androidId.take(8)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDispositivoDialog = false }) {
                    Text("Cerrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { retryTrigger++ }, enabled = !loadingDispositivos) {
                    Text("Reintentar")
                }
            },
        )
    }
}

// ====================================================================
// Componentes locales
// ====================================================================

/**
 * Card de configuracion. Titulo + subtitulo en la cabecera, contenido
 * arbitrario debajo. Visual consistente con Material 3 Cards.
 */
@Composable
private fun ConfigCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Row de configuracion: icono + titulo + subtitulo + trailing opcional.
 * Toda la fila es clickeable (si onClick != null). Visual similar a
 * Material 3 ListItem.
 */
@Composable
private fun ConfigRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val mod = if (onClick != null) {
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
    } else {
        Modifier.fillMaxWidth()
    }
    val rowContent: @Composable RowScope.() -> Unit = {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = mod,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = rowContent,
            )
        }
    } else {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = rowContent,
        )
    }
}
