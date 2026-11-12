package hu.reelee81.reepdf.viewer

import kotlin.math.abs

class ViewerViewport private constructor(
    val width: Int,
    val height: Int,
    val zoom: Float,
    val scrollX: Double,
    val scrollY: Double
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
        create(width, height, zoom, scrollX + dx, scrollY + dy, layout)

    fun scrolledTo(x: Double, y: Double, layout: ViewerPageLayout): ViewerViewport =
        create(width, height, zoom, x, y, layout)

    fun zoomedTo(newZoom: Float, pivotX: Double, pivotY: Double, layout: ViewerPageLayout): ViewerViewport {
        val target = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val x = (scrollX + pivotX) / zoom * target - pivotX
        val y = (scrollY + pivotY) / zoom * target - pivotY
        return create(width, height, target, x, y, layout)
    }

    fun resized(newWidth: Int, newHeight: Int, layout: ViewerPageLayout): ViewerViewport {
        if (newWidth == width || width <= 0) return create(newWidth, newHeight, zoom, scrollX, scrollY, layout)
        val target = (zoom * (newWidth.toFloat() / width)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = target / zoom.toDouble()
        return create(newWidth, newHeight, target, scrollX * factor, scrollY * factor, layout)
    }

    fun doubleTapZoom(layout: ViewerPageLayout): Float? {
        val fit = (width / layout.maxWidth).toFloat()
        if (fit == 0f || fit.isInfinite() || fit.isNaN()) return null
        var target = fit.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(target - zoom) < DOUBLE_TAP_EPSILON) target = (fit * 2).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(target - zoom) < DOUBLE_TAP_EPSILON) target = 1f
        return target
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 25f
        const val DOUBLE_TAP_EPSILON = 0.25f
        const val DOUBLE_TAP_ANIMATION_MS = 200L

        fun fitWidthZoom(width: Int, layout: ViewerPageLayout): Float =
            (width / layout.maxWidth).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)

        fun atPage(page: Int, width: Int, height: Int, layout: ViewerPageLayout): ViewerViewport {
            val target = page.coerceIn(0, layout.pageCount - 1)
            val zoom = fitWidthZoom(width, layout)
            val y = (layout.top(target) + layout.height(target) / 2) * zoom - height / 2.0
            return create(width, height, zoom, 0.0, y, layout)
        }

        private fun create(
            width: Int,
            height: Int,
            zoom: Float,
            x: Double,
            y: Double,
            layout: ViewerPageLayout
        ): ViewerViewport = ViewerViewport(
            width,
            height,
            zoom,
            constrain(x, layout.maxWidth * zoom, width.toDouble()),
            constrain(y, layout.totalHeight * zoom, height.toDouble())
        )

        private fun constrain(scroll: Double, content: Double, viewport: Double): Double =
            if (content <= viewport) -(viewport - content) / 2 else scroll.coerceIn(0.0, content - viewport)
    }
}
