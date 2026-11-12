package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPageLayoutTest {
    private val layout = ViewerPageLayout(floatArrayOf(595f, 842f, 595f), floatArrayOf(842f, 595f, 842f), 22.0)

    @Test
    fun pagesFollowEachOtherWithTheSpacing() {
        assertEquals(listOf(0.0, 864.0, 1481.0), (0..2).map(layout::top))
        assertEquals(listOf(842.0, 1459.0, 2323.0), (0..2).map(layout::bottom))
        assertEquals(2323.0, layout.totalHeight, 0.0)
        assertEquals(842.0, layout.maxWidth, 0.0)
        assertEquals(595.0, layout.height(1), 0.0)
    }

    @Test
    fun thirtyThousandPagesKeepPrecisePositions() {
        val count = 30_000
        val big = ViewerPageLayout(FloatArray(count) { 595.28f }, FloatArray(count) { 841.89f }, 22.0)
        val step = 841.89f.toDouble() + 22.0
        assertEquals(29_999 * step, big.top(29_999), 1e-3)
        assertEquals(25_000..25_000, big.labelPages(big.top(25_000) - 1.0, big.bottom(25_000) + 1.0))
        assertEquals(24_999..25_000, big.visiblePages(big.bottom(24_999) - 1.0, big.top(25_000) + 1.0))
    }

    @Test
    fun visiblePagesIncludePartlyVisibleOnes() {
        assertEquals(0..1, layout.visiblePages(800.0, 900.0))
        assertEquals(0..2, layout.visiblePages(0.0, 2323.0))
        assertTrue(layout.visiblePages(845.0, 860.0).isEmpty())
    }

    @Test
    fun labelShowsFullyVisiblePagesOrTheCentralOne() {
        assertEquals(0..1, layout.labelPages(0.0, 1500.0))
        assertEquals(1..1, layout.labelPages(850.0, 1470.0))
        assertEquals(0..0, layout.labelPages(100.0, 1000.0))
        assertEquals(1..1, layout.labelPages(900.0, 1400.0))
        assertEquals(2..2, layout.labelPages(1500.0, 2000.0))
    }

    @Test
    fun narrowPageIsCenteredInAWiderViewport() {
        assertEquals(123.5, layout.left(0, -79.0, 1000.0), 1e-9)
        assertEquals(0.0, layout.left(0, -300.0, 1000.0), 1e-9)
    }

    @Test
    fun widerPagesFollowTheHorizontalScrollProportionally() {
        assertEquals(0.0, layout.left(1, 500.0, 400.0), 1e-9)
        assertEquals(247.0, layout.left(0, 500.0, 400.0), 1e-9)
        assertEquals(123.5, layout.left(0, 221.0, 400.0), 1e-9)
        assertEquals(0.0, layout.left(0, 0.0, 400.0), 1e-9)
    }

    @Test
    fun thePageUnderAPointIsTheOneContainingItOrTheNearerOneInTheGap() {
        assertEquals(0, layout.pageAt(-50.0))
        assertEquals(0, layout.pageAt(0.0))
        assertEquals(0, layout.pageAt(842.0))
        assertEquals(0, layout.pageAt(852.0))
        assertEquals(1, layout.pageAt(854.0))
        assertEquals(1, layout.pageAt(864.0))
        assertEquals(1, layout.pageAt(1459.0))
        assertEquals(2, layout.pageAt(1481.0))
        assertEquals(2, layout.pageAt(5000.0))
    }

    @Test
    fun pageLabelTextArguments() {
        assertEquals(ViewerPageLabel.Single(5, 10), ViewerPageLabel.of(4..4, 10))
        assertEquals(ViewerPageLabel.Range(1, 3, 10), ViewerPageLabel.of(0..2, 10))
    }
}
