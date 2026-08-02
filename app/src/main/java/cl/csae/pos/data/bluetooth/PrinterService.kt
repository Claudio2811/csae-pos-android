package cl.csae.pos.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import cl.csae.pos.model.Ticket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
 * Para MVP, NO implementamos discovery ni pairing. Asumimos que el operador
 * emparejo la impresora desde Settings.
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
     */
    private fun enviarTicketEscPos(out: java.io.OutputStream, ticket: Ticket) {
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
        out.write("Precio: $${t.servicio.precio}\n".toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0x1D, 0x21, 0x00))
        out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        out.write("Operador: ${t.operador}\n".toByteArray(Charsets.UTF_8))
        out.write("\n\n\n".toByteArray(Charsets.UTF_8))
        // GS V 0 = Corte parcial
        out.write(byteArrayOf(0x1D, 0x56, 0x00))
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter
        }
}
