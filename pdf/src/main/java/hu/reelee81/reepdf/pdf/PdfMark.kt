package hu.reelee81.reepdf.pdf

import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

enum class PdfMarkKind(internal val code: String) {
    PEN("Pen"),
    HIGHLIGHTER("Hl"),
    TEXT_HIGHLIGHT("Text");

    internal val fills: Boolean
        get() = this == TEXT_HIGHLIGHT

    internal val translucent: Boolean
        get() = this != PEN

    companion object {
        internal fun of(code: String?): PdfMarkKind? = entries.firstOrNull { it.code == code }
    }
}

class PdfMark(
    val id: String,
    val kind: PdfMarkKind,
    val color: Int,
    val width: Float,
    val path: PdfMarkPath
) {
    init {
        require(id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' }) { "Invalid mark id $id" }
        require(width >= 0f && width.isFinite()) { "Invalid mark width $width" }
    }

    fun transformed(matrix: DoubleArray): PdfMark =
        PdfMark(id, kind, color, (width * PdfMarkPath.scaleOf(matrix)).toFloat(), path.transformed(matrix))

    companion object {
        const val ALPHA = 0.4f

        fun newId(): String = java.lang.Long.toUnsignedString(UUID.randomUUID().mostSignificantBits, 36)
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
