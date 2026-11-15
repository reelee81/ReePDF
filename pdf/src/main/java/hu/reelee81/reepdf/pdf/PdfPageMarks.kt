package hu.reelee81.reepdf.pdf

class PdfPageMarks(val toPage: DoubleArray, val marks: List<PdfPageMark>, val streams: IntArray) {
    companion object {
        val NONE = PdfPageMarks(PdfMatrix.IDENTITY, emptyList(), IntArray(0))
    }
}

class PdfPageMark(val mark: PdfMark, val stream: Int)

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
                marks += PdfPageMark(stored.mark.transformed(toPage), stream.number)
            }
        }
        for (layer in layers) {
            val stored = PdfMarkContent.read(layer.content)
            if (stored.isEmpty()) continue
            for (cell in cells) {
                if (cell.sourceIndex != layer.sourceIndex) continue
                val toPageFromLayer = PdfMatrix.concat(cell.matrix, toPage)
                for (item in stored) marks += PdfPageMark(item.mark.transformed(toPageFromLayer), layer.number)
            }
        }
        val streams = IntArray(appended.size + layers.size)
        appended.forEachIndexed { index, stream -> streams[index] = stream.number }
        layers.forEachIndexed { index, stream -> streams[appended.size + index] = stream.number }
        return PdfPageMarks(toPage, marks, streams)
    }

    companion object {
        fun cells(values: FloatArray): List<PdfEditProjection.Cell> {
            val cells = ArrayList<PdfEditProjection.Cell>()
            var offset = 0
            while (offset + 7 <= values.size) {
                cells += PdfEditProjection.Cell(values[offset].toInt(), DoubleArray(6) { values[offset + 1 + it].toDouble() })
                offset += 7
            }
            return cells
        }
    }
}
