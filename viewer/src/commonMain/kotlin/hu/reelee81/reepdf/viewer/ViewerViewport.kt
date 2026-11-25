package hu.reelee81.reepdf.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ViewerViewport private constructor(
    val width: Int,
    val height: Int,
    val zoom: Float,
    val scrollX: Double,
    val scrollY: Double,
    private val limits: ViewerZoomLimits,
    private val gutter: Double,
    val cover: Int
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
        create(width, height, zoom, scrollX + dx, scrollY + dy, layout, limits, gutter, cover)

    fun scrolledTo(x: Double, y: Double, layout: ViewerPageLayout): ViewerViewport =
        create(width, height, zoom, x, y, layout, limits, gutter, cover)

    fun covered(bottom: Int, layout: ViewerPageLayout): ViewerViewport =
        create(width, height, zoom, scrollX, scrollY, layout, limits, gutter, bottom.coerceIn(0, height))

    fun zoomedTo(newZoom: Float, pivotX: Double, pivotY: Double, layout: ViewerPageLayout): ViewerViewport {
        val target = newZoom.coerceIn(limits.min(width, layout, gutter), limits.max(width, layout, gutter))
        val x = (scrollX + pivotX) / zoom * target - pivotX
        val y = (scrollY + pivotY) / zoom * target - pivotY
        return create(width, height, target, x, y, layout, limits, gutter, cover)
    }

    fun resized(newWidth: Int, newHeight: Int, layout: ViewerPageLayout): ViewerViewport {
        if (newWidth == width || width <= 2 * gutter) {
            return create(newWidth, newHeight, zoom, scrollX, scrollY, layout, limits, gutter, cover.coerceIn(0, newHeight))
        }
        val ratio = ((newWidth - 2 * gutter) / (width - 2 * gutter)).toFloat()
        val target = (zoom * ratio).coerceIn(limits.min(newWidth, layout, gutter), limits.max(newWidth, layout, gutter))
        val factor = target / zoom.toDouble()
        return create(newWidth, newHeight, target, scrollX * factor, scrollY * factor, layout, limits, gutter, cover.coerceIn(0, newHeight))
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

    fun readableZoom(textSize: Float, readable: Float, layout: ViewerPageLayout): Float? {
        if (!(textSize > 0f) || !(readable > 0f)) return null
        val target = min(readable / textSize, limits.max(width, layout, gutter))
        return if (target > zoom * READABLE_STEP) target else null
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 25f
        const val DOUBLE_TAP_FIT_FACTOR = 2f
        const val DOUBLE_TAP_EPSILON = 0.25f
        const val DOUBLE_TAP_ANIMATION_MS = 200L
        const val READABLE_STEP = 1.1f

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
            gutter: Double = 0.0,
            cover: Int = 0
        ): ViewerViewport {
            val target = page.coerceIn(0, layout.pageCount - 1)
            val zoom = fitWidthZoom(width, layout, gutter)
            val y = (layout.top(target) + layout.height(target) / 2) * zoom - height / 2.0
            return create(width, height, zoom, 0.0, y, layout, limits, gutter, cover.coerceIn(0, height))
        }

        fun atSpot(
            spot: ViewerSpot,
            width: Int,
            height: Int,
            layout: ViewerPageLayout,
            limits: ViewerZoomLimits = ViewerZoomLimits.FREE,
            gutter: Double = 0.0,
            cover: Int = 0
        ): ViewerViewport {
            val page = spot.page.coerceIn(0, layout.pageCount - 1)
            val relative = spot.zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
            val zoom = (fitWidthZoom(width, layout, gutter) * relative)
                .coerceIn(limits.min(width, layout, gutter), limits.max(width, layout, gutter))
            val top = spot.top.takeIf { it.isFinite() } ?: 0.0
            val left = spot.left.takeIf { it.isFinite() } ?: 0.0
            return create(width, height, zoom, left * zoom, (layout.top(page) + top) * zoom, layout, limits, gutter, cover.coerceIn(0, height))
        }

        private fun create(
            width: Int,
            height: Int,
            zoom: Float,
            x: Double,
            y: Double,
            layout: ViewerPageLayout,
            limits: ViewerZoomLimits,
            gutter: Double,
            cover: Int
        ): ViewerViewport = ViewerViewport(
            width,
            height,
            zoom,
            constrain(x, layout.maxWidth * zoom, width.toDouble(), gutter),
            raisable(y, layout.totalHeight * zoom, height.toDouble(), gutter, cover),
            limits,
            gutter,
            cover
        )

        private fun constrain(scroll: Double, content: Double, viewport: Double, gutter: Double): Double =
            if (content + 2 * gutter <= viewport) {
                -(viewport - content) / 2
            } else {
                (scroll + gutter).coerceIn(0.0, content + 2 * gutter - viewport) - gutter
            }

        private fun raisable(scroll: Double, content: Double, viewport: Double, gutter: Double, cover: Int): Double {
            val fitted = constrain(scroll, content, viewport, gutter)
            if (cover <= 0 || scroll <= fitted) return fitted
            return min(scroll, max(fitted, content + gutter - (viewport - cover)))
        }
    }
}

class ViewerSpot(val page: Int, val top: Double, val left: Double, val zoom: Float)

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
