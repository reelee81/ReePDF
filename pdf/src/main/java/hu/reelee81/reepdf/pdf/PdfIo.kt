package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.CompressionConstants
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties

object PdfIo {
    fun writerProperties(): WriterProperties =
        WriterProperties()
            .setFullCompressionMode(true)
            .setCompressionLevel(CompressionConstants.BEST_SPEED)

    fun workFileWriter(path: String): PdfWriter = DirectObjectWriter(path, writerProperties())

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

    private class DirectObjectWriter(path: String, properties: WriterProperties) : PdfWriter(path, properties) {
        override fun flushObject(pdfObject: PdfObject, canBeInObjStm: Boolean) = super.flushObject(pdfObject, false)
    }
}
