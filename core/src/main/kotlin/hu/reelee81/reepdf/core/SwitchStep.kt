package hu.reelee81.reepdf.core

import kotlin.math.floor
import kotlin.math.roundToInt

object SwitchStep {
    class Next(val position: Int, val rising: Boolean)

    fun next(position: Int, last: Int, rising: Boolean): Next {
        if (last <= 0) return Next(0, true)
        val up = when {
            position <= 0 -> true
            position >= last -> false
            else -> rising
        }
        val target = (if (up) position + 1 else position - 1).coerceIn(0, last)
        return Next(target, up)
    }

    fun nearest(offset: Float, last: Int): Int = offset.roundToInt().coerceIn(0, last.coerceAtLeast(0))

    fun detent(raw: Float, last: Int, band: Float): Float {
        val x = raw.coerceIn(0f, last.coerceAtLeast(0).toFloat())
        if (last < 2 || band <= 0f) return x
        val segment = floor(x).toInt().coerceAtMost(last - 1)
        val free = band.coerceAtMost(0.45f)
        val lo = if (segment > 0) segment + free else segment.toFloat()
        val hi = if (segment + 1 < last) segment + 1 - free else (segment + 1).toFloat()
        return when {
            x <= lo -> segment.toFloat()
            x >= hi -> (segment + 1).toFloat()
            else -> segment + (x - lo) / (hi - lo)
        }
    }
}
