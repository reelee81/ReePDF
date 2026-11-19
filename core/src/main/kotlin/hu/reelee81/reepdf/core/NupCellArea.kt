package hu.reelee81.reepdf.core

import kotlin.math.max
import kotlin.math.min

data class NupArea(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    fun intersects(left: Double, bottom: Double, right: Double, top: Double): Boolean =
        left < this.right && right > this.left && bottom < this.top && top > this.bottom
}

object NupCellArea {
    fun of(visible: NupBox, matrix: DoubleArray, slot: NupBox): NupArea? {
        val placed = mapped(visible, matrix) ?: return null
        val left = max(placed.left, slot.x.toDouble())
        val bottom = max(placed.bottom, slot.y.toDouble())
        val right = min(placed.right, slot.x.toDouble() + slot.width)
        val top = min(placed.top, slot.y.toDouble() + slot.height)
        return if (right > left && top > bottom) NupArea(left, bottom, right, top) else null
    }

    fun mapped(box: NupBox, matrix: DoubleArray): NupArea? {
        if (matrix.size != 6 || matrix.any { !it.isFinite() }) return null
        val xs = doubleArrayOf(box.x.toDouble(), box.x.toDouble() + box.width)
        val ys = doubleArrayOf(box.y.toDouble(), box.y.toDouble() + box.height)
        var left = Double.POSITIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        for (x in xs) {
            for (y in ys) {
                val px = matrix[0] * x + matrix[2] * y + matrix[4]
                val py = matrix[1] * x + matrix[3] * y + matrix[5]
                left = min(left, px)
                bottom = min(bottom, py)
                right = max(right, px)
                top = max(top, py)
            }
        }
        return if (right > left && top > bottom) NupArea(left, bottom, right, top) else null
    }
}
