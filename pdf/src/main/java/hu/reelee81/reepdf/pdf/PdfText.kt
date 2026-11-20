package hu.reelee81.reepdf.pdf

class PdfTextPoint(val x: Float, val y: Float)

class PdfTextSpan(val from: PdfTextPoint, val to: PdfTextPoint, val rects: FloatArray)

class PdfTextHits(val rects: FloatArray, val starts: IntArray) {
    init {
        require(starts.isNotEmpty() && starts.first() == 0 && starts.last() * RECT_SIZE == rects.size) {
            "Hit starts do not match the rectangles"
        }
    }

    val count: Int
        get() = starts.size - 1

    companion object {
        const val RECT_SIZE = 4
        val NONE = PdfTextHits(FloatArray(0), intArrayOf(0))
    }
}

class PdfTextBaselines(val runs: FloatArray, val estimated: Int) {
    init {
        require(runs.size % RUN_SIZE == 0) { "Baseline runs have ${runs.size} values" }
    }

    val count: Int
        get() = runs.size / RUN_SIZE

    companion object {
        const val RUN_SIZE = 8
        val NONE = PdfTextBaselines(FloatArray(0), 0)
    }
}

class PdfTextHighlight(val rects: FloatArray, val boxes: FloatArray) {
    val start: PdfTextPoint?
        get() = if (boxes.isEmpty()) null else PdfTextPoint(boxes[0], (boxes[1] + boxes[3]) / 2)

    val end: PdfTextPoint?
        get() {
            if (boxes.isEmpty()) return null
            val last = boxes.size - PdfTextHits.RECT_SIZE
            return PdfTextPoint(boxes[last + 2], (boxes[last + 1] + boxes[last + 3]) / 2)
        }

    companion object {
        val NONE = PdfTextHighlight(FloatArray(0), FloatArray(0))
    }
}
