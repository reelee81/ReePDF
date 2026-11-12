package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point

internal class PdfTextCaret(val index: Int, val point: Point, val midline: Point) {
    fun choose(finger: Point, lands: (Point) -> Boolean): Point = when {
        lands(point) -> point
        lands(finger) -> finger
        midline !== point && lands(midline) -> midline
        else -> point
    }
}
