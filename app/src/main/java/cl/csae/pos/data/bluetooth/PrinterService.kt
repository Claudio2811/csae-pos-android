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
import cl.csae.pos.model.Comensal
import cl.csae.pos.model.Servicio
import cl.csae.pos.model.Ticket
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * PrinterService: impresion Bluetooth ESC/POS basica (58mm termica).
 *
 * Compatible con impresoras estandar 58mm que soporten ESC/POS (Epson, Star, Xprinter, etc.).
 *
 * Flujo:
 *   1. Usuario empareja la impresora desde Settings del dispositivo.
 *   2. POS lista los dispositivos emparejados (BluetoothAdapter.bondedDevices).
 *   3. Usuario elige uno (mac address).
 *   4. POS abre socket RFCOMM y envia comandos ESC/POS.
 *
 * Sprint 3.2: agrega soporte para QR via comandos `GS ( k` (estandar Epson ESC/POS).
 *   - Funcion 165: seleccionar modelo QR
 *   - Funcion 167: setear tamano del modulo
 *   - Funcion 169: setear nivel de correccion
 *   - Funcion 180: almacenar data
 *   - Funcion 181: imprimir
 *
 * **Permisos requeridos (AndroidManifest):**
 * - BLUETOOTH (max SDK 30)
 * - BLUETOOTH_CONNECT (Android 12+)
 * - BLUETOOTH_SCAN (Android 12+, noForLocation)
 */
class PrinterService(private val context: Context) {

    /**
     * Lista los dispositivos Bluetooth ya emparejados.
     */
    @SuppressLint("MissingPermission")
    fun listarEmparejados(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { return emptyList() }
        return bonded?.toList() ?: emptyList()
    }

    /**
     * Imprime un ticket via Bluetooth ESC/POS.
     * @return true si se imprimio OK, false si fallo.
     */
    suspend fun imprimir(deviceAddress: String, ticket: Ticket): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext Result.failure(
            IllegalStateException("Bluetooth no disponible en este dispositivo.")
        )
        val device = try { adapter.getRemoteDevice(deviceAddress) } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("No se encontro el dispositivo: ${e.message}"))
        }
        var socket: BluetoothSocket? = null
        try {
            socket = createSocket(device)
            socket.connect() // Conectar antes de obtener el output stream
            val out = socket.outputStream
            enviarTicketEscPos(out, ticket)
            out.flush()
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("Sin permiso BLUETOOTH_CONNECT. Activalo en Settings."))
        } catch (e: IOException) {
            Result.failure(IllegalStateException("Error de comunicacion con la impresora: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Imprime un ticket con QR + texto. Usado en el preview de TicketScreen
     * (sprint 3.2). Si el qrToken es null o vacio, imprime solo el texto.
     */
    suspend fun imprimirTicketConQr(
        deviceAddress: String,
        ticket: Ticket,
        qrToken: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext Result.failure(
            IllegalStateException("Bluetooth no disponible en este dispositivo.")
        )
        val device = try { adapter.getRemoteDevice(deviceAddress) } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("No se encontro el dispositivo: ${e.message}"))
        }
        var socket: BluetoothSocket? = null
        try {
            socket = createSocket(device)
            socket.connect()
            val out = socket.outputStream
            enviarTicketEscPos(out, ticket, qrToken)
            out.flush()
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("Sin permiso BLUETOOTH_CONNECT. Activalo en Settings."))
        } catch (e: IOException) {
            Result.failure(IllegalStateException("Error de comunicacion con la impresora: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Genera un Bitmap QR (monocromatico) para mostrar en pantalla. Usado por
     * el preview del ticket (sprint 3.2).
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
     * Ticket de prueba simple: envia un "HELLO" a la impresora.
     * Usado desde la pantalla de Configuracion.
     */
    suspend fun imprimirPrueba(deviceAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext Result.failure(
            IllegalStateException("Bluetooth no disponible en este dispositivo.")
        )
        val device = try { adapter.getRemoteDevice(deviceAddress) } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("No se encontro el dispositivo: ${e.message}"))
        }
        var socket: BluetoothSocket? = null
        try {
            socket = createSocket(device)
            socket.connect()
            val out = socket.outputStream
            out.write(byteArrayOf(0x1B, 0x40)) // Init
            out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
            out.write(byteArrayOf(0x1D, 0x21, 0x11)) // Tamano doble
            out.write("CSAE POS - PRUEBA\n".toByteArray(Charsets.UTF_8))
            out.write(byteArrayOf(0x1D, 0x21, 0x00))
            out.write("Si lees esto, la impresora esta OK.\n".toByteArray(Charsets.UTF_8))
            out.write("\n\n\n".toByteArray(Charsets.UTF_8))
            out.write(byteArrayOf(0x1D, 0x56, 0x00)) // Corte parcial
            out.flush()
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("Sin permiso BLUETOOTH_CONNECT. Activalo en Settings."))
        } catch (e: IOException) {
            Result.failure(IllegalStateException("Error de comunicacion con la impresora: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice): BluetoothSocket {
        // En API 31+ podemos usar createInsecureRfcommSocketToServiceRecord (sin pairing required).
        // En API < 31 usamos el UUID SPP estandar.
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        } else {
            device.createRfcommSocketToServiceRecord(uuid)
        }
    }

    /**
     * Envia el ticket en formato ESC/POS.
     * Comandos basicos: ESC @ (init), ESC ! n (tamano), GS V 0 (corte parcial).
     * Si [qrToken] no es null ni vacio, agrega el QR al final del ticket.
     */
    private fun enviarTicketEscPos(out: OutputStream, ticket: Ticket, qrToken: String? = null) {
        val t = ticket
        // ESC @ = Init
        out.write(byteArrayOf(0x1B, 0x40))
        // Center
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        // Tamano doble
        out.write(byteArrayOf(0x1D, 0x21, 0x11))
        out.write("CSAE POS\n".toByteArray(Charsets.UTF_8))
        // Tamano normal
        out.write(byteArrayOf(0x1D, 0x21, 0x00))
        out.write("Casino Salamanca\n".toByteArray(Charsets.UTF_8))
        out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        out.write("${t.fechaHora}\n".toByteArray(Charsets.UTF_8))
        out.write("Ticket: ${t.numero}\n".toByteArray(Charsets.UTF_8))
        out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        out.write("Comensal: ${t.comensal.nombre} ${t.comensal.apellido ?: ""}\n".toByteArray(Charsets.UTF_8))
        out.write("RUT: ${t.comensal.rut}\n".toByteArray(Charsets.UTF_8))
        if (t.comensal.empresa.isNotEmpty()) {
            out.write("Empresa: ${t.comensal.empresa}\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        out.write("Servicio: ${t.servicio.nombre}\n".toByteArray(Charsets.UTF_8))
        out.write("Tipo: ${t.servicio.tipo}\n".toByteArray(Charsets.UTF_8))
        // Precio en grande
        out.write(byteArrayOf(0x1D, 0x21, 0x11))
        out.write("Precio: $${t.precio.takeIf { it > 0 } ?: t.servicio.precio}\n".toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0x1D, 0x21, 0x00))
        out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        out.write("Operador: ${t.operador}\n".toByteArray(Charsets.UTF_8))

        // QR (si viene)
        if (!qrToken.isNullOrBlank()) {
            out.write("\n".toByteArray(Charsets.UTF_8))
            try {
                imprimirQr(out, qrToken)
            } catch (_: Exception) {
                // Si la impresora no soporta QR, seguimos sin cortar el ticket.
            }
        }

        out.write("\n\n\n".toByteArray(Charsets.UTF_8))
        // GS V 0 = Corte parcial
        out.write(byteArrayOf(0x1D, 0x56, 0x00))
    }

    /**
     * Envia el comando QR (Epson ESC/POS `GS ( k`).
     *
     * Funciones usadas:
     *   - 165 (0xA5): seleccionar modelo (modelo 2 = 50, 0x32)
     *   - 167 (0xA7): setear tamano del modulo (n = 4 ~ 5mm en 58mm)
     *   - 169 (0xA9): setear nivel de correccion (M = 49, 0x31 = 15%)
     *   - 180 (0xB4): almacenar data (4 bytes de header + data)
     *   - 181 (0xB5): imprimir
     *
     * Documentacion Epson: TM-T88V Command Reference, pag ~250.
     */
    fun imprimirQr(out: OutputStream, qrToken: String) {
        val data = qrToken.toByteArray(Charsets.UTF_8)
        if (data.isEmpty()) return

        // GS ( k  Function 165  pl  ph  cn  fn  n1  n2
        // 4 bytes de parametros: cn=49 (0x31), fn=65 (0x41, modelo), n1=50 (0x32, modelo 2), n2=0
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))

        // GS ( k  Function 167  pl  ph  cn  fn  n
        // cn=49, fn=67, n = tamano modulo (4)
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x04))

        // GS ( k  Function 169  pl  ph  cn  fn  n
        // cn=49, fn=69, n = nivel de correccion (49 = '1' = ~15% / Level M)
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31))

        // GS ( k  Function 180  pl  ph  cn  fn  data
        // pL = (data.size + 3) & 0xFF, pH = (data.size + 3) >> 8
        val pL = ((data.size + 3) and 0xFF).toByte()
        val pH = (((data.size + 3) shr 8) and 0xFF).toByte()
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30))
        out.write(data)

        // GS ( k  Function 181  pl  ph  cn  fn  m
        // m = 0 (imprimir)
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter
        }
}
