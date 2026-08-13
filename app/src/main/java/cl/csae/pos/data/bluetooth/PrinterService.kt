package cl.csae.pos.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import cl.csae.pos.model.Ticket
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PrinterService: impresion Bluetooth ESC/POS directa via `BluetoothSocket`.
 *
 * F4.4 (2026-08-13): reversion al patron original que SÍ funciona con la
 * PPT305BT del casino Demo (mismo device, misma MAC DC:0D:30:D5:79:BA).
 * El SDK vendor (`posprinterconnectandsendsdk.jar`) usado en F4 / F4.2 /
 * F4.3 fallaba con `connectBtPort` retornando `onfailed` en este device
 * especifico. La causa mas probable: el SDK usa un UUID RFCOMM interno
 * propio que NO matchea con el UUID SPP estandar
 * (`00001101-0000-1000-8000-00805F9B34FB`) que usan las impresoras
 * ESC/POS genericas como esta PPT305BT.
 *
 * **Patron:** abrir un `BluetoothSocket` directo via SPP standard UUID
 * + enviar comandos ESC/POS raw al `outputStream`. Esto es lo que el
 * commit `da09788` hacia antes de F4 y era 100% funcional.
 *
 * **Permisos requeridos (AndroidManifest):**
 * - BLUETOOTH (max SDK 30)
 * - BLUETOOTH_CONNECT (Android 12+, runtime permission)
 * - BLUETOOTH_SCAN (Android 12+, noForLocation)
 */
class PrinterService(private val context: Context) {

    @Volatile private var socket: BluetoothSocket? = null
    private val connecting = AtomicBoolean(false)

    /**
     * UUID SPP estandar. TODAS las impresoras ESC/POS Bluetooth genéricas
     * (Epson, Star, Xprinter, PPT305BT, etc.) escuchan en este UUID.
     * Usar el UUID SPP es la forma mas compatible; el SDK vendor usaba
     * un UUID interno que no siempre matchea.
     */
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

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
     * Conecta a la impresora Bluetooth via `BluetoothSocket` + UUID SPP.
     * Si ya esta conectado a la misma MAC, retorna success sin re-conectar.
     *
     * F4.4: agregado chequeo de permisos Bluetooth (API 31+) ANTES de
     * intentar la conexion, para fallar rapido con mensaje claro.
     */
    @SuppressLint("MissingPermission")
    suspend fun connectBtPort(macAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (macAddress.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("MAC vacio."))
        }
        // Permiso BLUETOOTH_CONNECT (API 31+ runtime permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.e(TAG, "connectBtPort: sin permiso BLUETOOTH_CONNECT (API 31+)")
                return@withContext Result.failure(IllegalStateException(
                    "Sin permiso BLUETOOTH_CONNECT. Otorgalo en Settings > Apps > " +
                        "CSAE POS > Permisos > Dispositivos cercanos."
                ))
            }
        }
        if (!connecting.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Ya hay una conexion en curso."))
        }
        try {
            Log.d(TAG, "connectBtPort: mac=$macAddress")
            val adapter = bluetoothAdapter ?: return@withContext Result.failure(
                IllegalStateException("Bluetooth no disponible en este dispositivo.")
            )
            val device = try { adapter.getRemoteDevice(macAddress) } catch (e: Exception) {
                return@withContext Result.failure(IllegalStateException("MAC invalida: $macAddress"))
            }
            // Cerrar socket anterior si existe (re-conectar).
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            // Crear socket SPP. API 31+ usa `createInsecureRfcommSocketToServiceRecord`
            // (sin pairing required, equivalente al que el codigo custom anterior usaba).
            val s = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            } else {
                device.createRfcommSocketToServiceRecord(sppUuid)
            }
            Log.d(TAG, "connectBtPort: socket creado, llamando connect() (UUID SPP)...")
            // Pitfall F4.4: `socket.connect()` debe llamarse ANTES de
            // `outputStream`. Si no, el stream no se inicializa.
            s.connect()
            Log.d(TAG, "connectBtPort: connect() OK")
            socket = s
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "connectBtPort: SecurityException: ${e.message}")
            Result.failure(IllegalStateException(
                "Sin permiso BLUETOOTH_CONNECT. Otorgalo en Settings del dispositivo."
            ))
        } catch (e: IOException) {
            Log.e(TAG, "connectBtPort: IOException: ${e.message}", e)
            // Errores tipicos: "Service discovery failed" (UUID no soportado),
            // "Connection refused" (impresora apagada o no emparejada),
            // "Connection timeout" (fuera de rango).
            Result.failure(IllegalStateException(
                "No se pudo conectar a $macAddress: ${e.message ?: "desconocido"}. " +
                    "Verifica: (1) la MAC corresponde a esta impresora (revisa la " +
                    "etiqueta), (2) la impresora esta encendida, (3) esta emparejada " +
                    "en Settings > Bluetooth."
            ))
        } catch (e: Exception) {
            Log.e(TAG, "connectBtPort: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        } finally {
            connecting.set(false)
        }
    }

    /**
     * Desconecta la impresora actual. Si no esta conectado, es no-op.
     */
    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        Result.success(Unit)
    }

    /**
     * Envia un ticket al printer. Asume que ya esta conectado.
     * Si no esta conectado, retorna error.
     */
    suspend fun imprimirTicket(ticket: Ticket, qrToken: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext Result.failure(
            IllegalStateException("Impresora no conectada. Llama a connectBtPort() primero.")
        )
        try {
            val out = s.outputStream
            for (bytes in buildTicketCommands(ticket, qrToken)) {
                out.write(bytes)
            }
            out.flush()
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "imprimirTicket: IOException: ${e.message}", e)
            try { s.close() } catch (_: Exception) {}
            socket = null
            Result.failure(IllegalStateException("Conexion perdida durante impresion: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "imprimirTicket: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Imprime un ticket de prueba: "CSAE POS - PRUEBA". Usado desde
     * Configuracion para verificar que la impresora responde.
     */
    suspend fun imprimirPrueba(): Result<Unit> = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext Result.failure(
            IllegalStateException("Impresora no conectada.")
        )
        try {
            val out = s.outputStream
            for (bytes in buildPruebaCommands()) {
                out.write(bytes)
            }
            out.flush()
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "imprimirPrueba: IOException: ${e.message}", e)
            try { s.close() } catch (_: Exception) {}
            socket = null
            Result.failure(IllegalStateException("Conexion perdida durante prueba: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "imprimirPrueba: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Imprime un bitmap (usado por TicketScreen para imprimir el preview
     * del ticket generado, F6). Espera a que la conexion este establecida.
     */
    suspend fun imprimirBitmap(bitmap: Bitmap, width: Int = 576): Result<Unit> = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext Result.failure(
            IllegalStateException("Impresora no conectada.")
        )
        try {
            val out = s.outputStream
            for (bytes in buildBitmapCommands(bitmap, width)) {
                out.write(bytes)
            }
            out.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "imprimirBitmap: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Genera un Bitmap QR (monocromatico) para mostrar en pantalla. Se usa
     * en el preview del ticket (TicketScreen). NO se envia a la impresora:
     * para el ticket, el QR se manda via comandos GS ( k directamente.
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

    // ================================================================
    // Comandos ESC/POS (manuales, sin SDK vendor)
    // ================================================================

    private fun buildPruebaCommands(): List<ByteArray> {
        val list = ArrayList<ByteArray>()
        list.add(escInit())
        list.add(escAlign(1))  // center
        list.add(escCharSize(0x11))  // doble
        list.add("CSAE POS - PRUEBA\n".toByteArray(Charsets.UTF_8))
        list.add(escCharSize(0x00))  // normal
        list.add("Si lees esto, la impresora esta OK.\n".toByteArray(Charsets.UTF_8))
        list.add("\n\n\n".toByteArray(Charsets.UTF_8))
        list.add(gsCut())
        return list
    }

    private fun buildTicketCommands(ticket: Ticket, qrToken: String?): List<ByteArray> {
        val list = ArrayList<ByteArray>()
        list.add(escInit())
        // Header
        list.add(escAlign(1))  // center
        list.add(escCharSize(0x11))  // doble
        list.add("CSAE POS\n".toByteArray(Charsets.UTF_8))
        list.add(escCharSize(0x00))
        list.add("${ticket.comensal.empresa}\n".toByteArray(Charsets.UTF_8))
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add(escAlign(0))  // left
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
        list.add(escCharSize(0x11))
        list.add("Precio: $${ticket.precio.takeIf { it > 0 } ?: ticket.servicio.precio}\n".toByteArray(Charsets.UTF_8))
        list.add(escCharSize(0x00))
        list.add("--------------------------------\n".toByteArray(Charsets.UTF_8))
        list.add("Operador: ${ticket.operador}\n".toByteArray(Charsets.UTF_8))

        // QR via comandos GS ( k (modelo 2, modulo 4, level M).
        if (!qrToken.isNullOrBlank()) {
            list.add("\n".toByteArray(Charsets.UTF_8))
            list.add(escAlign(1))  // center
            list.add(gsQRModel(2))      // modelo 2
            list.add(gsQRSize(4))       // tamano modulo 4
            list.add(gsQRError(0x31))   // error correction M
            list.add(gsQRStore(qrToken))
            list.add(gsQRPrint())
            list.add("\n".toByteArray(Charsets.UTF_8))
        }

        list.add("\n\n\n".toByteArray(Charsets.UTF_8))
        list.add(gsCut())
        return list
    }

    private fun buildBitmapCommands(bitmap: Bitmap, width: Int): List<ByteArray> {
        val list = ArrayList<ByteArray>()
        list.add(escInit())
        // Convertir bitmap a bytes ESC/POS GS v 0 (raster bit image).
        // Para impresoras 80mm, ancho max util ~576 dots a 203dpi.
        list.add(gsv0(bitmap, width))
        list.add(gsCut())
        return list
    }

    // --- Comandos ESC/POS individuales ---

    /** ESC @ (1B 40) - Inicializar impresora. */
    private fun escInit(): ByteArray = byteArrayOf(0x1B, 0x40)

    /** ESC a n (1B 61 n) - Seleccionar alineacion. 0=left, 1=center, 2=right. */
    private fun escAlign(n: Int): ByteArray = byteArrayOf(0x1B, 0x61, n.toByte())

    /** ESC ! n (1B 21 n) - Seleccionar tamano de caracter.
     *  Bit 3 (0x08) = doble ancho. Bit 4 (0x10) = doble alto.
     *  0x00 = normal, 0x11 = doble ancho y alto. */
    private fun escCharSize(n: Int): ByteArray = byteArrayOf(0x1B, 0x21, n.toByte())

    /** GS V m (1D 56 m) - Cortar papel. 0x42 0x00 = corte parcial. */
    private fun gsCut(): ByteArray = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    // --- QR via GS ( k ---

    /** GS ( k - Funcion 1: Seleccionar modelo QR (modelo 2 = n=50). */
    private fun gsQRModel(n: Int): ByteArray = byteArrayOf(
        0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, n.toByte(), 0x00
    )

    /** GS ( k - Funcion 2: Tamano del modulo (1-16, default 3). */
    private fun gsQRSize(n: Int): ByteArray = byteArrayOf(
        0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, n.toByte()
    )

    /** GS ( k - Funcion 3: Error correction. 0x30=L, 0x31=M, 0x32=Q, 0x33=H. */
    private fun gsQRError(n: Int): ByteArray = byteArrayOf(
        0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, n.toByte()
    )

    /** GS ( k - Funcion 4: Almacenar data del QR en symbol storage area.
     *  Header: 1D 28 6B pL pH 31 50 48 n [data]
     *  pLpH = (data.length + 3) en little-endian.
     *  n = 48 (48 decimal = 0x30) = modo 8-bit. */
    private fun gsQRStore(data: String): ByteArray {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val totalLen = dataBytes.size + 3
        val pL = (totalLen and 0xFF).toByte()
        val pH = ((totalLen shr 8) and 0xFF).toByte()
        val header = byteArrayOf(0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30)
        return header + dataBytes
    }

    /** GS ( k - Funcion 5: Imprimir QR del symbol storage area. */
    private fun gsQRPrint(): ByteArray = byteArrayOf(
        0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30
    )

    // --- Raster bit image (GS v 0) ---

    /** GS v 0 m xL xH yL yH [data] - Imprimir bitmap raster.
     *  m = 0 (normal), 1 (doble ancho), 2 (doble alto), 3 (doble ambos).
     *  xL xH = ancho en bytes = width / 8.
     *  yL yH = alto en dots. */
    private fun gsv0(bitmap: Bitmap, width: Int, m: Int = 0): ByteArray {
        val w = width / 8
        val h = bitmap.height
        val xL = (w and 0xFF).toByte()
        val xH = ((w shr 8) and 0xFF).toByte()
        val yL = (h and 0xFF).toByte()
        val yH = ((h shr 8) and 0xFF).toByte()
        val header = byteArrayOf(0x1D, 0x76, 0x30, m.toByte(), xL, xH, yL, yH)
        // Convertir bitmap a bytes 1-bit. Cada byte = 8 pixeles horizontales.
        val pixels = IntArray(w * 8 * h)
        bitmap.getPixels(pixels, 0, w * 8, 0, 0, w * 8, h)
        val data = ByteArray(w * h)
        for (y in 0 until h) {
            for (xByte in 0 until w) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    val pixel = pixels[y * w * 8 + x]
                    val luminance = (pixel shr 16 and 0xFF) * 0.299 +
                        (pixel shr 8 and 0xFF) * 0.587 +
                        (pixel and 0xFF) * 0.114
                    if (luminance < 128) {
                        b = b or (1 shl (7 - bit))
                    }
                }
                data[y * w + xByte] = b.toByte()
            }
        }
        return header + data
    }

    // ================================================================

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter
        }

    private companion object {
        const val TAG = "CsaePrinter"
    }
}
