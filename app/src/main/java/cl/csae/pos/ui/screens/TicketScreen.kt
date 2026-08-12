package cl.csae.pos.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cl.csae.pos.data.bluetooth.TicketPdfGenerator
import cl.csae.pos.di.ServiceLocator
import cl.csae.pos.model.Ticket
import cl.csae.pos.ui.components.CambiarModoTopBar
import cl.csae.pos.ui.components.CasinoLogoImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pantalla del ticket generado. Muestra el detalle (preview de lo que iria
 * en el ticket fisico) y 3 acciones:
 *   - Imprimir (Bluetooth ESC/POS sobre dispositivos emparejados).
 *   - Nuevo ticket (vuelve al POS).
 *   - Volver al dashboard.
 *
 * En modo kiosko, despues de 10s sin accion, vuelve automaticamente al POS.
 *
 * Sprint 3.2: si el Ticket tiene qrToken (lo emite el backend en la
 * respuesta de registrar consumo), muestra un QR generado con ZXing y lo
 * envia a la impresora termica via `imprimirTicketConQr`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketScreen(
    ticket: Ticket,
    esKiosko: Boolean,
    onNuevo: () -> Unit,
    onCambiarModo: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var segundosParaSalir by remember { mutableStateOf(10) }
    var showPrinterDialog by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    // Sprint 3.2.1: si la impresion fallo, guardamos el path del PDF generado
    // para que el operador pueda abrirlo desde el Snackbar de accion.
    var pdfFallbackPath by remember { mutableStateOf<String?>(null) }

    // F18.3: el ticket se imprime con el logo del casino en el header.
    // Antes era texto hardcoded ("CSAE POS" / "Casino Salamanca"). Ahora
    // sale del CasinoTheme persistido en el AuthStore al login.
    val casinoTheme by ServiceLocator.authRepo.currentCasinoTheme
        .collectAsState(initial = null)

    // Auto-volver en modo kiosko despues de 10s
    LaunchedEffect(esKiosko) {
        if (esKiosko) {
            while (segundosParaSalir > 0) {
                delay(1000)
                segundosParaSalir--
            }
            onNuevo()
        }
    }

    // Guardar el ticket en el cache al mostrarlo (asi aparece en el dashboard).
    LaunchedEffect(ticket.numero) {
        ServiceLocator.ticketCache.agregar(ticket)
    }

    // Launcher de permiso BLUETOOTH_CONNECT (Android 12+)
    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showPrinterDialog = true
        else scope.launch { snackbar.showSnackbar("Sin permiso de Bluetooth no se puede imprimir.") }
    }

    fun pedirPermisoYMostrarDialogo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) showPrinterDialog = true
            else btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            showPrinterDialog = true
        }
    }

    Scaffold(
        topBar = {
            CambiarModoTopBar(
                title = "Ticket generado",
                subtitle = "N° ${ticket.numero}",
                onCambiarModo = onCambiarModo,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Confirmacion grande
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Listo!",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Preview del ticket — Sprint F6 (2026-08-11): rediseñado para
            // parecerse a un papel termico 58mm. Antes era un Card con
            // elevation 4, separadores "---" en texto plano y fuentes
            // variables (sans-serif Material). Ahora: Surface blanco plano
            // con borde gris claro, fuente Monospace (estilo impresora
            // termica), HorizontalDivider para las separaciones, label/valor
            // en Row con el precio right-aligned, y un poco mas angosto
            // (max 280dp en vez de 360dp, ~58mm a densidad hdpi).
            val qrToken = ticket.qrToken
                ?: "CSAE-${ticket.numero}-${ticket.comensal.rut}"
            val qrBitmap = remember(qrToken) {
                ServiceLocator.printerService.generarQrBitmap(qrToken, sizePx = 320)
            }
            // Fix tickets preview (2026-08-12): rediseño del Surface del
            // ticket termico. Cambios:
            // - maxWidth 280 -> 320dp (un poco mas ancho, mejor en tablets 10"
            //   sin perder el ratio de 58mm termico).
            // - border 1dp #D0D0D0 -> 1.5dp #999999 (visible sobre fondos
            //   claros, no se pierde).
            // - shadowElevation 2 -> 6dp (drop shadow notable, el ticket
            //   "flota" sobre el fondo gris del Scaffold).
            // - padding interno 14/10 -> 20/16dp (mas aire, no se ve apretado).
            // - logo 120x48 -> 160x64dp (mas visible en el header).
            // - colores secundarios #777/#333 -> mas oscuros para contraste.
            // - QR 160dp -> 180dp (mas facil de escanear desde la pantalla).
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                color = Color.White,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.5.dp, Color(0xFF999999)),
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    // F18.3: header del casino con logo (si tiene) o texto.
                    // El logo del casino arriba + nombre debajo. Si no hay
                    // logo, CasinoLogoImage cae al csae_logo y el nombre
                    // sigue apareciendo (de casinoTheme o default "CSAE POS").
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CasinoLogoImage(
                            logoUrl = casinoTheme?.logoUrl,
                            contentDescription = casinoTheme?.razonSocial ?: "CSAE",
                            modifier = Modifier.size(width = 160.dp, height = 64.dp),
                        )
                    }
                    val casinoNombre = casinoTheme?.razonSocial ?: "CSAE POS"
                    Text(
                        casinoNombre,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.Black,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    DivThin()
                    Text(
                        ticket.fechaHora,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.Black,
                    )
                    Text(
                        "Ticket: ${ticket.numero}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.Black,
                    )
                    DivThin()

                    // Comensal
                    Text(
                        "${ticket.comensal.nombre} ${ticket.comensal.apellido ?: ""}".trim(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.Black,
                    )
                    Text(
                        "RUT: ${ticket.comensal.rut}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.Black,
                    )
                    if (ticket.comensal.empresa.isNotEmpty()) {
                        Text(
                            ticket.comensal.empresa,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF444444),
                        )
                    }
                    DivThin()

                    // Servicio + precio (label/valor)
                    Text(
                        "Servicio: ${ticket.servicio.nombre}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.Black,
                    )
                    Text(
                        "Tipo: ${ticket.servicio.tipo}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF333333),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "TOTAL",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.Black,
                            modifier = Modifier.weight(1f),
                        )
                        val precioFinal = ticket.precio.takeIf { it > 0 } ?: ticket.servicio.precio
                        Text(
                            "$$precioFinal",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color.Black,
                        )
                    }
                    DivThin()

                    // Operador
                    Text(
                        "Operador: ${ticket.operador}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF555555),
                    )

                    // QR (si hay)
                    qrBitmap?.let { bmp ->
                        DivThin()
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR del ticket",
                                modifier = Modifier.size(180.dp),
                            )
                        }
                        Text(
                            "Escanea para validar",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF555555),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    // Linea punteada final estilo "corte" de impresora termica
                    DashedDivider()
                }
            }

            // Botones de accion
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { pedirPermisoYMostrarDialogo() },
                    enabled = !printing,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Imprimir")
                }
                Button(
                    onClick = onNuevo,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("Nuevo")
                }
            }

            if (esKiosko) {
                Text(
                    "Volviendo al POS en ${segundosParaSalir}s...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showPrinterDialog) {
        PrinterPickerDialog(
            onDismiss = { showPrinterDialog = false },
            onSelect = { device ->
                showPrinterDialog = false
                printing = true
                scope.launch {
                    // Sprint 3.2: usar imprimirTicketConQr con el qrToken real
                    // del backend (en ticket.qrToken). Fallback al sintetico si
                    // el ticket no tiene qrToken (tickets pre-Sprint-3.2).
                    val qrToken = ticket.qrToken
                        ?: "CSAE-${ticket.numero}-${ticket.comensal.rut}"
                    // Regeneramos el QR para el PDF con sizePx fijo.
                    val qrBitmap = ServiceLocator.printerService.generarQrBitmap(qrToken, sizePx = 512)
                    val r = ServiceLocator.printerService.imprimirTicketConQr(
                        deviceAddress = device.address,
                        ticket = ticket,
                        qrToken = qrToken,
                    )
                    printing = false
                    r.onSuccess {
                        // Marcamos el ticket como impreso SOLO si la impresion
                        // fisica salio OK. Esto evita estados inconsistentes.
                        ticket.ticketId?.let { id ->
                            ServiceLocator.consumoRepo.marcarImpreso(id, device.address)
                        }
                        snackbar.showSnackbar("Impreso OK en ${device.name ?: device.address}.")
                    }.onFailure { err ->
                        // Sprint 3.2.1: fallback a PDF cuando la impresion
                        // fisica falla. Genera un PDF del ticket con el QR
                        // para que el operador pueda abrirlo / compartirlo.
                        val pdfFile = try {
                            TicketPdfGenerator.generarPdf(
                                context = context,
                                ticket = ticket,
                                qrBitmap = qrBitmap,
                            )
                        } catch (e: Exception) {
                            null
                        }
                        if (pdfFile != null) {
                            pdfFallbackPath = pdfFile.absolutePath
                            snackbar.showSnackbar(
                                "Impresion fallo. PDF guardado en: ${pdfFile.name}",
                            )
                        } else {
                            snackbar.showSnackbar(
                                "Impresion fallo: ${err.message}. No se pudo generar PDF.",
                            )
                        }
                    }
                }
            },
        )
    }

    // Sprint 3.2.1: si hay un PDF generado por fallback, mostrar card con
    // boton "Abrir PDF" para que el operador pueda abrirlo / compartirlo.
    pdfFallbackPath?.let { path ->
        AlertDialog(
            onDismissRequest = { pdfFallbackPath = null },
            icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
            title = { Text("Impresion fallo") },
            text = {
                Column {
                    Text("La impresora no respondio. El ticket se guardo como PDF para entrega manual.")
                    Spacer(Modifier.height(8.dp))
                    Text(path, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = File(path)
                    if (file.exists()) {
                        val uri: Uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                }) {
                    Text("Abrir PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { pdfFallbackPath = null }) {
                    Text("Cerrar")
                }
            },
        )
    }
}

@Composable
fun PrinterPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (BluetoothDevice) -> Unit,
) {
    val emparejados = remember { ServiceLocator.printerService.listarEmparejados() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar impresora") },
        text = {
            if (emparejados.isEmpty()) {
                Text(
                    "No hay impresoras Bluetooth emparejadas. Ve a Settings > Bluetooth del dispositivo y empareja tu impresora 58mm.",
                )
            } else {
                Column {
                    emparejados.forEach { d ->
                        val nombre = try { d.name } catch (_: SecurityException) { null } ?: d.address
                        TextButton(
                            onClick = { onSelect(d) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(nombre, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}

/**
 * Sprint F6 (2026-08-11): separador de linea fina estilo "punteado" para
 * el preview del ticket termico. Reemplaza al viejo `Text("---")` y al
 * HorizontalDivider default de Material3, que es muy grueso y azul
 * (tema). Acá usamos gris medio (#999999) con 1dp de grosor y un
 * padding vertical de 4dp, igual al espacio entre lineas de una
 * impresora termica 58mm.
 *
 * Fix tickets preview (2026-08-12): el color subio de #D0D0D0 a #999999
 * para que el separador sea visible (antes se perdia contra el border
 * del Surface y el fondo blanco). Padding reducido de 6 a 4dp para que
 * el ticket no se vea "ventilado" en exceso.
 */
@Composable
private fun DivThin() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color(0xFF999999),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * Fix tickets preview (2026-08-12): linea punteada al final del ticket
 * (estilo "------------" de las impresoras termicas antes del corte).
 * Mas fina y sutil que DivThin para que no compita con el resto del
 * contenido.
 */
@Composable
private fun DashedDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color(0xFFAAAAAA),
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
