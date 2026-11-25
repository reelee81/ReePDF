package hu.reelee81.reepdf.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import java.io.File
import java.text.Normalizer

object PdfMarkSave {
    private const val MAX_TREE_DEPTH = 64

    class Plan(
        val added: Map<Int, List<PdfMark>> = emptyMap(),
        val streams: Map<Int, PdfStreamEdit> = emptyMap(),
        val blankPages: Set<Int> = emptySet(),
        val blankAll: Boolean = false,
        val editedPages: Set<Int> = emptySet()
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
        fonts: PdfTextFonts? = null,
        ensureActive: () -> Unit
    ): Boolean {
        val blanked = if (plan.blankAll || plan.blankPages.isNotEmpty()) {
            blankedStreams(input, plan.blankPages.takeUnless { plan.blankAll }, batchSize, progressStage, ensureActive)
        } else {
            Blanked(emptySet(), emptySet())
        }
        val edits = LinkedHashMap<Int, PdfStreamEdit>()
        for ((number, edit) in plan.streams) if (!edit.isOriginal) edits[number] = edit
        for (number in blanked.streams) edits[number] = PdfStreamEdit.BLANK
        val added = plan.added.filterValues { it.isNotEmpty() }
        if (edits.isEmpty() && added.isEmpty()) return false
        val progress = progressStage((edits.size + added.size).toLong())
        val editedFonts = HashMap<Int, Set<String>>()
        PdfDocument(PdfReader(input), PdfIo.workFileWriter(output.absolutePath), StampingProperties().useAppendMode()).use { pdf ->
            for ((number, edit) in edits) {
                ensureActive()
                val stream = pdf.getPdfObject(number) as? PdfStream
                if (stream != null) {
                    val content = edit.applyTo(stream.bytes)
                    editedFonts[number] = PdfMarkContent.fontNames(content)
                    stream.remove(PdfName.Filter)
                    stream.remove(PdfName.DecodeParms)
                    stream.setData(content)
                    if (PdfEditProjection.isEditLayer(stream)) {
                        PdfEditProjection.writablePrivateData(stream).put(PdfEditProjection.EDITED, PdfBoolean.TRUE)
                        pruneFonts(stream, editedFonts.getValue(number), ownOnly = false)
                    }
                    stream.setModified()
                    stream.flush()
                }
                progress.advance(1)
            }
            val typedFonts = fonts?.let { TypedFonts(pdf, it) }
            for ((pageIndex, marks) in added.toSortedMap()) {
                ensureActive()
                require(pageIndex in 0 until pdf.numberOfPages) { "Page ${pageIndex + 1} is outside 1..${pdf.numberOfPages}" }
                val page = pdf.getPage(pageIndex + 1)
                val prepared = marks.mapNotNull { mark ->
                    val text = mark.text ?: return@mapNotNull mark
                    prepared(mark, text, requireNotNull(typedFonts) { "A typed mark needs its fonts" }.font(text.weight))
                }
                if (prepared.isEmpty()) {
                    progress.advance(1)
                    continue
                }
                val names = HashMap<Int, String>()
                if (prepared.any { it.text != null }) ownResources(page, pdf)
                val alpha = if (prepared.any { it.kind.translucent }) {
                    page.resources.addExtGState(PdfExtGState().setStrokeOpacity(PdfMark.ALPHA).setFillOpacity(PdfMark.ALPHA)).value
                } else {
                    null
                }
                val pageFonts = typedFonts?.let { source ->
                    object : PdfMarkContent.Fonts {
                        override fun name(weight: Int): String = names.getOrPut(weight) { source.addTo(page, weight) }

                        override fun encode(weight: Int, line: String): ByteArray = source.font(weight).convertToBytes(line)

                        override fun width(weight: Int, line: String): Double = source.width(weight, line)
                    }
                }
                page.newContentStreamAfter().setData(PdfMarkContent.write(prepared, alpha, pageFonts))
                page.pdfObject.setModified()
                page.resources.pdfObject.takeIf { it.indirectReference != null }?.setModified()
                progress.advance(1)
            }
            for (pageIndex in (plan.editedPages + blanked.pages + added.keys).sorted()) {
                if (pageIndex !in 0 until pdf.numberOfPages) continue
                ensureActive()
                prunePageFonts(pdf.getPage(pageIndex + 1), editedFonts)
            }
            progress.idle()
        }
        return true
    }

    internal fun prepared(text: String, font: PdfFont): String {
        val normal = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
        val kept = StringBuilder(normal.length)
        var count = 0
        var index = 0
        while (index < normal.length && count < PdfMarkText.MAX_CHARS) {
            val codePoint = normal.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == '\n'.code || font.containsGlyph(codePoint)) {
                kept.appendCodePoint(codePoint)
                count++
            }
        }
        return kept.toString()
    }

    internal fun pruneFonts(holder: PdfDictionary, used: Set<String>, ownOnly: Boolean): Boolean {
        val resources = holder.getAsDictionary(PdfName.Resources) ?: return false
        val fonts = resources.getAsDictionary(PdfName.Font) ?: return false
        val unused = fonts.keySet().filter { name ->
            name.value !in used && (!ownOnly || name.value.startsWith(PdfMarkContent.FONT_PREFIX))
        }
        if (unused.isEmpty()) return false
        for (name in unused) fonts.remove(name)
        fonts.takeIf { it.indirectReference != null }?.setModified()
        resources.takeIf { it.indirectReference != null }?.setModified()
        return true
    }

    private fun prepared(mark: PdfMark, text: PdfMarkText, font: PdfFont): PdfMark? {
        val kept = prepared(text.text, font)
        if (kept.isBlank()) return null
        if (kept == text.text) return mark
        return PdfMark(mark.id, mark.kind, mark.color, mark.width, mark.path, PdfMarkText(kept, text.weight, text.size, text.matrix))
    }

    private fun prunePageFonts(page: PdfPage, edited: Map<Int, Set<String>>) {
        val fonts = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.Font) ?: return
        if (fonts.keySet().none { it.value.startsWith(PdfMarkContent.FONT_PREFIX) }) return
        val contents = page.pdfObject.get(PdfName.Contents, false)
        val items = if (contents is PdfArray) List(contents.size()) { contents.get(it, false) } else listOfNotNull(contents)
        val used = HashSet<String>()
        for (item in items) {
            val number = (item as? PdfIndirectReference)?.objNumber ?: item.indirectReference?.objNumber
            val known = number?.let(edited::get)
            if (known != null) {
                used += known
                continue
            }
            val stream = (if (item is PdfIndirectReference) item.refersTo else item) as? PdfStream ?: continue
            used += PdfMarkContent.fontNames(stream.bytes)
        }
        if (pruneFonts(page.pdfObject, used, ownOnly = true)) page.pdfObject.setModified()
    }

    private fun ownResources(page: PdfPage, pdf: PdfDocument) {
        val current = page.pdfObject.getAsDictionary(PdfName.Resources)
        if (current != null && current.indirectReference == null) {
            ownSubDictionaries(current)
            page.pdfObject.setModified()
            return
        }
        val own = PdfDictionary(current ?: inheritedResources(page) ?: PdfDictionary())
        ownSubDictionaries(own)
        own.makeIndirect(pdf)
        page.pdfObject.put(PdfName.Resources, own)
        page.pdfObject.setModified()
    }

    private fun inheritedResources(page: PdfPage): PdfDictionary? {
        var node = page.pdfObject.getAsDictionary(PdfName.Parent)
        var depth = 0
        while (node != null && depth++ < MAX_TREE_DEPTH) {
            node.getAsDictionary(PdfName.Resources)?.let { return it }
            node = node.getAsDictionary(PdfName.Parent)
        }
        return null
    }

    private fun ownSubDictionaries(resources: PdfDictionary) {
        for (key in listOf(PdfName.Font, PdfName.ExtGState)) {
            val sub = resources.getAsDictionary(key) ?: continue
            resources.put(key, PdfDictionary(sub))
        }
    }

    private class TypedFonts(private val pdf: PdfDocument, private val source: PdfTextFonts) {
        private val fonts = HashMap<Int, PdfFont>()

        fun font(weight: Int): PdfFont = fonts.getOrPut(weight) {
            PdfFontFactory.createFont(
                PdfFontHinting.strip(source.bytes(weight)),
                PdfEncodings.IDENTITY_H,
                PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED,
                false
            ).also { pdf.addFont(it) }
        }

        fun addTo(page: PdfPage, weight: Int): String {
            val resources = page.pdfObject.getAsDictionary(PdfName.Resources)
                ?: PdfDictionary().also { page.pdfObject.put(PdfName.Resources, it) }
            val bucket = resources.getAsDictionary(PdfName.Font) ?: PdfDictionary().also { resources.put(PdfName.Font, it) }
            var name: PdfName
            do {
                name = PdfName(PdfMarkContent.FONT_PREFIX + PdfMark.newId())
            } while (bucket.containsKey(name))
            bucket.put(name, font(weight).pdfObject)
            return name.value
        }

        fun width(weight: Int, line: String): Double {
            val font = font(weight)
            var total = 0L
            var index = 0
            while (index < line.length) {
                val codePoint = line.codePointAt(index)
                index += Character.charCount(codePoint)
                total += font.getWidth(codePoint)
            }
            return total / 1000.0
        }
    }

    private class Blanked(val streams: Set<Int>, val pages: Set<Int>)

    private fun blankedStreams(
        input: File,
        pages: Set<Int>?,
        batchSize: Int,
        progressStage: (Long) -> PdfProgress,
        ensureActive: () -> Unit
    ): Blanked {
        val total = PdfDocument(PdfReader(input).setMemorySavingMode(true)).use { it.numberOfPages }
        val wanted = pages?.filter { it in 0 until total }?.sorted() ?: (0 until total).toList()
        val progress = progressStage(wanted.size.toLong())
        val found = LinkedHashSet<Int>()
        val withFonts = LinkedHashSet<Int>()
        for (chunk in wanted.chunked(batchSize.coerceAtLeast(1))) {
            ensureActive()
            PdfDocument(PdfReader(input).setMemorySavingMode(true)).use { pdf ->
                for (pageIndex in chunk) {
                    val page = pdf.getPage(pageIndex + 1)
                    for (stream in PdfEditProjection.editStreams(page)) {
                        stream.indirectReference?.objNumber?.let(found::add)
                    }
                    val fonts = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.Font)
                    if (fonts?.keySet()?.any { it.value.startsWith(PdfMarkContent.FONT_PREFIX) } == true) withFonts += pageIndex
                }
            }
            progress.advance(chunk.size)
        }
        progress.idle()
        return Blanked(found, withFonts)
    }
}
