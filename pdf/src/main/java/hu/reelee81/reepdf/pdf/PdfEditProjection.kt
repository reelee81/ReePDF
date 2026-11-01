package hu.reelee81.reepdf.pdf

import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDate
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfOutputStream
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfResources
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import hu.reelee81.reepdf.core.SourcePartPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

object PdfEditProjection {
    class Cell(val sourceIndex: Int, val matrix: DoubleArray)

    class NotProjectableException(message: String) : Exception(message)

    private val PIECE_NAME = PdfName("hureelee81reepdf")
    private val BASE = PdfName("Base")
    private val LAYER_FORM = PdfName("LayerForm")
    private val WRAP = PdfName("Wrap")
    private val LAYER = PdfName("Layer")
    private val SOURCE = PdfName("Source")
    private val GENERATION = PdfName("Gen")
    private val STREAMS = PdfName("Streams")
    private val LENGTH = PdfName("Length")
    private val CELLS = PdfName("Cells")
    private const val NAME_PREFIX = "NUE"
    private const val MAX_DEPTH = 8
    private const val SCAN_BATCH_FACTOR = 10

    fun newGeneration(): String = UUID.randomUUID().toString()

    fun markNupPage(page: PdfPage, cells: List<Cell>, generation: String) {
        val streams = page.contentStreamCount
        var length = 0L
        for (index in 0 until streams) length += page.getContentStream(index).bytes.size
        val array = PdfArray()
        for (cell in cells) {
            array.add(PdfNumber(cell.sourceIndex))
            for (value in cell.matrix) array.add(PdfNumber(value))
        }
        writablePrivateData(page.pdfObject).put(BASE, PdfDictionary().apply {
            put(STREAMS, PdfNumber(streams))
            put(LENGTH, PdfNumber(length.toDouble()))
            put(CELLS, array)
            put(GENERATION, PdfString(generation))
        })
    }

    fun tagLayerSource(form: PdfFormXObject, sourcePage: PdfPage, sourceIndex: Int, generation: String) {
        val layer = sourceLayer(sourcePage) ?: return
        val reference = layer.indirectReference
        val sourceXObjects = sourcePage.resources.pdfObject.getAsDictionary(PdfName.XObject) ?: return
        val name = sourceXObjects.keySet().firstOrNull { key ->
            val stream = sourceXObjects.getAsStream(key)
            stream === layer || (reference != null && stream?.indirectReference == reference)
        } ?: return
        val copied = form.pdfObject.getAsDictionary(PdfName.Resources)
            ?.getAsDictionary(PdfName.XObject)
            ?.getAsStream(name) ?: return
        writablePrivateData(copied).apply {
            put(LAYER, PdfBoolean.TRUE)
            put(SOURCE, PdfNumber(sourceIndex))
            put(GENERATION, PdfString(generation))
        }
    }

    fun isEditLayer(stream: PdfStream): Boolean = privateData(stream)?.getAsBool(LAYER) == true

    fun inverse(m: DoubleArray): DoubleArray? {
        val det = m[0] * m[3] - m[1] * m[2]
        if (!det.isFinite() || abs(det) < 1e-12) return null
        return doubleArrayOf(
            m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
            (m[2] * m[5] - m[3] * m[4]) / det,
            (m[1] * m[4] - m[0] * m[5]) / det
        )
    }

    fun hasLayers(
        open: () -> PdfReader,
        pages: Set<Int>?,
        batchSize: Int,
        onProgress: (Int) -> Unit = {},
        ensureActive: () -> Unit
    ): Boolean {
        val total = PdfDocument(open().setMemorySavingMode(true)).use { it.numberOfPages }
        val wanted = pages?.filter { it in 1..total }?.sorted() ?: (1..total).toList()
        for (chunk in wanted.chunked(batchSize.coerceAtLeast(1))) {
            ensureActive()
            val found = PdfDocument(open().setMemorySavingMode(true)).use { pdf ->
                chunk.any { number ->
                    val page = pdf.getPage(number)
                    appendedFrom(page) != null ||
                        layersOf(page).any { it.bytes.isNotEmpty() }
                }
            }
            if (found) return true
            onProgress(chunk.size)
        }
        return false
    }

    fun clearLayers(
        input: File,
        output: File,
        pages: Set<Int>?,
        batchSize: Int,
        ensureActive: () -> Unit
    ): Int = clearLayers({ PdfReader(input) }, output, pages, batchSize, ensureActive = ensureActive)

    fun clearLayers(
        open: () -> PdfReader,
        output: File,
        pages: Set<Int>?,
        batchSize: Int,
        onProgress: (Int) -> Unit = {},
        budget: Long = PdfBatchBudget.bytes(),
        ensureActive: () -> Unit
    ): Int {
        val total = PdfDocument(open().setMemorySavingMode(true)).use { it.numberOfPages }
        val batch = batchSize.coerceAtLeast(1)
        var cleared = 0
        PdfDocument(PdfWriter(output.absolutePath, PdfIo.writerProperties())).use { destination ->
            destination.setFlushUnusedObjects(true)
            var from = 1
            while (from <= total) {
                ensureActive()
                val to = PdfDocument(open().setMemorySavingMode(true)).use { src ->
                    val end = PdfPageCost.batchEnd(src, from, total, batch, budget)
                    src.copyPagesTo(from, end, destination)
                    for (number in from..end) {
                        if (pages != null && number !in pages) continue
                        val page = destination.getPage(number)
                        appendedFrom(page)?.let { streams ->
                            removeContentStreamsFrom(page, streams)
                            cleared++
                        }
                        for (layer in layersOf(page)) {
                            if (layer.bytes.isEmpty()) continue
                            layer.remove(PdfName.Filter)
                            layer.remove(PdfName.DecodeParms)
                            layer.setData(ByteArray(0))
                            cleared++
                        }
                    }
                    destination.flushCopiedObjects(src)
                    end
                }
                for (number in from..to) destination.getPage(number).flush()
                onProgress(to - from + 1)
                from = to + 1
            }
        }
        return cleared
    }

    private class Edit(val nupIndex: Int, val matrix: DoubleArray)

    class Projection(val changed: Int, val source: PdfSourceParts)

    fun project(
        source: PdfSourceParts,
        editedNup: File,
        batchSize: Int,
        onProgress: (Int) -> Unit = {},
        writeStage: (Long) -> PdfProgress,
        ensureActive: () -> Unit
    ): Projection {
        val bySource = HashMap<Int, MutableList<Edit>>()
        val removals = HashSet<Int>()
        val nupPages = PdfDocument(PdfReader(editedNup).setMemorySavingMode(true)).use { it.numberOfPages }
        val batch = batchSize.coerceAtLeast(1)
        val scanBatch = batch * SCAN_BATCH_FACTOR
        var from = 1
        while (from <= nupPages) {
            ensureActive()
            val to = min(from + scanBatch - 1, nupPages)
            PdfDocument(PdfReader(editedNup).setMemorySavingMode(true)).use { pdf ->
                for (number in from..to) {
                    val page = pdf.getPage(number)
                    val base = pageBase(page)
                        ?: throw NotProjectableException("page $number has no layout record")
                    for (layer in layersOf(page)) {
                        val sourceIndex = privateData(layer)?.getAsNumber(SOURCE)?.intValue() ?: continue
                        if (layer.bytes.isEmpty()) removals += sourceIndex
                    }
                    val streams = base.getAsNumber(STREAMS)?.intValue() ?: -1
                    if (page.contentStreamCount <= streams) continue
                    var length = 0L
                    for (index in 0 until streams) length += page.getContentStream(index).bytes.size
                    if (streams < 0 || length != base.getAsNumber(LENGTH)?.longValue()) {
                        throw NotProjectableException("page $number original content was modified")
                    }
                    val cells = base.getAsArray(CELLS) ?: continue
                    var offset = 0
                    while (offset + 7 <= cells.size()) {
                        val sourceIndex = cells.getAsNumber(offset).intValue()
                        val matrix = DoubleArray(6) { cells.getAsNumber(offset + 1 + it).doubleValue() }
                        bySource.getOrPut(sourceIndex) { ArrayList() } += Edit(number, matrix)
                        offset += 7
                    }
                }
            }
            onProgress(to - from + 1)
            from = to + 1
        }
        if (bySource.isEmpty() && removals.isEmpty()) return Projection(0, source)

        val changed = bySource.keys + removals
        if (changed.any { it !in 0 until source.total }) {
            throw NotProjectableException("layout record does not match the source")
        }
        val plan = SourcePartPlan.rewritePages(source.layout, changed)
        val written = source.write(
            plan,
            batch,
            writeStage(plan.writtenPages.toLong()),
            openPart = { PdfReader(it).setMemorySavingMode(true) }
        ) { first, last, destination ->
            ensureActive()
            val firstNumber = destination.numberOfPages - (last - first)
            for (page in first..last) {
                if (page in removals) removeLayer(destination.getPage(firstNumber + page - first))
            }
            val affected = (first..last).filter { bySource.containsKey(it) }
            if (affected.isNotEmpty()) {
                PdfDocument(PdfReader(editedNup).setMemorySavingMode(true)).use { nup ->
                    for (page in affected) {
                        val target = destination.getPage(firstNumber + page - first)
                        for (edit in bySource.getValue(page)) {
                            appendEdit(target, nup.getPage(edit.nupIndex), edit.matrix)
                        }
                    }
                    destination.flushCopiedObjects(nup)
                }
            }
        }
        return Projection(changed.size, written)
    }

    private fun appendedFrom(page: PdfPage): Int? {
        val base = pageBase(page) ?: return null
        val streams = base.getAsNumber(STREAMS)?.intValue() ?: return null
        if (streams < 0 || page.contentStreamCount <= streams) return null
        var length = 0L
        for (index in 0 until streams) length += page.getContentStream(index).bytes.size
        if (length != base.getAsNumber(LENGTH)?.longValue()) return null
        return streams
    }

    private fun removeContentStreamsFrom(page: PdfPage, streams: Int) {
        val contents = page.pdfObject.getAsArray(PdfName.Contents)
        if (contents == null) {
            if (streams == 0) page.pdfObject.remove(PdfName.Contents)
        } else {
            for (index in contents.size() - 1 downTo streams) contents.remove(index)
        }
        page.pdfObject.setModified()
    }

    internal fun privateData(dictionary: PdfDictionary): PdfDictionary? =
        dictionary.getAsDictionary(PdfName.PieceInfo)
            ?.getAsDictionary(PIECE_NAME)
            ?.getAsDictionary(PdfName.Private)

    internal fun writablePrivateData(dictionary: PdfDictionary): PdfDictionary {
        val modified = PdfDate().pdfObject
        val pieceInfo = dictionary.getAsDictionary(PdfName.PieceInfo)
            ?: PdfDictionary().also { dictionary.put(PdfName.PieceInfo, it) }
        val piece = pieceInfo.getAsDictionary(PIECE_NAME)
            ?: PdfDictionary().also { pieceInfo.put(PIECE_NAME, it) }
        piece.put(PdfName.LastModified, modified)
        dictionary.put(PdfName.LastModified, modified)
        return piece.getAsDictionary(PdfName.Private)
            ?: PdfDictionary().also { piece.put(PdfName.Private, it) }
    }

    private fun pageBase(page: PdfPage): PdfDictionary? = privateData(page.pdfObject)?.getAsDictionary(BASE)

    private fun sourceLayer(page: PdfPage): PdfStream? =
        privateData(page.pdfObject)?.getAsStream(LAYER_FORM)?.takeIf { ownsLayer(page, it) }

    private fun ownsLayer(page: PdfPage, layer: PdfStream): Boolean {
        val reference = layer.indirectReference
        val xObjects = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.XObject) ?: return false
        return xObjects.keySet().any { name ->
            val stream = xObjects.getAsStream(name)
            stream === layer || (reference != null && stream?.indirectReference == reference)
        }
    }

    internal fun unsharePrivateData(dictionary: PdfDictionary) {
        var parent = dictionary
        for (key in arrayOf(PdfName.PieceInfo, PIECE_NAME, PdfName.Private)) {
            val child = parent.getAsDictionary(key) ?: return
            val copy = PdfDictionary(child)
            parent.put(key, copy)
            parent = copy
        }
    }

    private fun layersOf(page: PdfPage): List<PdfStream> {
        val generation = pageBase(page)?.getAsString(GENERATION)?.value ?: return emptyList()
        return layersOf(page.resources.pdfObject, generation)
    }

    private fun layersOf(resources: PdfDictionary?, generation: String?): List<PdfStream> {
        val result = ArrayList<PdfStream>()
        val seen = HashSet<PdfStream>()
        fun visit(dictionary: PdfDictionary?, depth: Int) {
            if (depth >= MAX_DEPTH) return
            val xObjects = dictionary?.getAsDictionary(PdfName.XObject) ?: return
            for (name in xObjects.keySet()) {
                val stream = xObjects.getAsStream(name) ?: continue
                if (!seen.add(stream)) continue
                val data = privateData(stream)
                if (data?.getAsBool(LAYER) == true) {
                    if (generation == null || data.getAsString(GENERATION)?.value == generation) result += stream
                } else if (stream.getAsName(PdfName.Subtype) == PdfName.Form) {
                    visit(stream.getAsDictionary(PdfName.Resources), depth + 1)
                }
            }
        }
        visit(resources, 0)
        return result
    }

    private fun removeLayer(page: PdfPage) {
        val layer = sourceLayer(page) ?: return
        val reference = layer.indirectReference
        val wrap = privateData(page.pdfObject)?.getAsStream(WRAP)
        val wrapReference = wrap?.indirectReference
        privateData(page.pdfObject)?.apply {
            remove(LAYER_FORM)
            remove(WRAP)
        }
        val xObjects = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.XObject) ?: return
        val names = xObjects.keySet().filter { name ->
            val stream = xObjects.getAsStream(name)
            stream === layer || (reference != null && stream?.indirectReference == reference)
        }
        for (name in names) xObjects.remove(name)
        val contents = page.pdfObject.getAsArray(PdfName.Contents) ?: return
        var called = false
        for (index in contents.size() - 1 downTo 0) {
            val stream = contents.getAsStream(index) ?: continue
            if (wrap != null && (stream === wrap || (wrapReference != null && stream.indirectReference == wrapReference))) {
                if (called) contents.remove(index)
                continue
            }
            val text = String(stream.bytes, Charsets.ISO_8859_1)
            if (text.length <= 128 && names.any { text.contains("$it Do") }) {
                contents.remove(index)
                called = true
            }
        }
    }

    private fun appendEdit(target: PdfPage, nupPage: PdfPage, matrix: DoubleArray) {
        val inverse = inverse(matrix) ?: return
        val base = pageBase(nupPage) ?: return
        val firstNew = base.getAsNumber(STREAMS)?.intValue() ?: return
        val sourceResources = nupPage.resources.pdfObject
        val destination = target.document

        val existing = sourceLayer(target)
        if (existing == null) PdfCopySupport.unwrapSingleFormPage(target)
        val form = existing ?:PdfFormXObject(target.mediaBox).pdfObject.also {
            it.put(PdfName.Resources, PdfDictionary())
            writablePrivateData(it).put(LAYER, PdfBoolean.TRUE)
        }
        val formResources = form.getAsDictionary(PdfName.Resources)
            ?: PdfDictionary().also { form.put(PdfName.Resources, it) }

        val chunk = ByteArrayOutputStream()
        chunk.write("q\n".toByteArray(Charsets.ISO_8859_1))
        PdfOutputStream(chunk).use { out ->
            for (value in inverse) out.writeDouble(value, true).writeSpace()
            out.writeString("cm\n")
        }
        for (index in firstNew until nupPage.contentStreamCount) {
            val bytes = nupPage.getContentStream(index).bytes
            chunk.write(renameResources(bytes, sourceResources, formResources, destination))
            chunk.write('\n'.code)
        }
        chunk.write("Q\n".toByteArray(Charsets.ISO_8859_1))

        if (existing != null) {
            val previous = existing.bytes
            existing.remove(PdfName.Filter)
            existing.remove(PdfName.DecodeParms)
            existing.setData(previous + chunk.toByteArray())
        } else {
            form.setData(chunk.toByteArray())
            form.makeIndirect(destination)
            unsharePrivateData(target.pdfObject)
            privateData(target.pdfObject)?.remove(WRAP)
            val unclosed = unclosedSaves(target)
            if (unclosed != null) {
                val wrap = target.newContentStreamBefore()
                wrap.setData("q\n".toByteArray(Charsets.ISO_8859_1))
                writablePrivateData(target.pdfObject).put(WRAP, wrap)
            }
            writablePrivateData(target.pdfObject).put(LAYER_FORM, form)
            val ownResources = PdfDictionary(target.pdfObject.getAsDictionary(PdfName.Resources) ?: PdfDictionary())
            ownResources.put(
                PdfName.XObject,
                PdfDictionary(ownResources.getAsDictionary(PdfName.XObject) ?: PdfDictionary())
            )
            target.pdfObject.put(PdfName.Resources, ownResources)
            val call = target.newContentStreamAfter()
            if (unclosed != null) call.outputStream.writeString("Q\n".repeat(unclosed + 1))
            PdfCanvas(call, PdfResources(ownResources), destination)
                .saveState()
                .addXObjectWithTransformationMatrix(PdfFormXObject(form), 1f, 0f, 0f, 1f, 0f, 0f)
                .restoreState()
        }
    }

    private fun unclosedSaves(page: PdfPage): Int? {
        val content = ByteArrayOutputStream()
        for (index in 0 until page.contentStreamCount) {
            content.write(page.getContentStream(index).bytes)
            content.write('\n'.code)
        }
        return PdfCopySupport.unclosedSaves(content.toByteArray(), page.resources.pdfObject)
    }

    private fun renameResources(
        bytes: ByteArray,
        sourceResources: PdfDictionary,
        formResources: PdfDictionary,
        destination: PdfDocument
    ): ByteArray {
        val renamed = HashMap<Pair<PdfName, PdfName>, PdfName>()
        fun target(category: PdfName, name: PdfName): PdfName? {
            val value = sourceResources.getAsDictionary(category)?.get(name) ?: return null
            return renamed.getOrPut(category to name) {
                val bucket = formResources.getAsDictionary(category)
                    ?: PdfDictionary().also { formResources.put(category, it) }
                var counter = 1
                while (resourceNameUsed(formResources, PdfName("$NAME_PREFIX$counter"))) counter++
                val newName = PdfName("$NAME_PREFIX$counter")
                bucket.put(newName, value.copyTo(destination))
                newName
            }
        }

        val output = ByteArrayOutputStream(bytes.size + 64)
        val tokenizer = PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes)))
        tokenizer.use {
            val parser = PdfCanvasParser(tokenizer, PdfResources(sourceResources))
            val operands = ArrayList<PdfObject>()
            var start = 0
            while (parser.parse(operands).isNotEmpty()) {
                val end = tokenizer.position.toInt()
                val operator = operands.last().toString()
                val replacement = renameIndex(operator, operands)?.let { (index, category) ->
                    (operands[index] as? PdfName)?.let { name -> target(category, name)?.let { index to it } }
                }
                if (replacement == null) {
                    output.write(bytes, start, end - start)
                } else {
                    output.write('\n'.code)
                    PdfOutputStream(output).let { out ->
                        for (index in 0 until operands.size - 1) {
                            out.write(if (index == replacement.first) replacement.second else operands[index])
                            out.writeSpace()
                        }
                        out.writeString(operator)
                        out.flush()
                    }
                }
                start = end
            }
            output.write(bytes, start, bytes.size - start)
        }
        return output.toByteArray()
    }

    private fun renameIndex(operator: String, operands: List<PdfObject>): Pair<Int, PdfName>? = when (operator) {
        "Do" -> 0 to PdfName.XObject
        "gs" -> 0 to PdfName.ExtGState
        "Tf" -> 0 to PdfName.Font
        "cs", "CS" -> 0 to PdfName.ColorSpace
        "sh" -> 0 to PdfName.Shading
        "scn", "SCN" -> if (operands.size >= 2 && operands[operands.size - 2] is PdfName) {
            (operands.size - 2) to PdfName.Pattern
        } else null
        "BDC", "DP" -> if (operands.size >= 3 && operands[1] is PdfName) 1 to PdfName.Properties else null
        else -> null
    }

    private fun resourceNameUsed(resources: PdfDictionary, name: PdfName): Boolean =
        resources.keySet().any { category -> resources.getAsDictionary(category)?.containsKey(name) == true }
}
