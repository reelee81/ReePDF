package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerViewportTest {
    private val layout = ViewerPageLayout(FloatArray(10) { 595f }, FloatArray(10) { 842f }, 22.0)

    @Test
    fun fitWidthUsesTheWidestPage() {
        assertEquals(1080f / 595f, ViewerViewport.fitWidthZoom(1080, layout), 1e-6f)
        assertEquals(100f / 595f, ViewerViewport.fitWidthZoom(100, layout), 1e-6f)
        assertEquals(ViewerViewport.MIN_ZOOM, ViewerViewport.fitWidthZoom(0, layout), 0f)
    }

    @Test
    fun aPageWiderThanTheZoomLimitStillFitsTheWidth() {
        val fit = 1080f / 2384f
        val start = ViewerViewport.atPage(0, 1080, 2000, poster)
        assertEquals(fit, start.zoom, 1e-6f)
        assertEquals(0.0, start.scrollX, 1e-3)
        assertEquals(fit, start.zoomedTo(0.01f, 0.0, 0.0, poster).zoom, 1e-6f)
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, start.doubleTapZoom(poster)!!, 1e-6f)
        val zoomedIn = start.zoomedTo(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, 0.0, 0.0, poster)
        assertEquals(fit, zoomedIn.doubleTapZoom(poster)!!, 1e-6f)
        assertEquals(2160f / 2384f, start.resized(2160, 1000, poster).zoom, 1e-5f)
    }

    @Test
    fun aPageWiderThanTheZoomLimitFitsBetweenTheGutters() {
        val fit = ((1080.0 - 2 * GUTTER) / 2384.0).toFloat()
        val start = ViewerViewport.atPage(0, 1080, 2000, poster, fitLimits, GUTTER)
        assertEquals(fit, start.zoom, 1e-6f)
        assertEquals(-GUTTER, start.scrollX, 1e-3)
        assertEquals(fit, start.zoomedTo(0.01f, 0.0, 0.0, poster).zoom, 1e-6f)
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, start.zoomedTo(100f, 0.0, 0.0, poster).zoom, 1e-6f)
    }

    @Test
    fun aPageNarrowerThanTheZoomLimitFillsTheWidthBetweenTheGutters() {
        val tiny = ViewerPageLayout(floatArrayOf(20f), floatArrayOf(30f), 0.0)
        val fit = ((1080.0 - 2 * GUTTER) / 20.0).toFloat()
        val start = ViewerViewport.atPage(0, 1080, 2000, tiny, fitLimits, GUTTER)
        assertEquals(fit, start.zoom, 1e-4f)
        assertEquals(-GUTTER, start.scrollX, 1e-3)
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, start.zoomedTo(1000f, 0.0, 0.0, tiny).zoom, 1e-3f)
        assertEquals(fit, start.zoomedTo(0.01f, 0.0, 0.0, tiny).zoom, 1e-4f)
    }

    @Test
    fun goingToAPageCentersItAtFitWidth() {
        val viewport = ViewerViewport.atPage(3, 1080, 2000, layout)
        val zoom = 1080f / 595f
        assertEquals(zoom, viewport.zoom, 1e-6f)
        assertEquals((3 * 864.0 + 421.0) * zoom - 1000.0, viewport.scrollY, 1e-3)
        assertEquals(0.0, viewport.scrollX, 1e-3)
    }

    @Test
    fun goingToTheFirstOrLastPageStaysInsideTheContent() {
        assertEquals(0.0, ViewerViewport.atPage(0, 1080, 2000, layout).scrollY, 0.0)
        val last = ViewerViewport.atPage(9, 1080, 2000, layout)
        assertEquals(8618.0 * last.zoom - 2000.0, last.scrollY, 1e-3)
        assertEquals(last.scrollY, ViewerViewport.atPage(42, 1080, 2000, layout).scrollY, 0.0)
    }

    @Test
    fun zoomKeepsThePivotPointInPlace() {
        val start = ViewerViewport.atPage(5, 1080, 2000, layout)
        val zoomed = start.zoomedTo(4f, 500.0, 700.0, layout)
        assertEquals((start.scrollX + 500.0) / start.zoom, (zoomed.scrollX + 500.0) / zoomed.zoom, 1e-6)
        assertEquals((start.scrollY + 700.0) / start.zoom, (zoomed.scrollY + 700.0) / zoomed.zoom, 1e-6)
    }

    @Test
    fun zoomStaysWithinTheLimits() {
        val start = ViewerViewport.atPage(5, 1080, 2000, layout)
        assertEquals(ViewerViewport.MAX_ZOOM, start.zoomedTo(100f, 0.0, 0.0, layout).zoom, 0f)
        assertEquals(ViewerViewport.MIN_ZOOM, start.zoomedTo(0.01f, 0.0, 0.0, layout).zoom, 0f)
    }

    @Test
    fun smallerContentIsCentered() {
        val small = ViewerViewport.atPage(0, 1080, 2000, layout).zoomedTo(0.5f, 0.0, 0.0, layout)
        assertEquals(-(1080.0 - 297.5) / 2, small.scrollX, 1e-9)
        val short = ViewerPageLayout(floatArrayOf(595f), floatArrayOf(842f), 22.0)
        val single = ViewerViewport.atPage(0, 1080, 2000, short)
        assertEquals(-(2000.0 - 842.0 * single.zoom) / 2, single.scrollY, 1e-3)
    }

    @Test
    fun scrollingStopsAtTheEdges() {
        val start = ViewerViewport.atPage(5, 1080, 2000, layout)
        assertEquals(0.0, start.scrolledBy(0.0, -1e9, layout).scrollY, 0.0)
        assertEquals(8618.0 * start.zoom - 2000.0, start.scrolledBy(0.0, 1e9, layout).scrollY, 1e-3)
        assertEquals(start.scrollY + 10.0, start.scrolledBy(0.0, 10.0, layout).scrollY, 1e-9)
    }

    @Test
    fun widthChangeScalesZoomAndScroll() {
        val start = ViewerViewport.atPage(5, 1080, 2000, layout)
        val wide = start.resized(2160, 1000, layout)
        assertEquals(start.zoom * 2, wide.zoom, 1e-5f)
        assertEquals(start.scrollY * 2, wide.scrollY, 1e-2)
        assertEquals(ViewerViewport.fitWidthZoom(2160, layout), wide.zoom, 1e-5f)
    }

    @Test
    fun doubleTapZoomsInFromFitWidthAndBackOtherwise() {
        val exact = ViewerPageLayout(floatArrayOf(595f), floatArrayOf(842f), 22.0)
        val fit = ViewerViewport.atPage(0, 1190, 2000, exact)
        assertEquals(2f, fit.zoom, 0f)
        assertEquals(4f, fit.doubleTapZoom(exact)!!, 0f)
        assertEquals(4f, fit.zoomedTo(2.24f, 0.0, 0.0, exact).doubleTapZoom(exact)!!, 0f)
        assertEquals(2f, fit.zoomedTo(2.25f, 0.0, 0.0, exact).doubleTapZoom(exact)!!, 0f)
        assertEquals(2f, fit.zoomedTo(4f, 0.0, 0.0, exact).doubleTapZoom(exact)!!, 0f)
    }

    @Test
    fun doubleTapFallsBackToOneWhenNothingElseChanges() {
        val tiny = ViewerPageLayout(floatArrayOf(20f), floatArrayOf(30f), 0.0)
        val atMax = ViewerViewport.atPage(0, 1080, 2000, tiny)
        assertEquals(1080f / 20f, atMax.zoom, 1e-4f)
        assertEquals(atMax.zoom, atMax.zoomedTo(1000f, 0.0, 0.0, tiny).zoom, 0f)
        assertEquals(1f, atMax.doubleTapZoom(tiny)!!, 0f)
    }

    @Test
    fun doubleTapNeedsAWidth() {
        assertNull(ViewerViewport.atPage(0, 0, 2000, layout).doubleTapZoom(layout))
    }

    @Test
    fun fitWidthLimitsKeepTheZoomBetweenFitAndTheDoubleTapStep() {
        val start = ViewerViewport.atPage(0, 1080, 2000, single, fitLimits)
        val fit = 1080f / 595f
        assertEquals(fit, start.zoom, 1e-6f)
        val zoomedIn = start.zoomedTo(100f, 0.0, 0.0, single)
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, zoomedIn.zoom, 1e-6f)
        assertEquals(fit, start.zoomedTo(0.01f, 0.0, 0.0, single).zoom, 1e-6f)
        assertEquals(fit, zoomedIn.zoomedTo(0.01f, 0.0, 0.0, single).zoom, 1e-6f)
        val scrolled = zoomedIn.scrolledBy(0.0, 100.0, single).scrolledTo(10.0, 10.0, single)
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, scrolled.zoomedTo(100f, 0.0, 0.0, single).zoom, 1e-6f)
    }

    @Test
    fun theDoubleTapStepIsTheFitWidthLimit() {
        val fit = ViewerViewport.atPage(0, 1080, 2000, single)
        assertEquals(fit.zoom * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, fit.doubleTapZoom(single)!!, 1e-6f)
        assertEquals(
            ViewerViewport.atPage(0, 1080, 2000, single, fitLimits).zoomedTo(100f, 0.0, 0.0, single).zoom,
            fit.doubleTapZoom(single)!!,
            1e-6f
        )
    }

    @Test
    fun aRotationKeepsTheZoomRelativeToTheFitWidth() {
        val zoomedIn = ViewerViewport.atPage(0, 1080, 2000, single, fitLimits).zoomedTo(100f, 0.0, 0.0, single)
        val rotated = zoomedIn.resized(2400, 1000, single)
        assertEquals(2400f / 595f * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, rotated.zoom, 1e-4f)
        assertEquals(1080f / 595f * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, rotated.resized(1080, 2000, single).zoom, 1e-4f)
        val atFit = ViewerViewport.atPage(0, 1080, 2000, single, fitLimits).resized(2400, 1000, single)
        assertEquals(2400f / 595f, atFit.zoom, 1e-4f)
        assertEquals(2400f / 595f, atFit.zoomedTo(0.01f, 0.0, 0.0, single).zoom, 1e-4f)
    }

    @Test
    fun aGutterNarrowsTheFitWidthAndFramesThePage() {
        val fit = (1080.0 - 2 * GUTTER) / 595.0
        assertEquals(fit.toFloat(), ViewerViewport.fitWidthZoom(1080, single, GUTTER), 1e-6f)
        val start = ViewerViewport.atPage(0, 1080, 1000, single, fitLimits, GUTTER)
        assertEquals(fit.toFloat(), start.zoom, 1e-6f)
        assertEquals(-GUTTER, start.scrollX, 1e-3)
        assertEquals(-GUTTER, start.scrolledBy(0.0, -1e9, single).scrollY, 1e-9)
        assertEquals(842.0 * start.zoom - 1000.0 + GUTTER, start.scrolledBy(0.0, 1e9, single).scrollY, 1e-3)
    }

    @Test
    fun aZoomedPageScrollsUpToTheGutterOnEverySide() {
        val zoomed = ViewerViewport.atPage(0, 1080, 1000, single, fitLimits, GUTTER).zoomedTo(100f, 0.0, 0.0, single)
        val width = 595.0 * zoomed.zoom
        assertEquals(-GUTTER, zoomed.scrolledBy(-1e9, 0.0, single).scrollX, 1e-9)
        assertEquals(width - 1080.0 + GUTTER, zoomed.scrolledBy(1e9, 0.0, single).scrollX, 1e-3)
        assertEquals(zoomed.scrollX + 5.0, zoomed.scrolledBy(5.0, 0.0, single).scrollX, 1e-9)
    }

    @Test
    fun aSmallPageInsideTheGutterStaysCentered() {
        val short = ViewerViewport.atPage(0, 1080, 2000, single, fitLimits, GUTTER)
        assertEquals(-(2000.0 - 842.0 * short.zoom) / 2, short.scrollY, 1e-3)
        assertEquals(short.scrollY, short.scrolledBy(0.0, 300.0, single).scrollY, 0.0)
    }

    @Test
    fun aRotationKeepsAGutteredPageAtFitWidth() {
        val start = ViewerViewport.atPage(0, 1080, 2000, single, fitLimits, GUTTER)
        val wide = start.resized(2400, 1000, single)
        assertEquals(ViewerViewport.fitWidthZoom(2400, single, GUTTER), wide.zoom, 1e-5f)
        assertEquals(-GUTTER, wide.scrollX, 1e-3)
        assertEquals(ViewerViewport.fitWidthZoom(1080, single, GUTTER), wide.resized(1080, 2000, single).zoom, 1e-5f)
    }

    @Test
    fun noGutterKeepsTheViewerLimitsExactly() {
        val plain = ViewerViewport.atPage(5, 1080, 2000, layout)
        val explicit = ViewerViewport.atPage(5, 1080, 2000, layout, ViewerZoomLimits.FREE, 0.0)
        assertEquals(plain.zoom, explicit.zoom, 0f)
        assertEquals(plain.scrollY, explicit.scrollY, 0.0)
        assertEquals(0.0, plain.scrolledBy(-1e9, -1e9, layout).scrollY, 0.0)
        assertEquals(1080f / 595f, ViewerViewport.fitWidthZoom(1080, layout, 0.0), 0f)
        assertEquals(plain.resized(2160, 1000, layout).zoom, explicit.resized(2160, 1000, layout).zoom, 0f)
    }

    private val single = ViewerPageLayout(floatArrayOf(595f), floatArrayOf(842f), 0.0)
    private val poster = ViewerPageLayout(floatArrayOf(2384f), floatArrayOf(3370f), 0.0)
    private val fitLimits = ViewerZoomLimits.fitWidth(ViewerViewport.DOUBLE_TAP_FIT_FACTOR)

    private companion object {
        const val GUTTER = 72.0
    }
}
