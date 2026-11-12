package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad

internal class PdfTextWord(
    private val letters: PdfTextLetters,
    private val first: Int,
    private val last: Int,
    private val spanFirst: Int,
    private val spanLast: Int
) {
    val text: String
        get() = letters.text(first, last)

    fun anchors(copy: (Point, Point) -> String?, snapped: () -> Pair<Point, Point>?): Pair<Point, Point> {
        val own = letters.before(first) to letters.after(last)
        val wanted = text
        if (copy(own.first, own.second) == wanted) return own
        val fallback = snapped() ?: return own
        return if (copy(fallback.first, fallback.second) == wanted) fallback else own
    }

    fun touches(quad: Quad, tolerance: Float): Boolean = letters.spanTouches(spanFirst, spanLast, quad, tolerance)
}
