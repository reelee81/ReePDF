package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

object PdfMuPdfRepair {
    class Repaired(val skippedPages: Int, val annotationsLost: Boolean)

    private class Parts(val files: List<File>, val skippedPages: Int, val annotationsLost: Boolean)

    fun withRepairedParts(
        input: File,
        password: ByteArray?,
        workDir: File,
        batchSize: Int,
        ensureActive: () -> Unit,
        block: (List<File>) -> Unit
    ): Repaired? {
        val partDir = File(workDir, "mupdf_repair_${System.nanoTime()}")
        try {
            val parts = try {
                createParts(input, password, partDir, batchSize, ensureActive)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
            block(parts.files)
            return Repaired(parts.skippedPages, parts.annotationsLost)
        } finally {
            partDir.deleteRecursively()
        }
    }

    fun rejectsPassword(input: File, password: ByteArray?): Boolean {
        val document = try {
            Document.openDocument(input.absolutePath)
        } catch (_: RuntimeException) {
            return false
        }
        return try {
            document.needsPassword() &&
                (password == null || !document.authenticatePassword(String(password, Charsets.UTF_8)))
        } catch (_: RuntimeException) {
            false
        } finally {
            document.destroy()
            Context.emptyStore()
        }
    }

    private fun createParts(
        input: File,
        password: ByteArray?,
        partDir: File,
        batchSize: Int,
        ensureActive: () -> Unit
    ): Parts? {
        if (!partDir.mkdirs()) return null
        val total = withSource(input, password) { it.countPages() } ?: return null
        if (total <= 0) return null

        val parts = ArrayList<File>()
        var copiedPages = 0
        var annotationsLost = false
        var from = 0
        while (from < total) {
            ensureActive()
            val to = min(from + batchSize.coerceAtLeast(1), total)
            val part = File(partDir, "part_${parts.size}.pdf")
            val copied = withSource(input, password) { source ->
                graftBatch(source, from, to, part) { annotationsLost = true }
            } ?: return null
            if (copied > 0) parts += part
            copiedPages += copied
            from = to
        }
        if (parts.isEmpty()) return null
        return Parts(parts, total - copiedPages, annotationsLost)
    }

    private fun graftBatch(source: PDFDocument, from: Int, to: Int, part: File, onAnnotationsLost: () -> Unit): Int {
        val target = PDFDocument()
        val map = target.newPDFGraftMap()
        try {
            var pages = 0
            for (index in from until to) {
                try {
                    map.graftPage(pages, source, index)
                    runCatching { ensureMediaBox(target, pages) }
                    runCatching { copyAnnotations(map, source, index, target, pages) }.onFailure { onAnnotationsLost() }
                    pages++
                } catch (_: RuntimeException) {
                }
            }
            if (pages > 0) target.save(part.absolutePath, "decompress,compress")
            return pages
        } finally {
            map.destroy()
            target.destroy()
            Context.emptyStore()
        }
    }

    private fun copyAnnotations(map: PDFGraftMap, source: PDFDocument, sourceIndex: Int, target: PDFDocument, targetIndex: Int) {
        val sourcePage = source.findPage(sourceIndex)
        val annotations = sourcePage.get("Annots")
        try {
            if (!annotations.isArray || annotations.size() == 0) return
            for (index in 0 until annotations.size()) {
                val annotation = annotations.get(index)
                try {
                    if (annotation.isDictionary) detach(annotation)
                } finally {
                    annotation.destroy()
                }
            }
            val copied = map.graftObject(annotations)
            val targetPage = target.findPage(targetIndex)
            try {
                targetPage.put("Annots", copied)
            } finally {
                targetPage.destroy()
                copied.destroy()
            }
        } finally {
            annotations.destroy()
            sourcePage.destroy()
        }
    }

    private fun detach(annotation: PDFObject) {
        val subtype = annotation.get("Subtype")
        val isWidget = try {
            subtype.isName && subtype.asName() == "Widget"
        } finally {
            subtype.destroy()
        }
        if (isWidget) {
            for (key in inheritedFieldKeys) {
                val value = annotation.getInheritable(key)
                try {
                    if (!value.isNull) annotation.put(key, value)
                } finally {
                    value.destroy()
                }
            }
        }
        for (key in detachedKeys) annotation.delete(key)
    }

    private val inheritedFieldKeys = arrayOf("FT", "Ff", "V", "DV", "DA", "Q", "MaxLen", "Opt", "I", "TI", "DS", "RV")
    private val detachedKeys = arrayOf("P", "Parent", "Popup", "IRT")

    private fun ensureMediaBox(document: PDFDocument, index: Int) {
        val page = document.findPage(index)
        try {
            val box = page.getInheritable("MediaBox")
            try {
                if (box.isArray && box.size() >= 4) return
            } finally {
                box.destroy()
            }
            page.put("MediaBox", Rect(0f, 0f, 612f, 792f))
        } finally {
            page.destroy()
        }
    }

    private fun <T> withSource(input: File, password: ByteArray?, block: (PDFDocument) -> T): T? {
        val document = Document.openDocument(input.absolutePath)
        var pdf: PDFDocument? = null
        try {
            if (document.needsPassword() &&
                (password == null || !document.authenticatePassword(String(password, Charsets.UTF_8)))
            ) return null
            pdf = document.asPDF() ?: return null
            return block(pdf)
        } finally {
            pdf?.destroy()
            document.destroy()
            Context.emptyStore()
        }
    }
}
