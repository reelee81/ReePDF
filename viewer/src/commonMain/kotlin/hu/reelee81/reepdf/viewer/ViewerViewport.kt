package hu.reelee81.reepdf.viewer

import kotlin.math.abs

class ViewerViewport private constructor(
    val width: Int,
    val height: Int,
    val zoom: Float,
    val scrollX: Double,
    val scrollY: Double,
    private val limits: ViewerZoomLimits,
    private val gutter: Double
) {
    val contentLeft: Double
        get() = scrollX / zoom

    val contentTop: Double
        get() = scrollY / zoom

    val contentWidth: Double
        get() = width / zoom.toDouble()

    val contentHeight: Double
        get() = height / zoom.toDouble()

    fun scrolledBy(dx: Double, dy: Double, layout: ViewerPageLayout): ViewerViewport =
        create(width, height, zoom, scrollX + dx, scrollY + dy, layout, limits, gutter)

    fun scrolledTo(x: Double, y: Double, layout: ViewerPageLayout): ViewerViewport =
        create(width, height, zoom, x, y, layout, limits, gutter)

    fun zoomedTo(newZoom: Float, pivotX: Double, pivotY: Double, layout: ViewerPageLayout): ViewerViewport {
        val target = newZoom.coerceIn(limits.min(width, layout, gutter), limits.max(width, layout, gutter))
        val x = (scrollX + pivotX) / zoom * target - pivotX
        val y = (scrollY + pivotY) / zoom * target - pivotY
        return create(width, height, target, x, y, layout, limits, gutter)
    }

    fun resized(newWidth: Int, newHeight: Int, layout: ViewerPageLayout): ViewerViewport {
        if (newWidth == width || width <= 2 * gutter) {
            return create(newWidth, newHeight, zoom, scrollX, scrollY, layout, limits, gutter)
        }
        val ratio = ((newWidth - 2 * gutter) / (width - 2 * gutter)).toFloat()
        val target = (zoom * ratio).coerceIn(limits.min(newWidth, layout, gutter), limits.max(newWidth, layout, gutter))
        val factor = target / zoom.toDouble()
        return create(newWidth, newHeight, target, scrollX * factor, scrollY * factor, layout, limits, gutter)
    }

    fun doubleTapZoom(layout: ViewerPageLayout): Float? {
        val fit = ((width - 2 * gutter) / layout.maxWidth).toFloat()
        if (fit == 0f || fit.isInfinite() || fit.isNaN()) return null
        val low = limits.min(width, layout, gutter)
        val high = limits.max(width, layout, gutter)
        var target = fit.coerceIn(low, high)
        if (abs(target - zoom) < DOUBLE_TAP_EPSILON) target = (fit * DOUBLE_TAP_FIT_FACTOR).coerceIn(low, high)
        if (abs(target - zoom) < DOUBLE_TAP_EPSILON) target = 1f
        return target
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 25f
        const val DOUBLE_TAP_FIT_FACTOR = 2f
        const val DOUBLE_TAP_EPSILON = 0.25f
        const val DOUBLE_TAP_ANIMATION_MS = 200L

        fun fitWidthZoom(width: Int, layout: ViewerPageLayout, gutter: Double = 0.0): Float {
            val fit = ((width - 2 * gutter) / layout.maxWidth).toFloat()
            return if (fit > 0f && fit.isFinite()) fit else fit.coerceIn(MIN_ZOOM, MAX_ZOOM)
        }

        fun atPage(
            page: Int,
            width: Int,
            height: Int,
            layout: ViewerPageLayout,
            limits: ViewerZoomLimits = ViewerZoomLimits.FREE,
            gutter: Double = 0.0
        ): ViewerViewport {
            val target = page.coerceIn(0, layout.pageCount - 1)
            val zoom = fitWidthZoom(width, layout, gutter)
            val y = (layout.top(target) + layout.height(target) / 2) * zoom - height / 2.0
            return create(width, height, zoom, 0.0, y, layout, limits, gutter)
        }

        private fun create(
            width: Int,
            height: Int,
            zoom: Float,
            x: Double,
            y: Double,
            layout: ViewerPageLayout,
            limits: ViewerZoomLimits,
            gutter: Double
        ): ViewerViewport = ViewerViewport(
            width,
            height,
            zoom,
            constrain(x, layout.maxWidth * zoom, width.toDouble(), gutter),
            constrain(y, layout.totalHeight * zoom, height.toDouble(), gutter),
            limits,
            gutter
        )

        private fun constrain(scroll: Double, content: Double, viewport: Double, gutter: Double): Double =
            if (content + 2 * gutter <= viewport) {
                -(viewport - content) / 2
            } else {
                (scroll + gutter).coerceIn(0.0, content + 2 * gutter - viewport) - gutter
            }
    }
}

class ViewerZoomLimits private constructor(private val fitFactor: Float?) {
    fun min(width: Int, layout: ViewerPageLayout, gutter: Double): Float {
        val fit = ViewerViewport.fitWidthZoom(width, layout, gutter)
        return if (fitFactor == null) fit.coerceAtMost(ViewerViewport.MIN_ZOOM) else fit
    }

    fun max(width: Int, layout: ViewerPageLayout, gutter: Double): Float {
        val fit = ViewerViewport.fitWidthZoom(width, layout, gutter)
        return if (fitFactor == null) fit.coerceAtLeast(ViewerViewport.MAX_ZOOM) else fit * fitFactor
    }

    companion object {
        val FREE = ViewerZoomLimits(null)

        fun fitWidth(maxFactor: Float) = ViewerZoomLimits(maxFactor)
    }
}
