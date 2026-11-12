package hu.reelee81.reepdf.viewer

import kotlin.math.roundToInt

class ViewerFastScroll(private val topMargin: Int, private val bottomMargin: Int, private val thumbHeight: Int) {

    fun canScroll(viewHeight: Int, contentHeight: Double): Boolean =
        length(viewHeight) > 0 && contentHeight - viewHeight > 0.0

    fun thumbTop(scrollY: Double, viewHeight: Int, contentHeight: Double): Int =
        constrain(scrollY / (contentHeight - viewHeight) * length(viewHeight) + topMargin, viewHeight)

    fun scrollFor(thumbTop: Double, viewHeight: Int, contentHeight: Double): Double {
        val length = length(viewHeight).toDouble()
        return (thumbTop - topMargin).coerceIn(0.0, length) / length * (contentHeight - viewHeight)
    }

    fun constrain(position: Double, viewHeight: Int): Int =
        position.roundToInt().coerceIn(topMargin, viewHeight - (bottomMargin + thumbHeight))

    private fun length(viewHeight: Int): Int = viewHeight - (topMargin + bottomMargin + thumbHeight)

    companion object {
        const val HIDE_DELAY_MS = 1300L
        const val HIDE_ANIMATION_MS = 200L
    }
}
