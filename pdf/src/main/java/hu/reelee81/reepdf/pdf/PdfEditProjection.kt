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
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import hu.reelee81.reepdf.core.NupArea
import hu.reelee81.reepdf.core.NupBox
import hu.reelee81.reepdf.core.NupCellArea
import hu.reelee81.reepdf.core.SourcePartPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PdfEditProjection {
    class Cell(val sourceIndex: Int, val matrix: DoubleArray, val box: NupBox? = null, val slot: NupBox? = null)

    class NotProjectableException(message: String) : Exception(message)

    private val PIECE_NAME = PdfName("hureelee81reepdf")
    private val BASE = PdfName("Base")
    private val LAYER_FORM = PdfName("LayerForm")
    private val CONTENT_FORM = PdfName("ContentForm")
    private val WRAP = PdfName("Wrap")
    private val LAYER = PdfName("Layer")
    private val SOURCE = PdfName("Source")
    private val GENERATION = PdfName("Gen")
    private val MERGED = PdfName("Merged")
    private val STREAMS = PdfName("Streams")
    private val LENGTH = PdfName("Length")
    private val CELLS = PdfName("Cells")
    private val AREAS = PdfName("Areas")
    internal val EDITED = PdfName("Edited")
    private val RESOURCE_KEYS = listOf(
        PdfName.ExtGState,
        PdfName.ColorSpace,
        PdfName.Pattern,
        PdfName.Shading,
        PdfName.XObject,
        PdfName.Font,
        PdfName.ProcSet,
        PdfName.Properties
    )
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
            for (value in cell.matrix) array.add(precise(value))
        }
        val record = PdfDictionary().apply {
            put(STREAMS, PdfNumber(streams))
            put(LENGTH, PdfNumber(length.toDouble()))
            put(CELLS, array)
            put(GENERATION, PdfString(generation))
        }
        if (cells.isNotEmpty() && cells.all { it.box != null && it.slot != null }) {
            val areas = PdfArray()
            for (cell in cells) {
                for ((x, y, width, height) in listOfNotNull(cell.box, cell.slot)) {
                    for (value in floatArrayOf(x, y, width, height)) areas.add(precise(value.toDouble()))
                }
            }
            record.put(AREAS, areas)
        }
        writablePrivateData(page.pdfObject).put(BASE, record)
    }

    private fun precise(value: Double): PdfNumber {
        val bytes = ByteArrayOutputStream()
        PdfOutputStream(bytes).use { it.writeDouble(value, true) }
        return PdfNumber(bytes.toByteArray())
    }

    internal fun cells(base: PdfDictionary): List<Cell> {
        val values = base.getAsArray(CELLS) ?: return emptyList()
        val count = values.size() / 7
        val areas = base.getAsArray(AREAS)
            ?.takeIf { it.size() == count * 8 }
            ?.let { array -> DoubleArray(array.size()) { array.getAsNumber(it)?.doubleValue() ?: Double.NaN } }
            ?.takeIf { numbers -> numbers.all { it.isFinite() } }
        return List(count) { index ->
            val offset = index * 7
            val matrix = DoubleArray(6) { values.getAsNumber(offset + 1 + it).doubleValue() }
            val box = areas?.let { box(it, index * 8) }
            val slot = areas?.let { box(it, index * 8 + 4) }
            Cell(values.getAsNumber(offset).intValue(), matrix, box, slot)
        }
    }

    private fun box(values: DoubleArray, offset: Int) =
        NupBox(values[offset].toFloat(), values[offset + 1].toFloat(), values[offset + 2].toFloat(), values[offset + 3].toFloat())

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

    fun tagLayerCopy(original: PdfStream, copy: PdfStream, sourcePage: PdfPage, sourceIndex: Int, generation: String) {
        val layer = sourceLayer(sourcePage) ?: return
        val reference = layer.indirectReference
        if (original !== layer && (reference == null || original.indirectReference != reference)) return
        writablePrivateData(copy).apply {
            put(LAYER, PdfBoolean.TRUE)
            put(SOURCE, PdfNumber(sourceIndex))
            put(GENERATION, PdfString(generation))
        }
    }

    fun isEditLayer(stream: PdfStream): Boolean = privateData(stream)?.getAsBool(LAYER) == true

    internal fun isOwnLayer(page: PdfPage, stream: PdfStream): Boolean {
        val layer = sourceLayer(page) ?: return false
        val reference = layer.indirectReference
        return stream === layer || (reference != null && stream.indirectReference == reference)
    }

    internal fun isMergedLayer(stream: PdfStream): Boolean = privateData(stream)?.getAsBool(MERGED) == true

    internal fun tagMergedLayer(stream: PdfStream) {
        writablePrivateData(stream).apply {
            put(LAYER, PdfBoolean.TRUE)
            put(MERGED, PdfBoolean.TRUE)
        }
    }

    fun inverse(m: DoubleArray): DoubleArray? {
        val det = m[0] * m[3] - m[1] * m[2]
        if (!det.isFinite() || abs(det) < 1e-12) return null
        return doubleArrayOf(
            m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
            (m[2] * m[5] - m[3] * m[4]) / det,
            (m[1] * m[4] - m[0] * m[5]) / det
        )
    }

    private class Piece(val stream: Int, val start: Int, val end: Int)

    private class Edit(val nupIndex: Int, val matrix: DoubleArray, val area: NupArea?, val pieces: List<Piece>?)

    private class StreamMarks(val index: Int, val size: Int, val marks: List<PdfMarkContent.Stored>, val other: Boolean)

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
        val replacements = HashMap<Int, ByteArray>()
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
                        val data = privateData(layer)
                        val sourceIndex = data?.getAsNumber(SOURCE)?.intValue() ?: continue
                        val bytes = layer.bytes
                        if (bytes.isEmpty()) {
                            removals += sourceIndex
                        } else if (data.getAsBool(EDITED) == true) {
                            if (PdfMarkContent.isBlank(bytes)) removals += sourceIndex else replacements[sourceIndex] = bytes
                        }
                    }
                    val streams = base.getAsNumber(STREAMS)?.intValue() ?: -1
                    if (page.contentStreamCount <= streams) continue
                    var length = 0L
                    for (index in 0 until streams) length += page.getContentStream(index).bytes.size
                    if (streams < 0 || length != base.getAsNumber(LENGTH)?.longValue()) {
                        throw NotProjectableException("page $number original content was modified")
                    }
                    val appended = (streams until page.contentStreamCount).map { it to page.getContentStream(it).bytes }
                    if (appended.all { PdfMarkContent.isBlank(it.second) }) continue
                    val cells = cells(base)
                    if (cells.any { it.box == null || it.slot == null }) {
                        for (cell in cells) bySource.getOrPut(cell.sourceIndex) { ArrayList() } += Edit(number, cell.matrix, null, null)
                        continue
                    }
                    val content = appended.map { (index, bytes) ->
                        val marks = PdfMarkContent.read(bytes)
                        StreamMarks(index, bytes.size, marks, PdfMarkContent.hasOtherContent(bytes, marks))
                    }
                    for (cell in cells) {
                        val box = cell.box ?: continue
                        val slot = cell.slot ?: continue
                        val area = NupCellArea.of(box, cell.matrix, slot) ?: continue
                        val pieces = ArrayList<Piece>()
                        for (stream in content) {
                            if (stream.other) {
                                pieces += Piece(stream.index, 0, stream.size)
                            } else {
                                for (stored in stream.marks) if (touches(stored.mark, area)) pieces += Piece(stream.index, stored.start, stored.end)
                            }
                        }
                        if (pieces.isNotEmpty()) bySource.getOrPut(cell.sourceIndex) { ArrayList() } += Edit(number, cell.matrix, area, pieces)
                    }
                }
            }
            onProgress(to - from + 1)
            from = to + 1
        }
        removals.forEach(replacements::remove)
        if (bySource.isEmpty() && removals.isEmpty() && replacements.isEmpty()) return Projection(0, source)

        val changed = bySource.keys + removals + replacements.keys
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
                val target = destination.getPage(firstNumber + page - first)
                if (page in removals) removeLayer(target) else replacements[page]?.let { replaceLayer(target, it) }
            }
            val affected = (first..last).filter { bySource.containsKey(it) }
            if (affected.isNotEmpty()) {
                PdfDocument(PdfReader(editedNup).setMemorySavingMode(true)).use { nup ->
                    for (page in affected) {
                        val target = destination.getPage(firstNumber + page - first)
                        for (edit in bySource.getValue(page)) {
                            appendEdit(target, nup.getPage(edit.nupIndex), edit)
                        }
                    }
                    destination.flushCopiedObjects(nup)
                }
            }
        }
        return Projection(changed.size, written)
    }

    private fun touches(mark: PdfMark, area: NupArea): Boolean {
        val points = mark.path.points
        if (points.isEmpty()) return false
        var left = Double.POSITIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        var index = 0
        while (index + 1 < points.size) {
            left = min(left, points[index].toDouble())
            right = max(right, points[index].toDouble())
            bottom = min(bottom, points[index + 1].toDouble())
            top = max(top, points[index + 1].toDouble())
            index += 2
        }
        val reach = if (mark.kind.fills) 0.0 else mark.width / 2.0
        return area.intersects(left - reach, bottom - reach, right + reach, top + reach)
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
        privateData(page.pdfObject)?.getAsStream(LAYER_FORM)?.takeIf { ownsForm(page, it) }

    private fun ownsForm(page: PdfPage, form: PdfStream): Boolean {
        val reference = form.indirectReference
        val xObjects = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.XObject) ?: return false
        return xObjects.keySet().any { name ->
            val stream = xObjects.getAsStream(name)
            stream === form || (reference != null && stream?.indirectReference == reference)
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

    internal fun editStreams(page: PdfPage): List<PdfStream> {
        val result = ArrayList<PdfStream>()
        appendedFrom(page)?.let { first -> for (index in first until page.contentStreamCount) result += page.getContentStream(index) }
        result += layersOf(page)
        return result
    }

    private fun replaceLayer(page: PdfPage, content: ByteArray) {
        val layer = sourceLayer(page) ?: return
        layer.remove(PdfName.Filter)
        layer.remove(PdfName.DecodeParms)
        layer.setData(content)
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
        val contents = page.pdfObject.getAsArray(PdfName.Contents)
        if (contents != null) {
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
        restoreContent(page)
    }

    private fun appendEdit(target: PdfPage, nupPage: PdfPage, edit: Edit) {
        val inverse = inverse(edit.matrix) ?: return
        val base = pageBase(nupPage) ?: return
        val firstNew = base.getAsNumber(STREAMS)?.intValue() ?: return
        val sourceResources = nupPage.resources.pdfObject
        val destination = target.document

        val existing = sourceLayer(target)
        val form = existing ?: PdfFormXObject(target.mediaBox).pdfObject.also {
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
            edit.area?.let { area ->
                for (value in doubleArrayOf(area.left, area.bottom, area.right - area.left, area.top - area.bottom)) {
                    out.writeDouble(value, true).writeSpace()
                }
                out.writeString("re W n\n")
            }
        }
        val pieces = edit.pieces
        if (pieces == null) {
            for (index in firstNew until nupPage.contentStreamCount) {
                val bytes = nupPage.getContentStream(index).bytes
                chunk.write(renameResources(bytes, sourceResources, formResources, destination))
                chunk.write('\n'.code)
            }
        } else {
            for ((stream, selected) in pieces.groupBy { it.stream }) {
                val bytes = nupPage.getContentStream(stream).bytes
                val joined = ByteArrayOutputStream()
                for (piece in selected) {
                    joined.write(bytes, piece.start, piece.end - piece.start)
                    joined.write('\n'.code)
                }
                chunk.write(renameResources(joined.toByteArray(), sourceResources, formResources, destination))
                chunk.write('\n'.code)
            }
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
            privateData(target.pdfObject)?.apply {
                remove(WRAP)
                remove(CONTENT_FORM)
            }
            val single = if (target.contentStreamCount == 1) target.getContentStream(0).bytes else null
            val content = single ?: pageContent(target)
            val previous = target.resources.pdfObject
            val keepsContent = PdfCopySupport.onlyPlacesForms(content, previous)
            if (keepsContent) {
                val wrap = target.newContentStreamBefore()
                wrap.setData("q\n".toByteArray(Charsets.ISO_8859_1))
                writablePrivateData(target.pdfObject).put(WRAP, wrap)
            }
            writablePrivateData(target.pdfObject).put(LAYER_FORM, form)
            val kept = PdfDictionary()
            for (key in RESOURCE_KEYS) previous.get(key, false)?.let { kept.put(key, it) }
            val original = if (keepsContent) {
                null
            } else {
                val scan = PdfCopySupport.scanContent(content, previous)
                val unclosed = scan.unclosed.takeUnless { scan.broken } ?: 0
                kept.makeIndirect(destination)
                PdfFormXObject(target.mediaBox).also { original ->
                    val tail = (if (single != null) "\n" else "") + "Q\n".repeat(unclosed)
                    original.pdfObject.setData(content + tail.toByteArray(Charsets.ISO_8859_1))
                    original.pdfObject.put(PdfName.Resources, kept)
                    target.pdfObject.get(PdfName.Group, false)?.let { group ->
                        original.pdfObject.put(PdfName.Group, group)
                        target.pdfObject.remove(PdfName.Group)
                    }
                    original.pdfObject.makeIndirect(destination)
                    writablePrivateData(target.pdfObject).put(CONTENT_FORM, original.pdfObject)
                }
            }
            val ownResources = if (original != null) PdfDictionary() else kept
            ownResources.put(PdfName.XObject, PdfDictionary(ownResources.getAsDictionary(PdfName.XObject) ?: PdfDictionary()))
            ownResources.makeIndirect(destination)
            target.pdfObject.put(PdfName.Resources, ownResources)
            if (original != null) {
                target.pdfObject.remove(PdfName.Contents)
                PdfCanvas(target.newContentStreamAfter(), PdfResources(ownResources), destination)
                    .saveState()
                    .addXObjectWithTransformationMatrix(original, 1f, 0f, 0f, 1f, 0f, 0f)
                    .restoreState()
            }
            val call = target.newContentStreamAfter()
            if (keepsContent) call.outputStream.writeString("Q\n")
            PdfCanvas(call, PdfResources(ownResources), destination)
                .saveState()
                .addXObjectWithTransformationMatrix(PdfFormXObject(form), 1f, 0f, 0f, 1f, 0f, 0f)
                .restoreState()
        }
    }

    private fun pageContent(page: PdfPage): ByteArray {
        val content = ByteArrayOutputStream()
        for (index in 0 until page.contentStreamCount) {
            content.write(page.getContentStream(index).bytes)
            content.write('\n'.code)
        }
        return content.toByteArray()
    }

    private fun restoreContent(page: PdfPage) {
        val data = privateData(page.pdfObject) ?: return
        val original = data.getAsStream(CONTENT_FORM) ?: return
        data.remove(CONTENT_FORM)
        if (!ownsForm(page, original)) return
        page.pdfObject.put(PdfName.Contents, PdfStream(original.bytes).makeIndirect(page.document))
        page.pdfObject.put(PdfName.Resources, original.get(PdfName.Resources, false) ?: PdfDictionary())
        original.get(PdfName.Group, false)?.let { page.pdfObject.put(PdfName.Group, it) }
        page.pdfObject.setModified()
    }

    internal fun renameResources(
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
                val end = minOf(tokenizer.position.toInt(), bytes.size)
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
