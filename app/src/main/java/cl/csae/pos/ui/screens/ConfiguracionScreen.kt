package cl.csae.pos.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cl.csae.pos.BuildConfig
import cl.csae.pos.data.api.DispositivoPosDto
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.ui.components.CambiarModoTopBar
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Pantalla de Configuracion del dispositivo (sprint 3.2).
 *
 * Secciones:
 *   - Modo preferido del dispositivo: TOTEM / POS / GARZON (radio buttons).
 *     Al cambiar, guarda en `AuthStore.modoPreferido` y al reabrir la app va
 *     directo a ese modo.
 *   - Impresora Bluetooth: boton "Seleccionar impresora" (abre el listado
 *     de dispositivos emparejados via BluetoothAdapter), muestra MAC +
 *     nombre guardados, y boton "Imprimir ticket de prueba".
 *   - Version: muestra versionName + versionCode del APK.
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modoPreferido by remember { mutableStateOf<String?>(null) }
    var macImpresora by remember { mutableStateOf<String?>(null) }
    var nombreImpresora by remember { mutableStateOf<String?>(null) }
    var showPrinterDialog by remember { mutableStateOf(false) }
    var printStatus by remember { mutableStateOf<String?>(null) }
    var printing by remember { mutableStateOf(false) }
    var permDeniedSnack by remember { mutableStateOf(false) }
    // F17: estado del boton "Refrescar tema del casino". Permite al operador
    // ver cambios de color/logo sin desloguearse.
    var refreshingTheme by remember { mutableStateOf(false) }
    // F19: estado del selector de dispositivo POS. La lista se carga al abrir
    // la pantalla y se muestra en un dialog (patron igual a la impresora BT).
    var dispositivos by remember { mutableStateOf<List<DispositivoPosDto>>(emptyList()) }
    var loadingDispositivos by remember { mutableStateOf(false) }
    var showDispositivoDialog by remember { mutableStateOf(false) }
    val dispositivoActual by ServiceLocator.dispositivoPosActual.current
        .collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }

    // Cargar estado inicial
    LaunchedEffect(Unit) {
        modoPreferido = ServiceLocator.authStore.getModoPreferido()
        macImpresora = ServiceLocator.authStore.getImpresoraMac()
        // El nombre lo recuperamos del dispositivo emparejado actual.
    }

    // F19: cada vez que se abre el dialog, recarga la lista de dispositivos
    // del casino actual. Asi si el admin agrego uno nuevo, el operador lo ve.
    // F20 (2026-08-12): ademas del flag, agregamos un `retryTrigger` para
    // poder reintentar manualmente desde un boton en el dialog.
    var retryTrigger by remember { mutableStateOf(0) }
    fun cargarDispositivos() {
        loadingDispositivos = true
        dispositivos = emptyList()
        scope.launch {
            try {
                // Incluimos inactivos para que el operador vea el universo
                // completo de dispositivos del casino y pueda pedirle al
                // admin que reactive uno si es necesario. El backend
                // filtra por restaurante del JWT.
                val restauranteId = ServiceLocator.authStore.restauranteId.firstOrNull()
                android.util.Log.d("CsaeConfig", "GET /api/v1/dispositivos-pos?incluirInactivos=true (restauranteId del JWT=$restauranteId)")
                val resp = ServiceLocator.posApiService.listarDispositivos(incluirInactivos = true)
                android.util.Log.d("CsaeConfig", "  -> HTTP ${resp.code()}, body size=${resp.body()?.size ?: "null"}")
                if (resp.isSuccessful) {
                    val raw = resp.body().orEmpty()
                    android.util.Log.d("CsaeConfig", "  total raw=${raw.size}, activos=${raw.count { it.activo }}")
                    if (raw.isEmpty()) {
                        android.util.Log.w("CsaeConfig", "  Lista vacia del backend. Posibles causas: (a) el restaurante del JWT ($restauranteId) no tiene dispositivos registrados, (b) el admin del casino no ha creado dispositivos, (c) el JWT apunta a otro restaurante. Verificar con: GET /api/v1/dispositivos-pos con el token del admin web para ver TODOS los dispositivos.")
                    } else {
                        // Log de los primeros 3 para que se vea en logcat que hay datos.
                        raw.take(3).forEach { d ->
                            android.util.Log.d("CsaeConfig", "  - ${d.nombre} (${d.tipoNombre}, restauranteId=${d.restauranteId}, activo=${d.activo})")
                        }
                    }
                    dispositivos = raw.filter { it.activo }
                } else {
                    val err = resp.errorBody()?.string() ?: "sin body"
                    android.util.Log.e("CsaeConfig", "Error HTTP ${resp.code()}: $err")
                    snackbar.showSnackbar("Error al cargar dispositivos (HTTP ${resp.code()}): $err")
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

    val (versionName, versionCode) = remember {
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (pInfo.versionName ?: BuildConfig.VERSION_NAME) to (pInfo.versionCode?.toLong() ?: BuildConfig.VERSION_CODE.toLong())
        } catch (e: PackageManager.NameNotFoundException) {
            BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong()
        }
    }

    fun setModo(modo: String?) {
        modoPreferido = modo
        scope.launch { ServiceLocator.authStore.setModoPreferido(modo) }
    }

    fun selectImpresora(device: BluetoothDevice) {
        macImpresora = device.address
        val nombre = try { device.name } catch (_: SecurityException) { null } ?: device.address
        nombreImpresora = nombre
        scope.launch { ServiceLocator.authStore.setImpresora(device.address, nombre) }
        showPrinterDialog = false
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

    fun imprimirPrueba() {
        val mac = macImpresora
        if (mac == null) {
            printStatus = "Selecciona una impresora primero."
            return
        }
        printing = true
        printStatus = "Conectando..."
        scope.launch {
            // F4: con el SDK vendor hay que conectar ANTES de imprimir (antes
            // el PrinterService abria el socket por cada operacion). Conectar
            // es idempotente: si ya esta conectado, retorna success.
            val conn = ServiceLocator.printerService.connectBtPort(mac)
            if (conn.isFailure) {
                printing = false
                printStatus = "Error conectando: ${conn.exceptionOrNull()?.message}"
                return@launch
            }
            printStatus = "Imprimiendo prueba..."
            val r = ServiceLocator.printerService.imprimirPrueba()
            printing = false
            r.onSuccess { printStatus = "OK: prueba enviada a ${nombreImpresora ?: mac}." }
             .onFailure { printStatus = "Error: ${it.message}" }
        }
    }

    /**
     * F19: el operador eligio un dispositivo del dialog. Persiste en DataStore
     * via DispositivoPosActual (que ya dispara el StateFlow y notifica a la UI).
     */
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

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showPrinterDialog = true
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

    LaunchedEffect(printStatus) {
        if (printStatus != null) {
            snackbar.showSnackbar(printStatus!!)
            printStatus = null
        }
    }

    /**
     * F17: descarga el casino actual desde el API y guarda colores/logo en
     * AuthStore. El Flow `currentCasinoTheme` emite el nuevo valor, y el
     * `CsaePosTheme` del NavHost recompone con el color/logo actualizado.
     */
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Seccion 1: Modo preferido
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Modo preferido del dispositivo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Al abrir la app ira directo a este modo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    listOf("TOTEM", "POS", "GARZON").forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = modoPreferido == m,
                                onClick = { setModo(m) },
                            )
                            Text(
                                when (m) {
                                    "TOTEM" -> "Tótem (kiosko self-service)"
                                    "POS" -> "POS (operador atiende)"
                                    "GARZON" -> "Garzón (escanea QR)"
                                    else -> m
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    TextButton(onClick = { setModo(null) }) {
                        Text("Quitar modo por defecto (mostrar selector al abrir)")
                    }
                }
            }

            // Seccion 2: Impresora Bluetooth
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Impresora Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (macImpresora == null) "No hay impresora seleccionada."
                        else "Impresora: ${nombreImpresora ?: "?"}\nMAC: $macImpresora",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pedirPermisoYMostrarDialogo() }) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Seleccionar impresora")
                        }
                        OutlinedButton(onClick = { imprimirPrueba() }, enabled = !printing && macImpresora != null) {
                            Icon(Icons.Filled.Print, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (printing) "Enviando..." else "Imprimir prueba")
                        }
                    }
                }
            }

            // Seccion 3: Tema del casino (F17)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tema del casino", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Si el admin del casino cambio el color o el logo, presiona aqui para ver el cambio sin desloguearte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    OutlinedButton(onClick = { refrescarTema() }, enabled = !refreshingTheme) {
                        Text(if (refreshingTheme) "Actualizando..." else "Refrescar tema")
                    }
                }
            }

            // F3 (2026-08-13): Seccion 4: Sucursal activa. Muestra la sucursal
            // actual (o "Casino completo" si no hay) y un boton para cambiar.
            // Si el casino tiene <=1 sucursales, la card se oculta (no hay nada
            // que elegir).
            val sucursalActualId by ServiceLocator.authStore.sucursalId
                .collectAsState(initial = null)
            val sucursales by ServiceLocator.authRepo.sucursalesDisponibles.collectAsState()
            if (sucursales.size > 1 || sucursalActualId != null) {
                val sucursalActualNombre = sucursales
                    .firstOrNull { it.id == sucursalActualId }
                    ?.nombre
                    ?: "Casino completo"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sucursal activa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "En que sucursal del casino operas. El catalog (comensales, servicios) se re-baja al cambiar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            sucursalActualNombre,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (sucursales.size > 1) {
                            OutlinedButton(onClick = onIrSucursal) {
                                Text("Cambiar sucursal")
                            }
                        }
                    }
                }
            }

            // Seccion 4: Dispositivo POS (F19)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dispositivo POS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Con que dispositivo fisico operas. Si no aparece ninguno, consulta al admin del casino para que lo registre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    val nombreDisp = dispositivoActual?.nombre ?: "Sin asignar"
                    val androidId = dispositivoActual?.codigo
                    Text(
                        if (androidId == null) nombreDisp
                        else "$nombreDisp  (${androidId.take(8)})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(onClick = { showDispositivoDialog = true }) {
                        Text(if (dispositivoActual == null) "Seleccionar dispositivo" else "Cambiar dispositivo")
                    }
                }
            }

            // Seccion 5: Version
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("versionName: $versionName", style = MaterialTheme.typography.bodyMedium)
                    Text("versionCode: $versionCode", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showPrinterDialog) {
        PrinterPickerDialog(
            onDismiss = { showPrinterDialog = false },
            onSelect = { selectImpresora(it) },
        )
    }

    // F19: dialog de seleccion de dispositivo POS. Muestra la lista que se
    // cargo via listarDispositivos() al abrir el dialog. Cada item es un
    // boton con nombre + androidId corto + tipo. Click selecciona y cierra.
    // F20 (2026-08-12): boton "Reintentar" si la carga falla o la lista
    // esta vacia. Loggea con `adb logcat -s CsaeConfig` para debug.
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
                            "Si pensas que deberia haber dispositivos, revisa la conexion o consulta al admin del casino para que los registre en el backoffice.",
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
                                        if (isSelected) "${d.nombre}  *actual*" else d.nombre,
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
