package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.utils.PdfAnnotationFlattener
import java.io.File

object PdfEditorFlattening {
    fun flatten(file: File, flatFile: File, progressStage: (Long) -> PdfProgress, ensureActive: () -> Unit) {
        PdfDocument(
            PdfReader(file.absolutePath),
            PdfWriter(flatFile.absolutePath, PdfIo.writerProperties())
        ).use { pdf ->
            val flattener = PdfAnnotationFlattener()
            val progress = progressStage(pdf.numberOfPages.toLong())
            val writeUntouchedPages = !pdf.isTagged
            for (pageNumber in 1..pdf.numberOfPages) {
                ensureActive()
                val page = pdf.getPage(pageNumber)
                val annotations = page.annotations
                    .filter { annotation ->
                        when (annotation.subtype) {
                            PdfName.Stamp,
                            PdfName.Ink,
                            PdfName.Highlight,
                            PdfName.FreeText -> true
                            else -> false
                        }
                    }
                    .toList()

                if (annotations.isNotEmpty()) {
                    flattener.flatten(annotations)
                } else if (writeUntouchedPages) {
                    page.pdfObject.flush()
                }
                progress.advance(1)
            }
            progress.idle()
        }
    }
}
