package cl.csae.pos.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import cl.csae.pos.model.Ticket
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.posprinter.posprinterface.IMyBinder
import net.posprinter.posprinterface.ProcessData
import net.posprinter.posprinterface.UiExecute
import net.posprinter.utils.BitmapToByteData
import net.posprinter.utils.DataForSendToPrinterPos80
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PrinterService: impresion Bluetooth ESC/POS via SDK vendor (PPT305BT).
 *
 * **F4 (2026-08-13):** migrado del ESC/POS custom (que escribia bytes raw
 * via `BluetoothSocket.outputStream`) al SDK vendor `net.posprinter`. El
 * SDK expone un AIDL `IMyBinder` que se obtiene via `bindService()` a
 * `net.posprinter.service.PosprinterService` (declarado en el manifest).
 * El SDK se encarga de la conexion Bluetooth, la generacion de bytes
 * ESC/POS (init, alignment, font size, QR codes, bitmaps), el corte de
 * papel, etc.
 *
 * **Por que migrar al SDK vendor:**
 * 1. La PPT305BT tiene quirks especificos (bitmap printing, QR encoding,
 *    corte de papel) que el SDK ya maneja. Nuestra implementacion custom
 *    tenia bugs sutiles (ej: el QR no funcionaba en algunos modelos).
 * 2. El SDK soporta USB + Net + Bluetooth con la misma API, asi que
 *    despues podemos soportar impresora por red sin cambiar codigo.
 * 3. El vendor mantiene el SDK actualizado con fixes para nuevos modelos.
 *
 * **API vendor (JAR `posprinterconnectandsendsdk.jar`):**
 * - `IMyBinder.connectBtPort(mac, UiExecute)` — conectar por Bluetooth.
 * - `IMyBinder.disconnectCurrentPort(UiExecute)` — desconectar.
 * - `IMyBinder.write(byte[], UiExecute)` — escribir bytes raw.
 * - `IMyBinder.writeDataByYouself(UiExecute, ProcessData)` — escribir
 *   una lista de byte arrays (mas conveniente para el ticket).
 * - `IMyBinder.acceptdatafromprinter(UiExecute)` — callback cuando la
 *   impresora se desconecta (cable BT fuera de rango, etc).
 *
 * **Patron de uso:**
 * ```kotlin
 * printer.connectBtPort(mac)            // Result<Unit>
 * printer.imprimirTicket(ticket)        // Result<Unit> (internamente
 *                                          // llama connect si no esta)
 * printer.disconnect()                  // Result<Unit>
 * ```
 *
 * **Permisos requeridos (AndroidManifest):**
 * - BLUETOOTH (max SDK 30)
 * - BLUETOOTH_CONNECT (Android 12+)
 * - BLUETOOTH_SCAN (Android 12+, noForLocation)
 */
class PrinterService(private val context: Context) {

    @Volatile private var binder: IMyBinder? = null
    private val bindingReady = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    /**
     * Bindea el [net.posprinter.service.PosprinterService] del SDK vendor.
     * El service ya esta declarado en el manifest del proyecto
     * (`<service android:name="net.posprinter.service.PosprinterService"/>`)
     * asi que solo necesitamos hacer el bind. Una vez bindeado, `binder`
     * queda con la instancia del AIDL.
     *
     * **Pitfall F4.1 (resuelto):** al armar el `Intent` NO usar
     * `ComponentName("net.posprinter.service", ...)` con el package del
     * class, porque Android busca el Service en el APK que tenga ese
     * `applicationId` (y el nuestro es `cl.csae.pos`, no
     * `net.posprinter.service`). El Service, aunque la class esta en
     * el package `net.posprinter.service`, pertenece al APK actual
     * (applicationId = cl.csae.pos) porque esta en `app/libs/` y se
     * compila dentro de classes.dex.
     *
     * **Pitfall F4.2 (resuelto):** tampoco usar
     * `Class.forName("net.posprinter.service.PosprinterService")` +
     * `Intent(context, serviceClass)`. Esto funciona en la mayoria de
     * devices pero algunos (Xiaomi, Samsung con One UI 6+) tienen
     * optimizaciones que hacen que el cast del Class<*> al
     * `Class<*>` del Intent falle en runtime, dejando el Intent sin
     * component y `bindService` retornando false. El bug se manifiesta
     * como "No se pudo hacer bind del PosprinterService" o como
     * "Impresora no conectada" si el `onServiceConnected` se completa
     * con un IBinder null.
     *
     * **Fix definitivo:** usar `Intent().setClassName(ctx, FQN)`. Es
     * la forma que el demo oficial del vendor usa (con
     * `setClassName(this, "net.posprinter.service.PosprinterService")`)
     * y NO depende de la carga de la class en el classpath antes del
     * bind, porque Android resuelve el component por FQN en runtime
     * via PackageManager.
     */
    private suspend fun ensureBound(): IMyBinder {
        binder?.let { return it }
        return suspendCancellableCoroutine { cont ->
            // F4.2: usar setClassName con FQN (no ComponentName, no Class.forName).
            val intent = Intent().apply {
                setClassName(
                    context,
                    "net.posprinter.service.PosprinterService",
                )
            }
            Log.d(TAG, "bindService con setClassName FQN=net.posprinter.service.PosprinterService")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(component: ComponentName?, ib: IBinder?) {
                    Log.d(TAG, "onServiceConnected component=$component binderClass=${ib?.javaClass?.name}")
                    val b = ib as? IMyBinder
                    if (b != null) {
                        binder = b
                        bindingReady.set(true)
                        cont.resume(b)
                    } else {
                        cont.resumeWithException(IllegalStateException(
                            "Binder no es IMyBinder. Tipo real: ${ib?.javaClass?.name}"
                        ))
                    }
                }
                override fun onServiceDisconnected(component: ComponentName?) {
                    Log.w(TAG, "PosprinterService disconnected")
                    binder = null
                    bindingReady.set(false)
                }
            }
            val bound = try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.e(TAG, "bindService SecurityException: ${e.message}")
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            } catch (e: Exception) {
                Log.e(TAG, "bindService Exception: ${e.message}", e)
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            Log.d(TAG, "bindService retorno=$bound (true=ok, false=no encontrado)")
            if (!bound) {
                cont.resumeWithException(IllegalStateException(
                    "No se pudo hacer bind del PosprinterService (returned false). " +
                        "Posibles causas: (a) el service no esta declarado en el manifest, " +
                        "(b) el manifest dice 'exported=false' y otro proceso intenta acceder, " +
                        "(c) el SDK vendor no se cargo correctamente, " +
                        "(d) el package del FQN no coincide con el applicationId."
                ))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                try { context.unbindService(conn) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Lista los dispositivos Bluetooth ya emparejados (no requiere el SDK
     * vendor, usamos BluetoothAdapter directo del sistema).
     */
    @SuppressLint("MissingPermission")
    fun listarEmparejados(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { return emptyList() }
        return bonded?.toList() ?: emptyList()
    }

    /**
     * Conecta a la impresora Bluetooth via el SDK vendor.
     * Si ya esta conectado, retorna success sin re-conectar.
     */
    suspend fun connectBtPort(macAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (macAddress.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("MAC vacio."))
        }
        if (!connecting.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Ya hay una conexion en curso."))
        }
        try {
            Log.d(TAG, "connectBtPort: mac=$macAddress, ensureBound()...")
            val b = ensureBound()
            Log.d(TAG, "connectBtPort: binder listo, llamando connectBtPort del SDK...")
            val connected = suspendCancellableCoroutine<Unit> { cont ->
                b.connectBtPort(macAddress, object : UiExecute {
                    override fun onsucess() {
                        Log.d(TAG, "connectBtPort: SDK retorno onsucess")
                        cont.resume(Unit)
                    }
                    override fun onfailed() {
                        Log.e(TAG, "connectBtPort: SDK retorno onfailed")
                        cont.resumeWithException(IllegalStateException(
                            "No se pudo conectar a $macAddress. Verifica que este emparejada y encendida."
                        ))
                    }
                })
            }
            Result.success(connected)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException(
                "Sin permiso BLUETOOTH_CONNECT. Activalo en Settings del dispositivo."
            ))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connecting.set(false)
        }
    }

    /**
     * Desconecta la impresora actual. Si no esta conectado, es no-op.
     */
    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        val b = binder ?: return@withContext Result.success(Unit)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                b.disconnectCurrentPort(object : UiExecute {
                    override fun onsucess() { cont.resume(Unit) }
                    override fun onfailed() { cont.resume(Unit) }  // best-effort
                })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Envia un ticket al printer (asume que ya esta conectado).
     * Si no esta conectado, retorna error.
     */
    suspend fun imprimirTicket(ticket: Ticket, qrToken: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val b = binder ?: return@withContext Result.failure(
            IllegalStateException("Impresora no conectada. Llama a connectBtPort() primero.")
        )
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                b.writeDataByYouself(object : UiExecute {
                    override fun onsucess() { cont.resume(Unit) }
                    override fun onfailed() { cont.resumeWithException(IllegalStateException(
                        "La impresora rechazo el ticket. Verifica papel y conexion."
                    )) }
                }, object : ProcessData {
                    override fun processDataBeforeSend(): List<ByteArray> {
                        return buildTicketCommands(ticket, qrToken)
                    }
                })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imprime un ticket de prueba: "CSAE POS - PRUEBA / Si lees esto, la
     * impresora esta OK." Usado desde Configuracion.
     *
     * F4.2: si el `binder` es null (porque el Service se desconecto
     * entre el `connectBtPort` y esta llamada), intenta auto-rebind
     * antes de retornar "Impresora no conectada". Esto cubre el caso
     * edge donde el sistema mata el Service por presion de memoria
     * justo despues de conectar.
     */
    suspend fun imprimirPrueba(): Result<Unit> = withContext(Dispatchers.IO) {
        var b = binder
        if (b == null) {
            Log.w(TAG, "imprimirPrueba: binder null, intentando ensureBound() de nuevo...")
            val rebind = runCatching { ensureBound() }
            if (rebind.isFailure) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Impresora no conectada. ensureBound() fallo: ${rebind.exceptionOrNull()?.message}",
                        rebind.exceptionOrNull(),
                    )
                )
            }
            b = rebind.getOrNull()
        }
        if (b == null) {
            return@withContext Result.failure(IllegalStateException("Impresora no conectada (binder null despues de rebind)."))
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                b.writeDataByYouself(object : UiExecute {
                    override fun onsucess() { cont.resume(Unit) }
                    override fun onfailed() { cont.resumeWithException(IllegalStateException(
                        "La impresora rechazo la prueba."
                    )) }
                }, object : ProcessData {
                    override fun processDataBeforeSend(): List<ByteArray> {
                        val list = ArrayList<ByteArray>()
                        list.add(DataForSendToPrinterPos80.initializePrinter())
                        list.add(DataForSendToPrinterPos80.selectAlignment(1))
                        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x11))  // doble
                        list.add("CSAE POS - PRUEBA\n".toByteArray(Charsets.UTF_8))
                        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x00))  // normal
                        list.add("Si lees esto, la impresora esta OK.\n".toByteArray(Charsets.UTF_8))
                        list.add("\n\n\n".toByteArray(Charsets.UTF_8))
                        list.add(DataForSendToPrinterPos80.selectCutPagerModerAndCutPager(66, 1))
                        return list
                    }
                })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imprime un bitmap (usado por TicketScreen para imprimir el preview
     * del ticket generado, F6). Espera a que la conexion este establecida.
     */
    suspend fun imprimirBitmap(bitmap: Bitmap, width: Int = 576): Result<Unit> = withContext(Dispatchers.IO) {
        val b = binder ?: return@withContext Result.failure(
            IllegalStateException("Impresora no conectada.")
        )
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                b.writeDataByYouself(object : UiExecute {
                    override fun onsucess() { cont.resume(Unit) }
                    override fun onfailed() { cont.resumeWithException(IllegalStateException(
                        "La impresora rechazo el bitmap."
                    )) }
                }, object : ProcessData {
                    override fun processDataBeforeSend(): List<ByteArray> {
                        val list = ArrayList<ByteArray>()
                        list.add(DataForSendToPrinterPos80.initializePrinter())
                        list.add(DataForSendToPrinterPos80.printRasterBmp(
                            0, bitmap, BitmapToByteData.BmpType.Threshold,
                            BitmapToByteData.AlignType.Center, width,
                        ))
                        list.add(DataForSendToPrinterPos80.selectCutPagerModerAndCutPager(66, 1))
                        return list
                    }
                })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Genera un Bitmap QR (monocromatico) para mostrar en pantalla. Se usa
     * en el preview del ticket (TicketScreen). Esto lo seguimos haciendo
     * con ZXing en vez del SDK vendor porque el preview es en pantalla
     * (Bitmap) y no en la impresora.
     */
    fun generarQrBitmap(contenido: String, sizePx: Int = 512): Bitmap? {
        if (contenido.isBlank()) return null
        return try {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Construye la lista de byte arrays (comandos ESC/POS) que representan
     * el ticket. Usa los helpers `DataForSendToPrinterPos80` del SDK vendor.
     */
    private fun buildTicketCommands(ticket: Ticket, qrToken: String?): List<ByteArray> {
        val list = ArrayList<ByteArray>()
        list.add(DataForSendToPrinterPos80.initializePrinter())
        // Header
        list.add(DataForSendToPrinterPos80.selectAlignment(1))  // center
        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x11))  // doble
        list.add("CSAE POS\n".toByteArray(Charsets.UTF_8))
        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x00))
        list.add("${ticket.comensal.empresa}\n".toByteArray(Charsets.UTF_8))
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add(DataForSendToPrinterPos80.selectAlignment(0))  // left
        list.add("${ticket.fechaHora}\n".toByteArray(Charsets.UTF_8))
        list.add("Ticket: ${ticket.numero}\n".toByteArray(Charsets.UTF_8))
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add("Comensal: ${ticket.comensal.nombre} ${ticket.comensal.apellido ?: ""}\n".toByteArray(Charsets.UTF_8))
        list.add("RUT: ${ticket.comensal.rut}\n".toByteArray(Charsets.UTF_8))
        if (ticket.comensal.empresa.isNotEmpty()) {
            list.add("Empresa: ${ticket.comensal.empresa}\n".toByteArray(Charsets.UTF_8))
        }
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add("Servicio: ${ticket.servicio.nombre}\n".toByteArray(Charsets.UTF_8))
        list.add("Tipo: ${ticket.servicio.tipo}\n".toByteArray(Charsets.UTF_8))
        // Precio en grande
        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x11))
        list.add("Precio: $${ticket.precio.takeIf { it > 0 } ?: ticket.servicio.precio}\n".toByteArray(Charsets.UTF_8))
        list.add(DataForSendToPrinterPos80.selectCharacterSize(0x00))
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add("Operador: ${ticket.operador}\n".toByteArray(Charsets.UTF_8))

        // QR via comandos GS ( k del SDK (modelo 2, modulo 4, level M).
        if (!qrToken.isNullOrBlank()) {
            list.add("\n".toByteArray(Charsets.UTF_8))
            list.add(DataForSendToPrinterPos80.selectAlignment(1))  // center
            list.add(DataForSendToPrinterPos80.SetsTheSizeOfTheQRCodeSymbolModule(4))
            list.add(DataForSendToPrinterPos80.SetsTheErrorCorrectionLevelForQRCodeSymbol(0x31))  // M
            list.add(DataForSendToPrinterPos80.StoresSymbolDataInTheQRCodeSymbolStorageArea(qrToken))
            list.add(DataForSendToPrinterPos80.PrintsTheQRCodeSymbolDataInTheSymbolStorageArea())
            list.add("\n".toByteArray(Charsets.UTF_8))
        }

        list.add("\n\n\n".toByteArray(Charsets.UTF_8))
        list.add(DataForSendToPrinterPos80.selectCutPagerModerAndCutPager(66, 1))
        return list
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter
        }

    private companion object {
        const val TAG = "CsaePrinter"
    }
}
