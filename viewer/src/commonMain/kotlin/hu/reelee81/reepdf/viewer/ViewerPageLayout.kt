package hu.reelee81.reepdf.viewer

import kotlin.math.max

class ViewerPageLayout(pageWidths: FloatArray, pageHeights: FloatArray, spacing: Double) {
    val pageCount: Int = pageWidths.size
    private val widths = DoubleArray(pageCount) { pageWidths[it].toDouble() }
    private val tops = DoubleArray(pageCount)
    private val bottoms = DoubleArray(pageCount)
    val maxWidth: Double
    val totalHeight: Double

    init {
        require(pageCount > 0 && pageHeights.size == pageCount) { "Page sizes are missing" }
        require(spacing >= 0.0) { "Negative page spacing" }
        var top = 0.0
        for (page in 0 until pageCount) {
            tops[page] = top
            bottoms[page] = top + pageHeights[page]
            top = bottoms[page] + spacing
        }
        maxWidth = widths.max()
        totalHeight = bottoms[pageCount - 1]
    }

    fun top(page: Int): Double = tops[page]

    fun bottom(page: Int): Double = bottoms[page]

    fun width(page: Int): Double = widths[page]

    fun height(page: Int): Double = bottoms[page] - tops[page]

    fun left(page: Int, viewportLeft: Double, viewportWidth: Double): Double {
        val width = widths[page]
        val slack = maxWidth - viewportWidth
        return when {
            width < viewportWidth -> max(0.0, viewportLeft + (viewportWidth - width) / 2)
            viewportLeft + viewportWidth > maxWidth -> maxWidth - width
            viewportLeft > 0.0 && slack > 0.0 -> viewportLeft * (maxWidth - width) / slack
            else -> 0.0
        }
    }

    fun pageAt(y: Double): Int {
        val page = (countAtMost(tops, y) - 1).coerceIn(0, pageCount - 1)
        val next = page + 1
        if (next < pageCount && y > bottoms[page] && tops[next] - y < y - bottoms[page]) return next
        return page
    }

    fun visiblePages(viewportTop: Double, viewportBottom: Double): IntRange =
        countAtMost(bottoms, viewportTop)..<countBelow(tops, viewportBottom)

    fun labelPages(viewportTop: Double, viewportBottom: Double): IntRange {
        val first = countBelow(tops, viewportTop)
        val last = countAtMost(bottoms, viewportBottom) - 1
        if (first <= last) return first..last
        val center = (countAtMost(tops, (viewportTop + viewportBottom) / 2) - 1).coerceIn(0, pageCount - 1)
        return center..center
    }

    private fun countBelow(values: DoubleArray, limit: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < limit) low = middle + 1 else high = middle
        }
        return low
    }

    private fun countAtMost(values: DoubleArray, limit: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= limit) low = middle + 1 else high = middle
        }
        return low
    }
}
