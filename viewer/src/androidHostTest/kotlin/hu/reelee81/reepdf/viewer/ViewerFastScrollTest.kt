package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFastScrollTest {
    private val scroller = ViewerFastScroll(topMargin = 2, bottomMargin = 2, thumbHeight = 48)

    @Test
    fun thumbFollowsTheScrollPosition() {
        assertEquals(2, scroller.thumbTop(0.0, 1000, 10_000.0))
        assertEquals(476, scroller.thumbTop(4500.0, 1000, 10_000.0))
        assertEquals(950, scroller.thumbTop(9000.0, 1000, 10_000.0))
    }

    @Test
    fun draggingTheThumbScrollsProportionally() {
        assertEquals(4500.0, scroller.scrollFor(476.0, 1000, 10_000.0), 1e-9)
        assertEquals(0.0, scroller.scrollFor(-50.0, 1000, 10_000.0), 0.0)
        assertEquals(9000.0, scroller.scrollFor(2000.0, 1000, 10_000.0), 0.0)
    }

    @Test
    fun thumbStaysBetweenTheMargins() {
        assertEquals(2, scroller.constrain(-10.0, 1000))
        assertEquals(950, scroller.constrain(5000.0, 1000))
    }

    @Test
    fun fastScrollNeedsRoomAndScrollableContent() {
        assertTrue(scroller.canScroll(1000, 10_000.0))
        assertFalse(scroller.canScroll(1000, 900.0))
        assertFalse(scroller.canScroll(50, 10_000.0))
    }
}
