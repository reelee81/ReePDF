package hu.reelee81.reepdf.pdf

import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

enum class PdfMarkKind(internal val code: String) {
    PEN("Pen"),
    HIGHLIGHTER("Hl"),
    TEXT_HIGHLIGHT("Text"),
    UNDERLINE("Under"),
    STRIKETHROUGH("Strike"),
    SIGNATURE("Sign"),
    TYPED("Typed"),
    BOX_X("BoxX"),
    BOX_CHECK("BoxCheck"),
    BOX_DASH("BoxDash"),
    BOX_DOT("BoxDot");

    internal val fills: Boolean
        get() = this == TEXT_HIGHLIGHT || this == UNDERLINE || this == STRIKETHROUGH || this == TYPED || this == BOX_DOT

    internal val translucent: Boolean
        get() = this == HIGHLIGHTER || this == TEXT_HIGHLIGHT

    companion object {
        internal fun of(code: String?): PdfMarkKind? = entries.firstOrNull { it.code == code }
    }
}

class PdfMark(
    val id: String,
    val kind: PdfMarkKind,
    val color: Int,
    val width: Float,
    val path: PdfMarkPath,
    val text: PdfMarkText? = null
) {
    init {
        require(id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' }) { "Invalid mark id $id" }
        require(width >= 0f && width.isFinite()) { "Invalid mark width $width" }
        require((kind == PdfMarkKind.TYPED) == (text != null)) { "Only a typed mark carries text" }
    }

    fun transformed(matrix: DoubleArray): PdfMark =
        PdfMark(id, kind, color, (width * PdfMarkPath.scaleOf(matrix)).toFloat(), path.transformed(matrix), text?.transformed(matrix))

    companion object {
        const val ALPHA = 0.4f

        fun newId(): String = java.lang.Long.toUnsignedString(UUID.randomUUID().mostSignificantBits, 36)
    }
}

class PdfMarkText(val text: String, val weight: Int, val size: Float, val matrix: DoubleArray) {
    init {
        require(weight in WEIGHTS) { "Invalid text weight $weight" }
        require(size > 0f && size.isFinite()) { "Invalid text size $size" }
        require(matrix.size == 6 && matrix.all { it.isFinite() }) { "Invalid text matrix" }
        require(text.codePointCount(0, text.length) <= MAX_CHARS) { "The text is longer than $MAX_CHARS characters" }
    }

    val lines: List<String>
        get() = text.split('\n')

    fun transformed(by: DoubleArray): PdfMarkText = placed(text, weight, size.toDouble(), PdfMatrix.concat(matrix, by))

    fun boxes(widths: DoubleArray): PdfMarkPath {
        val builder = PdfMarkPath.Builder()
        val line = LINE_HEIGHT * size
        for ((index, width) in widths.withIndex()) {
            if (width <= 0.0) continue
            val top = -index * line
            val bottom = top - line
            val right = width * size
            val corners = doubleArrayOf(0.0, top, right, top, right, bottom, 0.0, bottom)
            for (corner in 0 until 4) {
                val x = corners[corner * 2]
                val y = corners[corner * 2 + 1]
                val px = (matrix[0] * x + matrix[2] * y + matrix[4]).toFloat()
                val py = (matrix[1] * x + matrix[3] * y + matrix[5]).toFloat()
                if (corner == 0) builder.moveTo(px, py) else builder.lineTo(px, py)
            }
            builder.close()
        }
        return builder.build()
    }

    companion object {
        val WEIGHTS = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900)
        const val UNITS_PER_EM = 2048.0
        const val ASCENT = 1900.0 / UNITS_PER_EM
        const val LINE_HEIGHT = 2400.0 / UNITS_PER_EM
        const val MAX_CHARS = 5000

        fun placed(text: String, weight: Int, size: Double, matrix: DoubleArray): PdfMarkText {
            val scale = PdfMarkPath.scaleOf(matrix)
            if (!(scale > 1e-9) || !scale.isFinite()) return PdfMarkText(text, weight, size.toFloat(), matrix)
            val unit = doubleArrayOf(matrix[0] / scale, matrix[1] / scale, matrix[2] / scale, matrix[3] / scale, matrix[4], matrix[5])
            return PdfMarkText(text, weight, (size * scale).toFloat(), unit)
        }
    }
}

class PdfMarkPath(val verbs: ByteArray, val points: FloatArray) {
    init {
        require(points.size == verbs.sumOf { pointsOf(it) }) { "The path has ${points.size} coordinates for its verbs" }
    }

    fun transformed(matrix: DoubleArray): PdfMarkPath {
        val moved = FloatArray(points.size)
        var index = 0
        while (index < points.size) {
            val x = points[index].toDouble()
            val y = points[index + 1].toDouble()
            moved[index] = (matrix[0] * x + matrix[2] * y + matrix[4]).toFloat()
            moved[index + 1] = (matrix[1] * x + matrix[3] * y + matrix[5]).toFloat()
            index += 2
        }
        return PdfMarkPath(verbs, moved)
    }

    class Builder {
        private val verbs = ArrayList<Byte>()
        private val points = ArrayList<Float>()

        fun moveTo(x: Float, y: Float) = add(MOVE, x, y)

        fun lineTo(x: Float, y: Float) = add(LINE, x, y)

        fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = add(CUBIC, x1, y1, x2, y2, x3, y3)

        fun close() = add(CLOSE)

        fun build() = PdfMarkPath(verbs.toByteArray(), points.toFloatArray())

        fun reset() {
            verbs.clear()
            points.clear()
        }

        val isEmpty: Boolean
            get() = verbs.isEmpty()

        private fun add(verb: Byte, vararg coordinates: Float): Builder {
            verbs += verb
            for (value in coordinates) points += value
            return this
        }
    }

    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CUBIC: Byte = 2
        const val CLOSE: Byte = 3

        fun pointsOf(verb: Byte): Int = when (verb) {
            MOVE, LINE -> 2
            CUBIC -> 6
            CLOSE -> 0
            else -> throw IllegalArgumentException("Unknown path verb $verb")
        }

        internal fun scaleOf(matrix: DoubleArray): Double = sqrt(abs(matrix[0] * matrix[3] - matrix[1] * matrix[2]))
    }
}

internal object PdfMatrix {
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun concat(first: DoubleArray, then: DoubleArray): DoubleArray = doubleArrayOf(
        first[0] * then[0] + first[1] * then[2],
        first[0] * then[1] + first[1] * then[3],
        first[2] * then[0] + first[3] * then[2],
        first[2] * then[1] + first[3] * then[3],
        first[4] * then[0] + first[5] * then[2] + then[4],
        first[4] * then[1] + first[5] * then[3] + then[5]
    )
}
