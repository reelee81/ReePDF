package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

internal object PdfBoundedStream {
    fun read(stream: PdfStream, limit: Int): ByteArray? {
        val encodedSize = stream.outputStream?.currentPos ?: stream.length.toLong()
        if (encodedSize !in 1..limit.toLong()) return null
        val filter = stream.get(PdfName.Filter)
        val isCompressed = filter == PdfName.FlateDecode ||
            (filter is PdfArray && filter.size() == 1 && filter.getAsName(0) == PdfName.FlateDecode)
        if (filter != null && !isCompressed) return null
        return try {
            val raw = stream.getBytes(false)
            if (raw.size > limit) return null
            if (!isCompressed) return raw
            InflaterInputStream(ByteArrayInputStream(raw)).use { input ->
                val output = ByteArrayOutputStream(minOf(raw.size, 8192))
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() > limit - count) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }
}
