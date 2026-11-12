package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

data class ViewerTextPoint(val x: Float, val y: Float)

class ViewerTextSpan(val from: ViewerTextPoint, val to: ViewerTextPoint, val rects: FloatArray)

class ViewerTextHits(val rects: FloatArray, val starts: IntArray) {
    init {
        require(starts.isNotEmpty() && starts.first() == 0 && starts.last() * RECT_SIZE == rects.size) {
            "Hit starts do not match the rectangles"
        }
    }

    val count: Int
        get() = starts.size - 1

    fun bounds(hit: Int): Rect = ViewerTextRects.bounds(rects, starts[hit], starts[hit + 1])

    companion object {
        const val RECT_SIZE = 4
        val NONE = ViewerTextHits(FloatArray(0), intArrayOf(0))
    }
}

class ViewerTextHandle(val page: Int, val x: Float, val y: Float, val lineHeight: Float)

object ViewerTextRects {
    fun count(rects: FloatArray): Int = rects.size / ViewerTextHits.RECT_SIZE

    fun rect(rects: FloatArray, index: Int): Rect {
        val offset = index * ViewerTextHits.RECT_SIZE
        return Rect(rects[offset], rects[offset + 1], rects[offset + 2], rects[offset + 3])
    }

    fun bounds(rects: FloatArray, from: Int, until: Int): Rect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in from until until) {
            val rect = rect(rects, index)
            left = min(left, rect.left)
            top = min(top, rect.top)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        return if (from < until) Rect(left, top, right, bottom) else Rect.Zero
    }
}
