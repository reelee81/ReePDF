package hu.reelee81.reepdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHitTest {
    private val left = 200
    private val top = 1500
    private val width = 600
    private val height = 150

    private fun inside(x: Float, y: Float): Boolean = TouchHit.isInside(x, y, left, top, width, height)

    @Test
    fun aTouchOnTheFieldIsInside() {
        assertTrue(inside(500f, 1575f))
        assertTrue(inside(200f, 1500f))
        assertTrue(inside(799.9f, 1649.9f))
    }

    @Test
    fun theRightAndBottomEdgesAreOutside() {
        assertFalse(inside(800f, 1575f))
        assertFalse(inside(500f, 1650f))
    }

    @Test
    fun aTouchOneKeyboardShiftAboveTheFieldIsOutside() {
        val shift = 800
        assertFalse(inside(500f, (top - shift + height / 2).toFloat()))
    }

    @Test
    fun aTouchBesideTheFieldIsOutside() {
        assertFalse(inside(199.9f, 1575f))
        assertFalse(inside(900f, 1575f))
    }
}
