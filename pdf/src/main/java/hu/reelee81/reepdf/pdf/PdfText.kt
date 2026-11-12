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
