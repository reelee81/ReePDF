package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.CompressionConstants
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties

object PdfIo {
    fun writerProperties(): WriterProperties =
        WriterProperties()
            .setFullCompressionMode(true)
            .setCompressionLevel(CompressionConstants.BEST_SPEED)

    fun reader(path: String, password: ByteArray? = null): PdfReader =
        PdfReader(
            path,
            ReaderProperties().apply {
                if (password != null) {
                    setPassword(password)
                }
            }
        ).apply {
            setMemorySavingMode(true)
            isCloseStream = true
            setUnethicalReading(true)
        }
}
