package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerBubblePlacementTest {
    private val bubble = Size(400f, 90f)
    private val view = Size(1000f, 2000f)

    private fun place(selection: Rect) = ViewerBubblePlacement.place(selection, bubble, view, GAP, HANDLE, MARGIN)

    private fun assertPlaced(x: Float, y: Float, actual: Offset?) {
        assertNotNull(actual)
        assertEquals(listOf(x, y), listOf(actual!!.x, actual.y))
    }

    @Test
    fun theBubbleSitsCentredAboveTheSelection() {
        assertPlaced(250f, 390f, place(Rect(300f, 500f, 600f, 540f)))
    }

    @Test
    fun withoutRoomAboveItGoesBelowTheHandles() {
        assertPlaced(250f, 170f, place(Rect(300f, 50f, 600f, 90f)))
    }

    @Test
    fun aSelectionFillingTheViewGetsTheBubbleInTheMiddle() {
        assertPlaced(300f, 955f, place(Rect(0f, -100f, 1000f, 2100f)))
    }

    @Test
    fun theBubbleStaysInsideTheViewSideways() {
        assertPlaced(592f, 390f, place(Rect(950f, 500f, 990f, 540f)))
        assertPlaced(MARGIN, 390f, place(Rect(0f, 500f, 20f, 540f)))
    }

    @Test
    fun aSelectionOutsideTheViewHasNoBubble() {
        assertNull(place(Rect(0f, 2100f, 100f, 2200f)))
        assertNull(place(Rect(-200f, 500f, -10f, 540f)))
    }

    private companion object {
        const val GAP = 20f
        const val HANDLE = 60f
        const val MARGIN = 8f
    }
}
