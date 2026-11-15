package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import java.io.File

object PdfMarkSave {
    class Plan(
        val added: Map<Int, List<PdfMark>> = emptyMap(),
        val streams: Map<Int, PdfStreamEdit> = emptyMap(),
        val blankPages: Set<Int> = emptySet(),
        val blankAll: Boolean = false
    ) {
        val isEmpty: Boolean
            get() = added.values.all { it.isEmpty() } && streams.values.all { it.isOriginal } && blankPages.isEmpty() && !blankAll
    }

    fun write(
        input: File,
        output: File,
        plan: Plan,
        batchSize: Int,
        progressStage: (Long) -> PdfProgress,
        ensureActive: () -> Unit
    ): Boolean {
        val blanked = if (plan.blankAll || plan.blankPages.isNotEmpty()) {
            blankedStreams(input, plan.blankPages.takeUnless { plan.blankAll }, batchSize, progressStage, ensureActive)
        } else {
            emptySet()
        }
        val edits = LinkedHashMap<Int, PdfStreamEdit>()
        for ((number, edit) in plan.streams) if (!edit.isOriginal) edits[number] = edit
        for (number in blanked) edits[number] = PdfStreamEdit.BLANK
        val added = plan.added.filterValues { it.isNotEmpty() }
        if (edits.isEmpty() && added.isEmpty()) return false
        val progress = progressStage((edits.size + added.size).toLong())
        PdfDocument(PdfReader(input), PdfIo.workFileWriter(output.absolutePath), StampingProperties().useAppendMode()).use { pdf ->
            for ((number, edit) in edits) {
                ensureActive()
                val stream = pdf.getPdfObject(number) as? PdfStream
                if (stream != null) {
                    val content = edit.applyTo(stream.bytes)
                    stream.remove(PdfName.Filter)
                    stream.remove(PdfName.DecodeParms)
                    stream.setData(content)
                    if (PdfEditProjection.isEditLayer(stream)) PdfEditProjection.writablePrivateData(stream).put(PdfEditProjection.EDITED, PdfBoolean.TRUE)
                    stream.setModified()
                    stream.flush()
                }
                progress.advance(1)
            }
            for ((pageIndex, marks) in added.toSortedMap()) {
                ensureActive()
                require(pageIndex in 0 until pdf.numberOfPages) { "Page ${pageIndex + 1} is outside 1..${pdf.numberOfPages}" }
                val page = pdf.getPage(pageIndex + 1)
                val alpha = if (marks.any { it.kind.translucent }) {
                    page.resources.addExtGState(PdfExtGState().setStrokeOpacity(PdfMark.ALPHA).setFillOpacity(PdfMark.ALPHA)).value
                } else {
                    null
                }
                page.newContentStreamAfter().setData(PdfMarkContent.write(marks, alpha))
                page.pdfObject.setModified()
                page.resources.pdfObject.takeIf { it.indirectReference != null }?.setModified()
                progress.advance(1)
            }
            progress.idle()
        }
        return true
    }

    private fun blankedStreams(
        input: File,
        pages: Set<Int>?,
        batchSize: Int,
        progressStage: (Long) -> PdfProgress,
        ensureActive: () -> Unit
    ): Set<Int> {
        val total = PdfDocument(PdfReader(input).setMemorySavingMode(true)).use { it.numberOfPages }
        val wanted = pages?.filter { it in 0 until total }?.sorted() ?: (0 until total).toList()
        val progress = progressStage(wanted.size.toLong())
        val found = LinkedHashSet<Int>()
        for (chunk in wanted.chunked(batchSize.coerceAtLeast(1))) {
            ensureActive()
            PdfDocument(PdfReader(input).setMemorySavingMode(true)).use { pdf ->
                for (pageIndex in chunk) {
                    for (stream in PdfEditProjection.editStreams(pdf.getPage(pageIndex + 1))) {
                        stream.indirectReference?.objNumber?.let(found::add)
                    }
                }
            }
            progress.advance(chunk.size)
        }
        progress.idle()
        return found
    }
}
