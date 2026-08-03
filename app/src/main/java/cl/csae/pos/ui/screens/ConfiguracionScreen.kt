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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import cl.csae.pos.di.ServiceLocator
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
    onBack: () -> Unit,
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
    val snackbar = remember { SnackbarHostState() }

    // Cargar estado inicial
    LaunchedEffect(Unit) {
        modoPreferido = ServiceLocator.authStore.getModoPreferido()
        macImpresora = ServiceLocator.authStore.getImpresoraMac()
        // El nombre lo recuperamos del dispositivo emparejado actual.
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
        printStatus = "Imprimiendo..."
        scope.launch {
            val r = ServiceLocator.printerService.imprimirPrueba(mac)
            printing = false
            r.onSuccess { printStatus = "OK: prueba enviada a ${nombreImpresora ?: mac}." }
             .onFailure { printStatus = "Error: ${it.message}" }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuracion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
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

            // Seccion 3: Version
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
}
