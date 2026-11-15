package hu.reelee81.reepdf.viewer

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ViewerPageImage(val page: Int, val scale: Float, val width: Int, val height: Int, val version: Int = 0)

data class ViewerTile(
    val page: Int,
    val zoom: Float,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val version: Int = 0
)

class ViewerTilePlan(private val tileSize: Int, private val maxPagePixels: Long) {

    init {
        require(tileSize > 0 && maxPagePixels > 0) { "Invalid tile limits" }
    }

    fun pageImage(page: Int, pageWidth: Double, pageHeight: Double, zoom: Float, version: Int = 0): ViewerPageImage {
        val scale = min(zoom.toDouble(), sqrt(maxPagePixels / (pageWidth * pageHeight))).toFloat()
        return ViewerPageImage(page, scale, pixels(pageWidth, scale), pixels(pageHeight, scale), version)
    }

    fun tiles(
        page: Int,
        pageWidth: Double,
        pageHeight: Double,
        zoom: Float,
        visibleLeft: Double,
        visibleTop: Double,
        visibleRight: Double,
        visibleBottom: Double,
        version: Int = 0
    ): List<ViewerTile> {
        if (zoom <= pageImage(page, pageWidth, pageHeight, zoom).scale) return emptyList()
        val width = pixels(pageWidth, zoom)
        val height = pixels(pageHeight, zoom)
        val columns = (width + tileSize - 1) / tileSize
        val rows = (height + tileSize - 1) / tileSize
        val firstColumn = floor(visibleLeft * zoom / tileSize).toInt().coerceIn(0, columns)
        val lastColumn = (ceil(visibleRight * zoom / tileSize).toInt() - 1).coerceIn(-1, columns - 1)
        val firstRow = floor(visibleTop * zoom / tileSize).toInt().coerceIn(0, rows)
        val lastRow = (ceil(visibleBottom * zoom / tileSize).toInt() - 1).coerceIn(-1, rows - 1)
        val tiles = ArrayList<ViewerTile>()
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val left = column * tileSize
                val top = row * tileSize
                tiles += ViewerTile(page, zoom, left, top, min(tileSize, width - left), min(tileSize, height - top), version)
            }
        }
        return tiles
    }

    fun batch(missing: List<ViewerTile>): List<ViewerTile> {
        val chosen = ArrayList<ViewerTile>()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (tile in missing) {
            val nextLeft = min(left, tile.left)
            val nextTop = min(top, tile.top)
            val nextRight = max(right, tile.left + tile.width)
            val nextBottom = max(bottom, tile.top + tile.height)
            val area = (nextRight - nextLeft).toLong() * (nextBottom - nextTop)
            if (chosen.isNotEmpty() && area > maxPagePixels) break
            chosen += tile
            left = nextLeft
            top = nextTop
            right = nextRight
            bottom = nextBottom
        }
        return chosen
    }

    private fun pixels(points: Double, scale: Float): Int = ceil(points * scale).toInt().coerceAtLeast(1)
}
