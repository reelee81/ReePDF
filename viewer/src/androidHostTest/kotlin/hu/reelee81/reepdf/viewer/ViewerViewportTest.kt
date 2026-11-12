package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerViewportTest {
    private val layout = ViewerPageLayout(FloatArray(10) { 595f }, FloatArray(10) { 842f }, 22.0)

    @Test
    fun fitWidthUsesTheWidestPage() {
        assertEquals(1080f / 595f, ViewerViewport.fitWidthZoom(1080, layout), 1e-6f)
        assertEquals(ViewerViewport.MIN_ZOOM, ViewerViewport.fitWidthZoom(100, layout), 0f)
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
        assertEquals(ViewerViewport.MAX_ZOOM, atMax.zoom, 0f)
        assertEquals(1f, atMax.doubleTapZoom(tiny)!!, 0f)
    }

    @Test
    fun doubleTapNeedsAWidth() {
        assertNull(ViewerViewport.atPage(0, 0, 2000, layout).doubleTapZoom(layout))
    }
}
