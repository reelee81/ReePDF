package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

object ViewerTooltipPlacement {
    fun place(anchor: IntRect, bubble: IntSize, area: IntRect, gap: Int): IntOffset {
        val x = (anchor.left + (anchor.width - bubble.width) / 2).coerceIn(area.left, max(area.left, area.right - bubble.width))
        val above = anchor.top - gap - bubble.height
        val y = if (above >= area.top) above else anchor.bottom + gap
        return IntOffset(x, y)
    }

    internal fun anchor(origin: Offset, size: IntSize, center: Float?, band: Float?): IntRect {
        val middle = center ?: (size.width / 2f)
        val height = band ?: size.height.toFloat()
        val left = (origin.x + middle - size.width / 2f).roundToInt()
        val top = (origin.y + (size.height - height) / 2f).roundToInt()
        return IntRect(left, top, left + size.width, top + height.roundToInt())
    }
}
