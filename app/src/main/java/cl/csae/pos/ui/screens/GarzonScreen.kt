package cl.csae.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cl.csae.pos.data.api.ValidarTicketResponse
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.UsuarioPos
import cl.csae.pos.ui.components.CambiarModoTopBar
import cl.csae.pos.ui.components.topBarColorsFor
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Pantalla del modo GARZON (sprint 3.2).
 *
 * Requiere que el usuario este logueado. El nav host (AppNavHost) ya
 * enrutiza login_garzon -> garzon, asi que esta pantalla solo se encarga
 * del escaneo de QR + validacion / confirmacion de tickets.
 *
 * Flujo:
 *   1. Camara fullscreen con overlay para escanear QR.
 *   2. Al detectar un QR, llama a TicketValidarRepository.validar(qrToken).
 *   3. Muestra el resultado en un dialog:
 *      - "Valido" (verde): nombre, RUT, servicio, hora, boton "CONFIRMAR CONSUMO"
 *      - "Ya consumido" (rojo): muestra cuando se consumio
 *      - "No encontrado" (rojo)
 *   4. Al confirmar, llama a TicketValidarRepository.consumir(qrToken). Muestra
 *      "Consumido" por 2s y vuelve al escaner.
 */
private const val TAG = "GarzonScreen"

@OptIn(ExperimentalMaterial3Api::class, androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun GarzonScreen(
    usuario: UsuarioPos,
    onCambiarModo: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var lastScannedToken by remember { mutableStateOf<String?>(null) }
    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }
    var processing by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Tras mostrar exito 2s, volver al escaner.
    LaunchedEffect(successMessage) {
        if (successMessage != null) {
            delay(2000)
            successMessage = null
            lastScannedToken = null
        }
    }
    // Mostrar error y volver a escanear.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(2500)
            errorMessage = null
            lastScannedToken = null
        }
    }

    Scaffold(
        topBar = {
            CambiarModoTopBar(
                title = "Garzon",
                subtitle = "Escaner QR - ${usuario.displayName}",
                onCambiarModo = onCambiarModo,
                onLogout = onLogout,
                colors = topBarColorsFor(Color(0xFF6D4C41)),  // cafe del modo garzon
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (hasCameraPermission) {
                // CameraX preview + ML Kit analyzer.
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analyzer = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                val scanner = BarcodeScanning.getClient(
                                    BarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build(),
                                )
                                val executor = Executors.newSingleThreadExecutor()
                                analyzer.setAnalyzer(executor) { imageProxy ->
                                    procesarFrame(imageProxy, scanner) { raw ->
                                        if (raw != lastScannedToken && !processing && dialogState is DialogState.None && successMessage == null && errorMessage == null) {
                                            lastScannedToken = raw
                                            processing = true
                                            scope.launch {
                                                val r = ServiceLocator.ticketValidarRepo.validar(raw)
                                                processing = false
                                                r.onSuccess { resp ->
                                                    dialogState = DialogState.Resultado(resp)
                                                }.onFailure { e ->
                                                    dialogState = DialogState.Resultado(
                                                        ValidarTicketResponse(
                                                            valido = false,
                                                            mensaje = e.message ?: "Error validando ticket",
                                                            ticket = null,
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analyzer,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera init failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Overlay UI encima de la camara
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Sprint 3.2.1: el header (Garzon name + Salir) lo movimos
                    // al CambiarModoTopBar de arriba. Dejamos solo el texto
                    // guia y los mensajes de estado.
                    Spacer(Modifier.height(8.dp))

                    // Marco de enfoque + texto guia
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Apunta al QR del ticket del comensal",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    // Estado / mensaje
                    successMessage?.let { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    } ?: run {
                        errorMessage?.let { msg ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Error, contentDescription = null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text(msg, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        } ?: run {
                            if (processing) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Validando QR...", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Sin permiso de camara
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("Se necesita permiso de camara para escanear.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Conceder permiso")
                    }
                }
            }
        }
    }

    // Dialog de resultado
    when (val state = dialogState) {
        is DialogState.Resultado -> {
            ResultadoValidacionDialog(
                response = state.response,
                onConfirm = {
                    val tk = state.response.ticket
                    if (tk != null) {
                        scope.launch {
                            val r = ServiceLocator.ticketValidarRepo.consumir(tk.qrToken)
                            dialogState = DialogState.None
                            r.onSuccess { resp ->
                                if (resp.ok) {
                                    successMessage = "OK Consumido: ${tk.numero}"
                                } else {
                                    errorMessage = "Error: ${resp.mensaje}"
                                }
                            }.onFailure { e ->
                                errorMessage = "Error: ${e.message}"
                            }
                            // Limpiar scanned token para que pueda escanear otro.
                            lastScannedToken = null
                        }
                    } else {
                        dialogState = DialogState.None
                        lastScannedToken = null
                    }
                },
                onDismiss = {
                    dialogState = DialogState.None
                    lastScannedToken = null
                },
            )
        }
        DialogState.None -> { /* nada */ }
    }
}

private sealed class DialogState {
    data class Resultado(val response: ValidarTicketResponse) : DialogState()
    data object None : DialogState()
}

@Composable
private fun ResultadoValidacionDialog(
    response: ValidarTicketResponse,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = response.ticket
    val valido = response.valido && t != null
    val colorFondo = if (valido) Color(0xFF1B5E20) else Color(0xFFB00020)
    val icono = if (valido) Icons.Filled.CheckCircle else Icons.Filled.Error

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icono, contentDescription = null, tint = colorFondo) },
        title = {
            Text(
                when {
                    valido -> "Valido"
                    t != null && !t.consumidoEnUtc.isNullOrBlank() -> "Ya consumido"
                    else -> "No encontrado"
                },
                color = colorFondo,
            )
        },
        text = {
            Column {
                Text(response.mensaje, style = MaterialTheme.typography.bodyMedium)
                t?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Nombre: ${it.comensalNombre}", fontWeight = FontWeight.SemiBold)
                    Text("RUT: ${it.comensalRut}")
                    Text("Servicio: ${it.servicioNombre}")
                    Text("N° ${it.numero}", style = MaterialTheme.typography.bodySmall)
                    if (!it.consumidoEnUtc.isNullOrBlank()) {
                        Text(
                            "Consumido: ${it.consumidoEnUtc}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (valido) {
                Button(onClick = onConfirm) {
                    Text("CONFIRMAR CONSUMO")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
        dismissButton = {
            if (valido) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

/**
 * Procesa un frame de la camara con ML Kit y entrega el primer QR
 * encontrado al callback [onQr]. Funcion separada para poder anotar
 * @OptIn(ExperimentalGetImage) a nivel de funcion, ya que lint no propaga
 * la opt-in dentro de lambdas inline.
 */
@OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun procesarFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQr: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    val raw = b.rawValue
                    if (raw != null) {
                        onQr(raw)
                        break  // Solo procesamos el primer QR
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Barcode scan failed", e)
            }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}
