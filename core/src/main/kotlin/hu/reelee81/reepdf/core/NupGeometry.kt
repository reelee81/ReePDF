package hu.reelee81.reepdf.core

import kotlin.math.floor
import kotlin.math.min

data class NupConfig(
    val rows: Int,
    val cols: Int,
    val marginMm: Int,
    val isLandscape: Boolean,
    val drawFrame: Boolean,
    val scaleIndividually: Boolean,
    val fitIndividually: Boolean
)

object NupGeometry {
    const val A4_WIDTH_PT = 595f
    const val A4_HEIGHT_PT = 842f
    const val DEFAULT_POSITION = 5
    private const val MIN_CELL_MM = 1f

    fun maxMarginMm(rows: Int, cols: Int, isLandscape: Boolean): Int {
        val pageWidth = if (isLandscape) A4_HEIGHT_PT else A4_WIDTH_PT
        val pageHeight = if (isLandscape) A4_WIDTH_PT else A4_HEIGHT_PT
        val minCellPt = MIN_CELL_MM * 72f / 25.4f
        val widthLimit = (pageWidth - cols * minCellPt) / (cols + 1)
        val heightLimit = (pageHeight - rows * minCellPt) / (rows + 1)
        return floor(min(widthLimit, heightLimit) * 25.4f / 72f).toInt().coerceAtLeast(0)
    }

    fun validPosition(position: Int): Int = if (position in 1..9) position else DEFAULT_POSITION

    fun horizontalPositionFactor(position: Int): Float = ((position - 1) % 3) * 0.5f

    fun verticalPositionFactor(position: Int): Float = (2 - (position - 1) / 3) * 0.5f
}
