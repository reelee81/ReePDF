package hu.reelee81.reepdf.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object PdfMarkContent {
    const val TAG = "ReePDF"
    private const val COORDINATE_DECIMALS = 3
    private const val COLOR_DECIMALS = 4
    private val PATH_OPERATORS = setOf("m", "l", "c", "v", "y", "h", "re")
    private val PAINT_OPERATORS = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")
    private val BLANK_OPERATORS = setOf("q", "Q", "cm", "m", "l", "c", "v", "y", "h", "re", "W", "W*", "n")

    class Stored(val mark: PdfMark, val start: Int, val end: Int, val clip: DoubleArray? = null)

    fun write(marks: List<PdfMark>, alpha: String?): ByteArray {
        val text = StringBuilder()
        for (mark in marks) {
            require(!mark.kind.translucent || alpha != null) { "A translucent mark needs an alpha state" }
            text.append('/').append(TAG).append(" <</Id(").append(mark.id).append(")/K/").append(mark.kind.code).append(">> BDC\nq\n")
            if (mark.kind.translucent) text.append('/').append(alpha).append(" gs\n")
            for (shift in intArrayOf(16, 8, 0)) text.number(((mark.color shr shift) and 0xFF) / 255.0, COLOR_DECIMALS).append(' ')
            text.append(if (mark.kind.fills) "rg\n" else "RG\n")
            if (!mark.kind.fills) text.number(mark.width.toDouble(), COORDINATE_DECIMALS).append(" w 1 J 1 j\n")
            var index = 0
            for (verb in mark.path.verbs) {
                val count = PdfMarkPath.pointsOf(verb)
                for (offset in 0 until count) text.number(mark.path.points[index + offset].toDouble(), COORDINATE_DECIMALS).append(' ')
                index += count
                text.append(
                    when (verb) {
                        PdfMarkPath.MOVE -> "m\n"
                        PdfMarkPath.LINE -> "l\n"
                        PdfMarkPath.CUBIC -> "c\n"
                        else -> "h\n"
                    }
                )
            }
            text.append(if (mark.kind.fills) "f\n" else "S\n").append("Q\nEMC\n")
        }
        return text.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun read(content: ByteArray): List<Stored> = Reader(content).read()

    fun without(content: ByteArray, ids: Set<String>): ByteArray {
        val removed = read(content).filter { it.mark.id in ids }
        if (removed.isEmpty()) return content
        val out = java.io.ByteArrayOutputStream(content.size)
        var from = 0
        for (stored in removed.sortedBy { it.start }) {
            if (stored.start < from) continue
            out.write(content, from, stored.start - from)
            from = stored.end
        }
        out.write(content, from, content.size - from)
        return out.toByteArray()
    }

    fun hasOtherContent(content: ByteArray, marks: List<Stored>): Boolean {
        if (marks.isEmpty()) return !isBlank(content)
        val rest = java.io.ByteArrayOutputStream(content.size)
        var from = 0
        for (stored in marks.sortedBy { it.start }) {
            if (stored.start < from) continue
            rest.write(content, from, stored.start - from)
            rest.write(' '.code)
            from = stored.end
        }
        rest.write(content, from, content.size - from)
        return !isBlank(rest.toByteArray())
    }

    fun isBlank(content: ByteArray): Boolean {
        val lexer = Lexer(content)
        while (true) {
            val token = lexer.next() ?: return true
            if (token is Token.Operator && token.name !in BLANK_OPERATORS) return false
            if (token is Token.InlineImage) return false
        }
    }

    private fun StringBuilder.number(value: Double, decimals: Int): StringBuilder {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (value * factor).roundToLong()
        if (scaled == 0L) return append('0')
        if (scaled < 0) append('-')
        val magnitude = kotlin.math.abs(scaled)
        append(magnitude / factor)
        var fraction = magnitude % factor
        if (fraction != 0L) {
            var digits = decimals
            while (fraction % 10 == 0L) {
                fraction /= 10
                digits--
            }
            append('.')
            val text = fraction.toString()
            repeat(digits - text.length) { append('0') }
            append(text)
        }
        return this
    }

    private class Reader(content: ByteArray) {
        private val lexer = Lexer(content)
        private val found = ArrayList<Stored>()
        private val saved = ArrayDeque<Pair<DoubleArray, DoubleArray?>>()
        private var ctm = PdfMatrix.IDENTITY
        private var clip: DoubleArray? = null
        private var pathBox: DoubleArray? = null
        private var clipping = false
        private var open: Open? = null

        fun read(): List<Stored> {
            val operands = ArrayList<Any?>()
            var operandsStart = -1
            while (true) {
                val token = lexer.next() ?: break
                when (token) {
                    is Token.Operator -> {
                        val start = if (operandsStart >= 0) operandsStart else token.start
                        operate(token.name, operands, start)
                        operands.clear()
                        operandsStart = -1
                    }
                    is Token.InlineImage -> {
                        operands.clear()
                        operandsStart = -1
                    }
                    is Token.Value -> {
                        if (operandsStart < 0) operandsStart = token.start
                        operands += token.value
                    }
                }
            }
            return found
        }

        private fun operate(name: String, operands: List<Any?>, start: Int) {
            when (name) {
                "q" -> saved.addLast(ctm to clip)
                "Q" -> saved.removeLastOrNull()?.let { (matrix, area) ->
                    ctm = matrix
                    clip = area
                }
                "cm" -> numbers(operands, 6)?.let { ctm = PdfMatrix.concat(it, ctm) }
            }
            val current = open
            if (current == null) {
                if (name == "BDC" && operands.size == 2 && operands[0] == Name(TAG)) {
                    val properties = operands[1] as? Map<*, *>
                    val id = properties?.get("Id") as? String
                    if (!id.isNullOrEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' }) {
                        open = Open(id, PdfMarkKind.of((properties["K"] as? Name)?.value), start, clip)
                    }
                } else {
                    outside(name, operands)
                }
                return
            }
            when (name) {
                "BDC", "BMC" -> current.depth++
                "EMC" -> if (current.depth > 0) current.depth-- else finish(current)
                "w" -> numbers(operands, 1)?.let { current.width = it[0] }
                "RG", "rg" -> numbers(operands, 3)?.let { current.color = color(it) }
                "gs" -> current.translucent = true
                in PATH_OPERATORS -> current.build(name, numbers(operands, operands.size) ?: return, ctm)
                in PAINT_OPERATORS -> current.paint(name, ctm)
                "n" -> current.discard()
            }
        }

        private fun outside(name: String, operands: List<Any?>) {
            when (name) {
                "re" -> numbers(operands, 4)?.let { (x, y, w, h) ->
                    include(x, y)
                    include(x + w, y + h)
                    include(x + w, y)
                    include(x, y + h)
                }
                in PATH_OPERATORS -> numbers(operands, operands.size)?.let { values ->
                    var index = 0
                    while (index + 1 < values.size) {
                        include(values[index], values[index + 1])
                        index += 2
                    }
                }
                "W", "W*" -> clipping = true
                "n", in PAINT_OPERATORS -> {
                    if (clipping) {
                        val box = pathBox ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                        clip = clip?.let { area ->
                            doubleArrayOf(max(area[0], box[0]), max(area[1], box[1]), min(area[2], box[2]), min(area[3], box[3]))
                        } ?: box
                    }
                    clipping = false
                    pathBox = null
                }
            }
        }

        private fun include(x: Double, y: Double) {
            val px = ctm[0] * x + ctm[2] * y + ctm[4]
            val py = ctm[1] * x + ctm[3] * y + ctm[5]
            pathBox = pathBox?.let { box -> doubleArrayOf(min(box[0], px), min(box[1], py), max(box[2], px), max(box[3], py)) }
                ?: doubleArrayOf(px, py, px, py)
        }

        private fun finish(current: Open) {
            open = null
            val path = current.path.build()
            if (path.verbs.isEmpty()) return
            val kind = current.kind ?: when {
                current.filled -> PdfMarkKind.TEXT_HIGHLIGHT
                current.translucent -> PdfMarkKind.HIGHLIGHTER
                else -> PdfMarkKind.PEN
            }
            val mark = PdfMark(current.id, kind, current.color, if (kind.fills) 0f else current.paintedWidth.toFloat(), path)
            found += Stored(mark, current.start, lexer.position, current.clip)
        }

        private fun numbers(operands: List<Any?>, count: Int): DoubleArray? {
            if (operands.size != count) return null
            return DoubleArray(count) { (operands[it] as? Double) ?: return null }
        }

        private fun color(values: DoubleArray): Int {
            var color = 0
            for (value in values) color = (color shl 8) or (value.coerceIn(0.0, 1.0) * 255).roundToInt()
            return color
        }
    }

    private class Open(val id: String, val kind: PdfMarkKind?, val start: Int, val clip: DoubleArray?) {
        var depth = 0
        var width = 1.0
        var paintedWidth = 0.0
        var color = 0
        var translucent = false
        var filled = false
        val path = PdfMarkPath.Builder()
        private val pending = PdfMarkPath.Builder()
        private var pendingEmpty = true
        private var x = 0.0
        private var y = 0.0
        private var startX = 0.0
        private var startY = 0.0

        fun build(name: String, values: DoubleArray, ctm: DoubleArray) {
            when (name) {
                "m" -> if (values.size == 2) {
                    x = values[0]
                    y = values[1]
                    startX = x
                    startY = y
                    point(ctm, x, y) { px, py -> pending.moveTo(px, py) }
                }
                "l" -> if (values.size == 2) {
                    x = values[0]
                    y = values[1]
                    point(ctm, x, y) { px, py -> pending.lineTo(px, py) }
                }
                "c" -> if (values.size == 6) curve(ctm, values[0], values[1], values[2], values[3], values[4], values[5])
                "v" -> if (values.size == 4) curve(ctm, x, y, values[0], values[1], values[2], values[3])
                "y" -> if (values.size == 4) curve(ctm, values[0], values[1], values[2], values[3], values[2], values[3])
                "h" -> if (values.isEmpty()) {
                    pending.close()
                    x = startX
                    y = startY
                }
                "re" -> if (values.size == 4) {
                    val (left, bottom, width, height) = values.toList()
                    point(ctm, left, bottom) { px, py -> pending.moveTo(px, py) }
                    point(ctm, left + width, bottom) { px, py -> pending.lineTo(px, py) }
                    point(ctm, left + width, bottom + height) { px, py -> pending.lineTo(px, py) }
                    point(ctm, left, bottom + height) { px, py -> pending.lineTo(px, py) }
                    pending.close()
                    x = left
                    y = bottom
                    startX = left
                    startY = bottom
                }
            }
            pendingEmpty = pending.isEmpty
        }

        fun paint(name: String, ctm: DoubleArray) {
            if (pendingEmpty) return
            if (name == "s" || name == "b" || name == "b*") pending.close()
            val built = pending.build()
            var index = 0
            for (verb in built.verbs) {
                val p = built.points
                when (verb) {
                    PdfMarkPath.MOVE -> path.moveTo(p[index], p[index + 1])
                    PdfMarkPath.LINE -> path.lineTo(p[index], p[index + 1])
                    PdfMarkPath.CUBIC -> path.curveTo(p[index], p[index + 1], p[index + 2], p[index + 3], p[index + 4], p[index + 5])
                    else -> path.close()
                }
                index += PdfMarkPath.pointsOf(verb)
            }
            if (name.startsWith("f") || name.startsWith("F") || name.startsWith("B") || name.startsWith("b")) filled = true
            if (name.startsWith("S") || name.startsWith("s") || name.startsWith("B") || name.startsWith("b")) {
                paintedWidth = width * PdfMarkPath.scaleOf(ctm)
            }
            discard()
        }

        fun discard() {
            pending.reset()
            pendingEmpty = true
        }

        private fun curve(ctm: DoubleArray, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
            val a = transform(ctm, x1, y1)
            val b = transform(ctm, x2, y2)
            val c = transform(ctm, x3, y3)
            pending.curveTo(a.first, a.second, b.first, b.second, c.first, c.second)
            x = x3
            y = y3
        }

        private inline fun point(ctm: DoubleArray, px: Double, py: Double, add: (Float, Float) -> Unit) {
            val (tx, ty) = transform(ctm, px, py)
            add(tx, ty)
        }

        private fun transform(ctm: DoubleArray, px: Double, py: Double): Pair<Float, Float> =
            (ctm[0] * px + ctm[2] * py + ctm[4]).toFloat() to (ctm[1] * px + ctm[3] * py + ctm[5]).toFloat()
    }

    private data class Name(val value: String)

    private sealed interface Token {
        val start: Int

        class Value(override val start: Int, val value: Any?) : Token

        class Operator(override val start: Int, val name: String) : Token

        class InlineImage(override val start: Int) : Token
    }

    private class Lexer(private val bytes: ByteArray) {
        var position = 0
            private set

        fun next(): Token? {
            skipSpace()
            if (position >= bytes.size) return null
            val start = position
            val value = value(start) ?: return null
            if (value is Keyword) {
                return when (value.text) {
                    "true" -> Token.Value(start, true)
                    "false" -> Token.Value(start, false)
                    "null" -> Token.Value(start, null)
                    "BI" -> {
                        skipInlineImage()
                        Token.InlineImage(start)
                    }
                    else -> Token.Operator(start, value.text)
                }
            }
            return Token.Value(start, if (value === DictEnd || value === ArrayEnd) null else value)
        }

        private fun value(start: Int): Any? {
            if (position >= bytes.size) return null
            return when (val c = bytes[position].toInt().toChar()) {
                '/' -> {
                    position++
                    Name(regular())
                }
                '(' -> literalString()
                '<' -> if (peek(1) == '<') dictionary() else hexString()
                '>' -> if (peek(1) == '>') {
                    position += 2
                    DictEnd
                } else {
                    position++
                    Keyword(c.toString())
                }
                '[' -> array()
                ']' -> {
                    position++
                    ArrayEnd
                }
                '{', '}', ')' -> {
                    position++
                    Keyword(c.toString())
                }
                else -> {
                    val text = regular()
                    if (text.isEmpty()) {
                        position = start + 1
                        Keyword(c.toString())
                    } else {
                        text.toDoubleOrNull()?.takeIf { looksNumeric(text) } ?: Keyword(text)
                    }
                }
            }
        }

        private fun dictionary(): Map<String, Any?> {
            position += 2
            val result = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (position >= bytes.size) return result
                val key = value(position)
                if (key === DictEnd || key == null) return result
                skipSpace()
                val item = value(position)
                if (item === DictEnd) return result
                if (key is Name) result[key.value] = item
            }
        }

        private fun array(): List<Any?> {
            position++
            val result = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (position >= bytes.size) return result
                val item = value(position)
                if (item === ArrayEnd || item == null) return result
                result += item
            }
        }

        private fun literalString(): String {
            position++
            val text = StringBuilder()
            var depth = 0
            while (position < bytes.size) {
                when (val c = bytes[position++].toInt().toChar()) {
                    '\\' -> if (position < bytes.size) {
                        when (val escaped = bytes[position++].toInt().toChar()) {
                            'n' -> text.append('\n')
                            'r' -> text.append('\r')
                            't' -> text.append('\t')
                            'b' -> text.append('\b')
                            'f' -> text.append('\u000c')
                            in '0'..'7' -> {
                                var code = escaped - '0'
                                repeat(2) {
                                    val digit = peek(0)
                                    if (digit in '0'..'7') {
                                        code = code * 8 + (digit - '0')
                                        position++
                                    }
                                }
                                text.append((code and 0xFF).toChar())
                            }
                            '\r', '\n' -> if (escaped == '\r' && peek(0) == '\n') position++
                            else -> text.append(escaped)
                        }
                    } else {
                        text.append(c)
                    }
                    '(' -> {
                        depth++
                        text.append(c)
                    }
                    ')' -> {
                        if (depth == 0) return text.toString()
                        depth--
                        text.append(c)
                    }
                    else -> text.append(c)
                }
            }
            return text.toString()
        }

        private fun hexString(): String {
            position++
            val text = StringBuilder()
            while (position < bytes.size && bytes[position].toInt().toChar() != '>') text.append(bytes[position++].toInt().toChar())
            if (position < bytes.size) position++
            return text.toString()
        }

        private fun regular(): String {
            val start = position
            while (position < bytes.size && !isSpace(bytes[position]) && !isDelimiter(bytes[position])) position++
            return String(bytes, start, position - start, Charsets.ISO_8859_1)
        }

        private fun skipInlineImage() {
            while (true) {
                skipSpace()
                if (position >= bytes.size) return
                val item = value(position)
                if (item is Keyword && item.text == "ID") break
                if (item == null) return
            }
            if (position < bytes.size) position++
            while (position + 1 < bytes.size) {
                if (bytes[position] == 'E'.code.toByte() && bytes[position + 1] == 'I'.code.toByte() &&
                    (position == 0 || isSpace(bytes[position - 1])) &&
                    (position + 2 >= bytes.size || isSpace(bytes[position + 2]) || isDelimiter(bytes[position + 2]))
                ) {
                    position += 2
                    return
                }
                position++
            }
            position = bytes.size
        }

        private fun skipSpace() {
            while (position < bytes.size) {
                val b = bytes[position]
                if (isSpace(b)) {
                    position++
                } else if (b == '%'.code.toByte()) {
                    while (position < bytes.size && bytes[position] != '\n'.code.toByte() && bytes[position] != '\r'.code.toByte()) position++
                } else {
                    return
                }
            }
        }

        private fun peek(offset: Int): Char =
            if (position + offset < bytes.size) bytes[position + offset].toInt().toChar() else '\u0000'

        private fun looksNumeric(text: String) = text.all { it in '0'..'9' || it == '.' || it == '-' || it == '+' }

        private fun isSpace(b: Byte) = when (b.toInt()) {
            0, 9, 10, 12, 13, 32 -> true
            else -> false
        }

        private fun isDelimiter(b: Byte) = when (b.toInt().toChar()) {
            '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true
            else -> false
        }
    }

    private class Keyword(val text: String)

    private object DictEnd

    private object ArrayEnd
}
