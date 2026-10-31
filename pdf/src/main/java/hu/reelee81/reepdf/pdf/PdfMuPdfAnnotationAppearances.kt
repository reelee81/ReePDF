package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import java.io.File
import java.io.IOException

object PdfMuPdfAnnotationAppearances {
    fun generate(input: File, output: File) {
        val document = Document.openDocument(input.absolutePath)
        var pdf: PDFDocument? = null
        try {
            pdf = document.asPDF() ?: throw IOException("Not a PDF document")
            val page = pdf.loadPage(0) as? PDFPage ?: throw IOException("Missing PDF page")
            try {
                page.annotations?.forEach { annotation ->
                    try {
                        annotation.requestSynthesis()
                        annotation.update()
                    } finally {
                        annotation.destroy()
                    }
                }
            } finally {
                page.destroy()
            }
            pdf.save(output.absolutePath, "")
        } finally {
            pdf?.destroy()
            document.destroy()
            Context.emptyStore()
        }
    }
}
