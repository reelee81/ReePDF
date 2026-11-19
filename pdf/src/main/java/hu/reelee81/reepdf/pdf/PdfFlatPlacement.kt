package hu.reelee81.reepdf.pdf

import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfOutputStream
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfResources
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.util.InlineImageParsingUtils
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object PdfFlatPlacement {
    private const val MAX_DEPTH = 64
    private const val MAX_INLINED_FORM_BYTES = 16 * 1024
    private const val MAX_PARSED_BYTES = 256 * 1024
    private const val MAX_ITEMS = 512
    private const val EPSILON = 1e-6
    private const val CONTAINS_TOLERANCE = 1e-3

    private val GRAPHICS_OPERATORS = mapOf(
        "w" to "w", "J" to "J", "j" to "j", "M" to "M", "d" to "d", "i" to "i",
        "g" to "fill", "rg" to "fill", "k" to "fill",
        "G" to "stroke", "RG" to "stroke", "K" to "stroke"
    )
    private val PATH_OPERATORS = setOf("m", "l", "c", "v", "y", "h", "re")
    private val PAINT_OPERATORS = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")

    private class State(
        val ctm: DoubleArray,
        var clip: DoubleArray?,
        val ops: MutableList<ByteArray>,
        val graphics: LinkedHashMap<String, ByteArray>
    ) {
        fun copy() = State(ctm.copyOf(), clip?.copyOf(), ArrayList(ops), LinkedHashMap(graphics))
    }

    private sealed class Item(val state: State)
    private class FormItem(state: State, val form: PdfStream) : Item(state)
    private class PaintItem(state: State, val bytes: ByteArray) : Item(state)

    private class Budget(var bytes: Int) {
        var exceeded = false

        fun take(count: Int): Boolean {
            bytes -= count
            if (bytes < 0) exceeded = true
            return !exceeded
        }
    }

    private class Frames(private val output: ByteArrayOutputStream) {
        private val ends = ArrayList<Int>()
        private var opened: List<ByteArray> = emptyList()

        fun enter(ops: List<ByteArray>) {
            var common = 0
            while (common < opened.size && common < ops.size && opened[common] === ops[common]) common++
            while (ends.isNotEmpty() && ends.last() > common) {
                ends.removeAt(ends.size - 1)
                output.writeLatin1("\nQ")
            }
            val kept = ends.lastOrNull() ?: 0
            if (ops.size > kept) {
                output.writeLatin1("\nq")
                for (op in ops.subList(kept, ops.size)) {
                    output.writeLatin1("\n")
                    output.write(op)
                }
                ends += ops.size
            }
            opened = ops
        }
    }

    private class PathOp(val operator: String, val values: DoubleArray, val bytes: ByteArray)

    private class Path {
        val ops = ArrayList<PathOp>()
        var clip: String? = null

        fun bytes(): ByteArray {
            val output = ByteArrayOutputStream()
            for (op in ops) output.write(op.bytes)
            return output.toByteArray()
        }

        fun clear() {
            ops.clear()
            clip = null
        }
    }

    fun place(
        page: PdfPage,
        sheet: PdfPage,
        canvas: PdfCanvas,
        bBox: Rectangle,
        wholeSheet: Boolean,
        onCopied: (original: PdfStream, copy: PdfStream) -> Unit = { _, _ -> }
    ): Boolean {
        if (page.rotation % 360 != 0) return false
        val group = page.pdfObject.getAsDictionary(PdfName.Group)
        if (group != null && (!wholeSheet || sheet.pdfObject.containsKey(PdfName.Group))) return false
        val resources = page.resources.pdfObject
        val xObjects = resources.getAsDictionary(PdfName.XObject) ?: return false
        if (xObjects.keySet().none { xObjects.getAsStream(it)?.getAsName(PdfName.Subtype) == PdfName.Form }) return false
        if (rawContentLength(page) > MAX_PARSED_BYTES) return false
        val base = doubleArrayOf(bBox.left.toDouble(), bBox.bottom.toDouble(), bBox.right.toDouble(), bBox.top.toDouble())
        val items = ArrayList<Item>()
        val budget = Budget(MAX_PARSED_BYTES)
        val forms = flatten(contentOf(page), resources, initialState(), base, 0, HashSet(), items, budget) ?: return false
        if (forms == 0 || budget.exceeded) return false

        val destination = sheet.document
        val names = IdentityHashMap<PdfStream, PdfName>()
        fun nameOf(form: PdfStream): PdfName = names.getOrPut(form) {
            val copy = form.copyTo(destination, false) as PdfStream
            onCopied(form, copy)
            canvas.resources.addForm(PdfFormXObject(copy))
        }

        val output = ByteArrayOutputStream()
        output.writeOperator(doubleArrayOf(base[0], base[1], base[2] - base[0], base[3] - base[1]), "re W n")
        val frames = Frames(output)
        var index = 0
        while (index < items.size) {
            val end = mergeableRunEnd(items, index, page)
            val merged = if (end > index) merged(items.subList(index, end), base, destination) else null
            if (merged != null) {
                frames.enter(emptyList())
                output.writeLatin1("\n")
                output.writeName(canvas.resources.addForm(merged))
                output.writeLatin1(" Do")
                index = end
                continue
            }
            val item = items[index]
            frames.enter(item.state.ops)
            when (item) {
                is FormItem -> {
                    val body = ByteArrayOutputStream()
                    body.writeName(nameOf(item.form))
                    body.writeLatin1(" Do")
                    output.writeBody(item.state.graphics, body.toByteArray())
                }
                is PaintItem -> output.writeBody(item.state.graphics, item.bytes)
            }
            index++
        }
        frames.enter(emptyList())
        output.writeLatin1("\n")
        if (group != null) sheet.pdfObject.put(PdfName.Group, group.copyTo(destination, false))
        canvas.contentStream.outputStream.writeBytes(output.toByteArray())
        return true
    }

    private fun initialState() = State(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), null, ArrayList(), LinkedHashMap())

    private fun isOldLayer(page: PdfPage, form: PdfStream): Boolean =
        PdfEditProjection.isEditLayer(form) && !PdfEditProjection.isOwnLayer(page, form)

    private fun mergeableRunEnd(items: List<Item>, start: Int, page: PdfPage): Int {
        val first = items[start]
        if (first !is FormItem || !isOldLayer(page, first.form)) return -1
        var layers = 0
        var end = -1
        var index = start
        while (index < items.size) {
            val item = items[index]
            if (item is FormItem) {
                if (!isOldLayer(page, item.form)) break
                layers++
                end = index + 1
            }
            index++
        }
        return if (layers >= 2) end else -1
    }

    private fun merged(run: List<Item>, base: DoubleArray, destination: PdfDocument): PdfFormXObject? {
        if (!run.all { it !is FormItem || inlinable(it.form) }) return null
        val resources = PdfDictionary()
        val content = ByteArrayOutputStream()
        for (item in run) {
            when (item) {
                is PaintItem -> content.writeItem(item.state, item.bytes, selfContained = true)
                is FormItem -> if (!appendLayer(content, item, base, resources, destination)) return null
            }
        }
        val width = base[2] - base[0]
        val height = base[3] - base[1]
        val margin = max(max(width, height), 1.0)
        val box = Rectangle(
            (base[0] - margin).toFloat(), (base[1] - margin).toFloat(),
            (width + 2 * margin).toFloat(), (height + 2 * margin).toFloat()
        )
        val form = PdfFormXObject(box)
        form.pdfObject.setData(content.toByteArray())
        form.pdfObject.put(PdfName.Resources, resources)
        PdfEditProjection.tagMergedLayer(form.pdfObject)
        form.pdfObject.makeIndirect(destination)
        return form
    }

    private fun inlinable(form: PdfStream): Boolean {
        if (form.getAsArray(PdfName.Matrix)?.let { PdfCopySupport.matrixValues(it) } == null && form.containsKey(PdfName.Matrix)) return false
        if (form.getAsArray(PdfName.BBox)?.takeIf { it.size() == 4 } == null) return false
        if (form.containsKey(PdfName.Group) || form.containsKey(PdfName.OC)) return false
        val resources = form.getAsDictionary(PdfName.Resources) ?: PdfDictionary()
        if ((resources.getAsDictionary(PdfName.Pattern)?.size() ?: 0) > 0) return false
        val scan = PdfCopySupport.scanContent(form.bytes, resources)
        return !scan.broken && scan.unclosed != null
    }

    private fun appendLayer(
        content: ByteArrayOutputStream,
        item: FormItem,
        base: DoubleArray,
        resources: PdfDictionary,
        destination: PdfDocument
    ): Boolean {
        val form = item.form
        val formResources = form.getAsDictionary(PdfName.Resources) ?: PdfDictionary()
        val bytes = form.bytes
        val scan = PdfCopySupport.scanContent(bytes, formResources)
        if (scan.broken) return false
        val unclosed = scan.unclosed ?: return false
        val state = item.state.copy()
        if (!enterForm(state, form, base)) return false
        val renamed = PdfEditProjection.renameResources(bytes, formResources, resources, destination)
        if (PdfEditProjection.isMergedLayer(form) && unclosed == 0) {
            val blocks = blocks(renamed, resources, state, base)
            if (blocks != null) {
                content.write(blocks)
                return true
            }
        }
        val body = ByteArrayOutputStream()
        body.write(renamed)
        repeat(unclosed) { body.writeLatin1("\nQ") }
        content.writeItem(state, body.toByteArray())
        return true
    }

    private fun blocks(bytes: ByteArray, resources: PdfDictionary, outer: State, base: DoubleArray): ByteArray? {
        val output = ByteArrayOutputStream()
        try {
            PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes))).use { tokenizer ->
                val parser = PdfCanvasParser(tokenizer, PdfResources(resources))
                val operands = ArrayList<PdfObject>()
                var depth = 0
                var state = outer
                var prefix = false
                var bodyStart = -1
                val path = Path()
                var pathStart = -1
                var previousEnd = 0
                while (parser.parse(operands).isNotEmpty()) {
                    val start = previousEnd
                    val operator = operands.last().toString()
                    val arguments = operands.subList(0, operands.size - 1)
                    if (operator == "BI") InlineImageParsingUtils.parse(parser, resources.getAsDictionary(PdfName.ColorSpace))
                    val end = min(tokenizer.position.toInt(), bytes.size)
                    previousEnd = end
                    if (depth == 0) {
                        if (operator != "q") return null
                        depth = 1
                        state = outer.copy()
                        prefix = true
                        bodyStart = -1
                        path.clear()
                        pathStart = -1
                        continue
                    }
                    if (prefix && depth == 1) {
                        val numeric = arguments.all(PdfCopySupport::isNumeric)
                        val absorbed = when {
                            operator == "cm" && numeric && arguments.size == 6 && path.ops.isEmpty() -> {
                                applyCm(state, values(arguments), bytes.copyOfRange(start, end))
                                true
                            }
                            operator in GRAPHICS_OPERATORS && numeric && path.ops.isEmpty() -> {
                                state.graphics[GRAPHICS_OPERATORS.getValue(operator)] = bytes.copyOfRange(start, end)
                                true
                            }
                            operator in PATH_OPERATORS && numeric && path.clip == null -> {
                                if (pathStart < 0) pathStart = start
                                path.ops += PathOp(operator, values(arguments), bytes.copyOfRange(start, end))
                                true
                            }
                            (operator == "W" || operator == "W*") && path.ops.isNotEmpty() && path.clip == null -> {
                                path.clip = operator
                                true
                            }
                            operator == "n" && path.ops.isNotEmpty() -> {
                                if (path.clip != null) applyClip(state, path, base)
                                path.clear()
                                pathStart = -1
                                true
                            }
                            else -> false
                        }
                        if (absorbed) continue
                        if (operator == "Q" && path.ops.isNotEmpty()) return null
                        prefix = false
                        bodyStart = if (pathStart >= 0) pathStart else start
                    }
                    if (operator == "q") depth++
                    if (operator == "Q") {
                        depth--
                        if (depth == 0) output.writeItem(state, bytes.copyOfRange(bodyStart, start))
                    }
                }
                if (depth != 0) return null
            }
        } catch (_: Exception) {
            return null
        }
        return output.toByteArray()
    }

    private fun flatten(
        content: ByteArray,
        resources: PdfDictionary,
        state: State,
        base: DoubleArray,
        depth: Int,
        chain: MutableSet<PdfStream>,
        items: MutableList<Item>,
        budget: Budget
    ): Int? {
        if (!budget.take(content.size)) return null
        var current = state
        val saved = ArrayList<State>()
        val path = Path()
        var forms = 0
        try {
            PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(content))).use { tokenizer ->
                val parser = PdfCanvasParser(tokenizer, PdfResources(PdfDictionary()))
                val operands = ArrayList<PdfObject>()
                var previousEnd = 0
                while (parser.parse(operands).isNotEmpty()) {
                    val start = previousEnd
                    val end = min(tokenizer.position.toInt(), content.size)
                    previousEnd = end
                    val operator = operands.last().toString()
                    val arguments = operands.subList(0, operands.size - 1)
                    val literal = content.copyOfRange(start, end)
                    if (operator != "Do" && !arguments.all(PdfCopySupport::isNumeric)) return null
                    when (operator) {
                        "q" -> {
                            if (path.ops.isNotEmpty()) return null
                            saved += current.copy()
                        }
                        "Q" -> {
                            if (path.ops.isNotEmpty() || saved.isEmpty()) return null
                            current = saved.removeAt(saved.size - 1)
                        }
                        "cm" -> {
                            if (arguments.size != 6 || path.ops.isNotEmpty()) return null
                            applyCm(current, values(arguments), literal)
                        }
                        in GRAPHICS_OPERATORS -> {
                            if (path.ops.isNotEmpty()) return null
                            current.graphics[GRAPHICS_OPERATORS.getValue(operator)] = literal
                        }
                        in PATH_OPERATORS -> {
                            if (path.clip != null) return null
                            path.ops += PathOp(operator, values(arguments), literal)
                        }
                        "W", "W*" -> {
                            if (path.ops.isEmpty() || path.clip != null) return null
                            path.clip = operator
                        }
                        "n" -> {
                            if (path.clip != null) applyClip(current, path, base)
                            path.clear()
                        }
                        in PAINT_OPERATORS -> {
                            if (path.ops.isNotEmpty()) {
                                val bytes = ByteArrayOutputStream()
                                bytes.write(path.bytes())
                                bytes.write(literal)
                                items += PaintItem(current.copy(), bytes.toByteArray())
                                if (path.clip != null) applyClip(current, path, base)
                            }
                            path.clear()
                        }
                        "Do" -> {
                            if (path.ops.isNotEmpty() || arguments.size != 1) return null
                            val name = arguments[0] as? PdfName ?: return null
                            val form = resources.getAsDictionary(PdfName.XObject)?.getAsStream(name) ?: return null
                            if (form.getAsName(PdfName.Subtype) != PdfName.Form) return null
                            forms++
                            if (!dissolve(form, current, base, depth, chain, items, budget)) items += FormItem(current.copy(), form)
                        }
                        else -> return null
                    }
                    if (items.size > MAX_ITEMS) {
                        budget.exceeded = true
                        return null
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return if (path.ops.isEmpty()) forms else null
    }

    private fun dissolve(
        form: PdfStream,
        state: State,
        base: DoubleArray,
        depth: Int,
        chain: MutableSet<PdfStream>,
        items: MutableList<Item>,
        budget: Budget
    ): Boolean {
        if (depth >= MAX_DEPTH || form in chain) return false
        if (!PdfCopySupport.isPlainForm(form) || form.containsKey(PdfName.Group)) return false
        val resources = form.getAsDictionary(PdfName.Resources) ?: return false
        if ((form.getAsNumber(PdfName.Length)?.intValue() ?: 0) > MAX_INLINED_FORM_BYTES) return false
        val content = form.bytes
        if (content.size > MAX_INLINED_FORM_BYTES) return false

        val inner = state.copy()
        if (!enterForm(inner, form, base)) return false
        val found = ArrayList<Item>()
        chain.add(form)
        val forms = try {
            flatten(content, resources, inner, base, depth + 1, chain, found, budget)
        } finally {
            chain.remove(form)
        }
        if (forms == null || forms == 0) return false
        items.addAll(found)
        return true
    }

    private fun values(arguments: List<PdfObject>): DoubleArray =
        DoubleArray(arguments.size) { (arguments[it] as PdfNumber).doubleValue() }

    private fun concat(state: State, m: DoubleArray) {
        val c = state.ctm.copyOf()
        state.ctm[0] = m[0] * c[0] + m[1] * c[2]
        state.ctm[1] = m[0] * c[1] + m[1] * c[3]
        state.ctm[2] = m[2] * c[0] + m[3] * c[2]
        state.ctm[3] = m[2] * c[1] + m[3] * c[3]
        state.ctm[4] = m[4] * c[0] + m[5] * c[2] + c[4]
        state.ctm[5] = m[4] * c[1] + m[5] * c[3] + c[5]
    }

    private fun axisAligned(m: DoubleArray): Boolean =
        (abs(m[1]) < EPSILON && abs(m[2]) < EPSILON) || (abs(m[0]) < EPSILON && abs(m[3]) < EPSILON)

    private fun enterForm(state: State, form: PdfStream, base: DoubleArray): Boolean {
        val matrixArray = form.getAsArray(PdfName.Matrix)
        if (matrixArray != null) {
            val matrix = PdfCopySupport.matrixValues(matrixArray) ?: return false
            val literal = ByteArrayOutputStream()
            literal.writeNumbers(matrixArray.toList(), "cm")
            applyCm(state, matrix, literal.toByteArray())
        }
        val box = form.getAsArray(PdfName.BBox)?.takeIf { it.size() == 4 } ?: return false
        val corners = DoubleArray(4) { box.getAsNumber(it)?.doubleValue() ?: return false }
        val literal = ByteArrayOutputStream()
        literal.writeNumbers(listOf(box.get(0), box.get(1)), "m")
        literal.writeNumbers(listOf(box.get(2), box.get(1)), "l")
        literal.writeNumbers(listOf(box.get(2), box.get(3)), "l")
        literal.writeNumbers(listOf(box.get(0), box.get(3)), "l")
        literal.writeLatin1("\nh")
        val path = Path()
        path.ops += PathOp("box", corners, literal.toByteArray())
        path.clip = "W"
        applyClip(state, path, base)
        return true
    }

    private fun applyCm(state: State, m: DoubleArray, literal: ByteArray) {
        if (!isIdentity(m)) state.ops += literal
        concat(state, m)
    }

    private fun applyClip(state: State, path: Path, base: DoubleArray) {
        val single = path.ops.singleOrNull()
        val m = state.ctm
        if (single != null && (single.operator == "re" || single.operator == "box") && axisAligned(m)) {
            val v = single.values
            val x1 = if (single.operator == "re") v[0] + v[2] else v[2]
            val y1 = if (single.operator == "re") v[1] + v[3] else v[3]
            val ax = m[0] * v[0] + m[2] * v[1] + m[4]
            val ay = m[1] * v[0] + m[3] * v[1] + m[5]
            val bx = m[0] * x1 + m[2] * y1 + m[4]
            val by = m[1] * x1 + m[3] * y1 + m[5]
            val rect = doubleArrayOf(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
            val current = state.clip ?: base
            if (contains(rect, current)) return
            val left = max(current[0], rect[0])
            val bottom = max(current[1], rect[1])
            state.clip = doubleArrayOf(left, bottom, max(left, min(current[2], rect[2])), max(bottom, min(current[3], rect[3])))
        }
        val output = ByteArrayOutputStream()
        output.write(path.bytes())
        output.writeLatin1(if (path.clip == "W*") "\nW* n" else "\nW n")
        state.ops += output.toByteArray()
    }

    private fun contains(outer: DoubleArray, inner: DoubleArray): Boolean =
        outer[0] <= inner[0] + CONTAINS_TOLERANCE && outer[1] <= inner[1] + CONTAINS_TOLERANCE &&
            outer[2] >= inner[2] - CONTAINS_TOLERANCE && outer[3] >= inner[3] - CONTAINS_TOLERANCE

    private fun isIdentity(m: DoubleArray): Boolean =
        abs(m[0] - 1) < EPSILON && abs(m[1]) < EPSILON && abs(m[2]) < EPSILON &&
            abs(m[3] - 1) < EPSILON && abs(m[4]) < EPSILON && abs(m[5]) < EPSILON

    private fun ByteArrayOutputStream.writeItem(state: State, body: ByteArray, selfContained: Boolean = false) {
        if (selfContained && state.ops.isEmpty() && state.graphics.isEmpty()) {
            writeLatin1("\n")
            write(body)
            return
        }
        writeLatin1("\nq")
        for (op in state.ops) {
            writeLatin1("\n")
            write(op)
        }
        for (graphics in state.graphics.values) {
            writeLatin1("\n")
            write(graphics)
        }
        writeLatin1("\n")
        write(body)
        writeLatin1("\nQ")
    }

    private fun ByteArrayOutputStream.writeBody(graphics: Map<String, ByteArray>, body: ByteArray) {
        if (graphics.isEmpty()) {
            writeLatin1("\n")
            write(body)
            return
        }
        writeLatin1("\nq")
        for (value in graphics.values) {
            writeLatin1("\n")
            write(value)
        }
        writeLatin1("\n")
        write(body)
        writeLatin1("\nQ")
    }

    private fun ByteArrayOutputStream.writeNumbers(numbers: List<PdfObject>, operator: String) {
        val output = PdfOutputStream(this)
        output.writeString("\n")
        for (number in numbers) output.write(number).writeSpace()
        output.writeString(operator)
        output.flush()
    }

    private fun ByteArrayOutputStream.writeName(name: PdfName) {
        PdfOutputStream(this).apply {
            write(name)
            flush()
        }
    }

    private fun ByteArrayOutputStream.writeLatin1(text: String) {
        write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun ByteArrayOutputStream.writeOperator(values: DoubleArray, operator: String) {
        val output = PdfOutputStream(this)
        for (value in values) output.writeDouble(value, true).writeSpace()
        output.writeString("$operator\n")
        output.flush()
    }

    private fun rawContentLength(page: PdfPage): Long {
        var length = 0L
        for (index in 0 until page.contentStreamCount) {
            length += page.getContentStream(index).getAsNumber(PdfName.Length)?.longValue() ?: 0L
        }
        return length
    }

    private fun contentOf(page: PdfPage): ByteArray {
        val content = ByteArrayOutputStream()
        for (index in 0 until page.contentStreamCount) {
            content.write(page.getContentStream(index).bytes)
            content.write('\n'.code)
        }
        return content.toByteArray()
    }
}
