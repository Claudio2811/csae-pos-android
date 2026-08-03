package cl.csae.pos.data.bluetooth

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cl.csae.pos.model.Ticket
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Genera un PDF del ticket como fallback cuando la impresion Bluetooth falla.
 *
 * Sprint 3.2.1: usamos `android.graphics.pdf.PdfDocument` nativo de Android
 * (sin dependencias externas como iText). En API 29+ guardamos el PDF en la
 * carpeta publica Downloads/csae-tickets/ via MediaStore (no requiere
 * permiso WRITE_EXTERNAL_STORAGE). En API < 29 usamos el path publico
 * clasico con permiso.
 *
 * El archivo se devuelve como `File` para que el caller pueda abrirlo via
 * intent o FileProvider. En API 29+ el File devuelto apunta al archivo
 * fisico real (no a un content URI) y es directamente abrible por
 * cualquier app con FileProvider.
 */
object TicketPdfGenerator {

    private const val PAGE_WIDTH = 595   // A6 portrait (pt)
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f        // ~12mm

    /**
     * Genera el PDF y lo guarda.
     *
     * @param context contexto Android
     * @param ticket ticket a renderizar
     * @param qrBitmap bitmap del QR (puede ser null)
     * @return File apuntando al PDF generado
     */
    fun generarPdf(
        context: Context,
        ticket: Ticket,
        qrBitmap: Bitmap?,
    ): File {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val labelPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val precioPaint = Paint().apply {
            color = Color.BLACK
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val footerPaint = Paint().apply {
            color = Color.GRAY
            textSize = 9f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val infoPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
            isAntiAlias = true
        }

        val cx = PAGE_WIDTH / 2f
        var y = MARGIN + 20f

        // Header
        canvas.drawText("CSAE POS", cx, y, titlePaint)
        y += 18f
        canvas.drawText("Casino Salamanca", cx, y, subtitlePaint)
        y += 14f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 16f

        // Comensal
        val comensalFull = "${ticket.comensal.nombre} ${ticket.comensal.apellido ?: ""}".trim()
        canvas.drawText("Comensal:", MARGIN, y, labelPaint)
        y += 14f
        canvas.drawText(comensalFull, MARGIN, y, valuePaint)
        y += 14f
        canvas.drawText("RUT: ${ticket.comensal.rut}", MARGIN, y, infoPaint)
        y += 12f
        if (ticket.comensal.empresa.isNotEmpty()) {
            canvas.drawText("Empresa: ${ticket.comensal.empresa}", MARGIN, y, infoPaint)
            y += 12f
        }

        y += 8f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 16f

        // Servicio
        canvas.drawText("Servicio: ${ticket.servicio.nombre}", MARGIN, y, valuePaint)
        y += 14f
        canvas.drawText("Tipo: ${ticket.servicio.tipo}", MARGIN, y, infoPaint)
        y += 16f

        // Precio
        val precio = ticket.precio.takeIf { it > 0 } ?: ticket.servicio.precio
        canvas.drawText("Precio: $$precio CLP", cx, y, precioPaint)
        y += 22f

        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 16f

        // Fecha/hora + numero de ticket
        canvas.drawText("Fecha: ${ticket.fechaHora}", MARGIN, y, infoPaint)
        y += 12f
        canvas.drawText("Ticket: ${ticket.numero}", MARGIN, y, infoPaint)
        y += 12f
        canvas.drawText("Operador: ${ticket.operador}", MARGIN, y, infoPaint)
        y += 16f

        // QR centrado
        if (qrBitmap != null) {
            val qrSize = 180
            val qrLeft = ((PAGE_WIDTH - qrSize) / 2).toFloat()
            val qrTop = y.coerceAtMost(PAGE_HEIGHT - qrSize - MARGIN - 40f)
            canvas.drawBitmap(
                qrBitmap, null,
                RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize),
                null,
            )
            y = qrTop + qrSize + 12f
        }

        // Footer
        val footerY = (PAGE_HEIGHT - MARGIN - 20f).coerceAtLeast(y + 20f)
        canvas.drawText("Para validar, escanea el QR en la app del garzon.", cx, footerY, footerPaint)
        y = footerY + 10f
        canvas.drawText(
            "Generado: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}",
            cx, y, footerPaint,
        )

        pdf.finishPage(page)

        val safeNumero = ticket.numero.replace(Regex("[^A-Za-z0-9-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "ticket-${safeNumero}-${timestamp}.pdf"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: usar MediaStore (no requiere permiso).
            saveViaMediaStore(context, pdf, fileName)
        } else {
            // API < 29: guardar en Downloads publico (requiere WRITE_EXTERNAL_STORAGE).
            saveViaLegacyPath(context, pdf, fileName)
        }
    }

    /**
     * Guarda el PDF en Downloads/csae-tickets/ via MediaStore. Devuelve un
     * File que apunta a una copia temporal local, ya que el archivo "real"
     * esta en MediaStore (content URI).
     */
    private fun saveViaMediaStore(context: Context, pdf: PdfDocument, fileName: String): File {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/csae-tickets")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en MediaStore")
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw IllegalStateException("No se pudo abrir OutputStream del URI")
            pdf.writeTo(out)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        pdf.close()
        // Para que el caller pueda abrir el PDF con FileProvider, copiamos
        // a un archivo local en cache y devolvemos ese File. Esto evita
        // que el operador tenga que aprender a abrir content:// URIs.
        val cacheFile = File(context.cacheDir, fileName)
        resolver.openInputStream(uri).use { input ->
            if (input == null) throw IllegalStateException("No se pudo abrir InputStream del URI")
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        }
        return cacheFile
    }

    /**
     * Legacy: guardar en /storage/emulated/0/Download/csae-tickets/.
     * Requiere WRITE_EXTERNAL_STORAGE que ya no se concede en API 29+ pero
     * todavia sirve en API 26-28 (minSdk del proyecto).
     */
    @Suppress("DEPRECATION")
    private fun saveViaLegacyPath(context: Context, pdf: PdfDocument, fileName: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloads, "csae-tickets")
        if (!targetDir.exists()) targetDir.mkdirs()
        val outFile = File(targetDir, fileName)
        FileOutputStream(outFile).use { out -> pdf.writeTo(out) }
        pdf.close()
        return outFile
    }
}
