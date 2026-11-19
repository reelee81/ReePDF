package hu.reelee81.reepdf.pdf

import hu.reelee81.reepdf.core.NupBox
import hu.reelee81.reepdf.core.NupCellArea
import kotlin.math.max
import kotlin.math.min

class PdfPageMarks(val toPage: DoubleArray, val marks: List<PdfPageMark>, val streams: IntArray) {
    companion object {
        val NONE = PdfPageMarks(PdfMatrix.IDENTITY, emptyList(), IntArray(0))
    }
}

class PdfPageMark(val mark: PdfMark, val stream: Int, val clip: FloatArray? = null)

class PdfStreamEdit(val blank: Boolean, val erased: Set<String>) {
    val isOriginal: Boolean
        get() = !blank && erased.isEmpty()

    internal fun applyTo(original: ByteArray): ByteArray {
        if (blank) return ByteArray(0)
        val content = PdfMarkContent.without(original, erased)
        return if (PdfMarkContent.isBlank(content)) ByteArray(0) else content
    }

    companion object {
        val ORIGINAL = PdfStreamEdit(false, emptySet())
        val BLANK = PdfStreamEdit(true, emptySet())
    }
}

internal class PdfPageEditData(
    val toPage: DoubleArray,
    val cells: List<PdfEditProjection.Cell>,
    val appended: List<Stream>,
    val layers: List<Stream>
) {
    class Stream(val number: Int, val content: ByteArray, val sourceIndex: Int = -1)

    fun read(): PdfPageMarks {
        val marks = ArrayList<PdfPageMark>()
        for (stream in appended) {
            for (stored in PdfMarkContent.read(stream.content)) {
                marks += PdfPageMark(stored.mark.transformed(toPage), stream.number, stored.clip?.let { pageClip(it) })
            }
        }
        for (layer in layers) {
            val stored = PdfMarkContent.read(layer.content)
            if (stored.isEmpty()) continue
            for (cell in cells) {
                if (cell.sourceIndex != layer.sourceIndex) continue
                val toPageFromLayer = PdfMatrix.concat(cell.matrix, toPage)
                val box = cell.box
                val slot = cell.slot
                val area = if (box != null && slot != null) {
                    NupCellArea.of(box, cell.matrix, slot)?.let { doubleArrayOf(it.left, it.bottom, it.right, it.top) } ?: NOTHING
                } else {
                    null
                }
                for (item in stored) {
                    val piece = item.clip?.let { bounds(it, cell.matrix) }
                    val visible = when {
                        piece == null -> area
                        area == null -> piece
                        else -> doubleArrayOf(max(piece[0], area[0]), max(piece[1], area[1]), min(piece[2], area[2]), min(piece[3], area[3]))
                    }
                    marks += PdfPageMark(item.mark.transformed(toPageFromLayer), layer.number, visible?.let { pageClip(it) })
                }
            }
        }
        val streams = IntArray(appended.size + layers.size)
        appended.forEachIndexed { index, stream -> streams[index] = stream.number }
        layers.forEachIndexed { index, stream -> streams[appended.size + index] = stream.number }
        return PdfPageMarks(toPage, marks, streams)
    }

    private fun pageClip(box: DoubleArray): FloatArray {
        if (box[2] <= box[0] || box[3] <= box[1]) return FloatArray(4) { Float.NaN }
        val page = bounds(box, toPage)
        return floatArrayOf(page[0].toFloat(), page[1].toFloat(), page[2].toFloat(), page[3].toFloat())
    }

    private fun bounds(box: DoubleArray, matrix: DoubleArray): DoubleArray {
        var left = Double.POSITIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        for (x in doubleArrayOf(box[0], box[2])) {
            for (y in doubleArrayOf(box[1], box[3])) {
                val px = matrix[0] * x + matrix[2] * y + matrix[4]
                val py = matrix[1] * x + matrix[3] * y + matrix[5]
                left = min(left, px)
                bottom = min(bottom, py)
                right = max(right, px)
                top = max(top, py)
            }
        }
        return doubleArrayOf(left, bottom, right, top)
    }

    companion object {
        private val NOTHING = DoubleArray(4)

        fun cells(values: FloatArray, areas: FloatArray? = null): List<PdfEditProjection.Cell> {
            val count = values.size / 7
            val boxes = areas?.takeIf { it.size == count * 8 && it.all(Float::isFinite) }
            return List(count) { index ->
                val offset = index * 7
                val matrix = DoubleArray(6) { values[offset + 1 + it].toDouble() }
                val area = index * 8
                PdfEditProjection.Cell(
                    values[offset].toInt(), matrix,
                    boxes?.let { NupBox(it[area], it[area + 1], it[area + 2], it[area + 3]) },
                    boxes?.let { NupBox(it[area + 4], it[area + 5], it[area + 6], it[area + 7]) }
                )
            }
        }
    }
}
