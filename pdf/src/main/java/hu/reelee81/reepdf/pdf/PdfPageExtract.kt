package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import java.io.File

object PdfPageExtract {
    fun write(source: File, pageNumber: Int, out: File) {
        if (out.exists()) out.delete()

        PdfDocument(PdfIo.reader(source.absolutePath)).use { src ->
            PdfDocument(PdfWriter(out.absolutePath, PdfIo.writerProperties())).use { dest ->
                dest.setFlushUnusedObjects(true)
                src.copyPagesTo(pageNumber, pageNumber, dest)
                runCatching { PdfCopySupport.flushCopiedObjects(dest, src) }
                runCatching { dest.lastPage?.flush() }
            }
        }
    }
}
