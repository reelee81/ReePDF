package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTooltipPlacementTest {
    private val area = IntRect(44, 112, 1036, 2224)

    private fun place(anchor: IntRect, bubble: IntSize = IntSize(300, 90)) = ViewerTooltipPlacement.place(anchor, bubble, area, GAP)

    @Test
    fun theBubbleSitsCenteredAboveTheAnchor() {
        assertEquals(IntOffset(390, 368), place(IntRect(500, 480, 580, 600)))
    }

    @Test
    fun withoutRoomAboveItGoesBelowTheAnchor() {
        assertEquals(IntOffset(390, 284), place(IntRect(500, 130, 580, 262)))
    }

    @Test
    fun touchingTheTopEdgeStillCountsAsRoomAbove() {
        assertEquals(IntOffset(390, 112), place(IntRect(500, 224, 580, 300)))
        assertEquals(IntOffset(390, 322), place(IntRect(500, 223, 580, 300)))
    }

    @Test
    fun theBubbleStaysInsideTheAreaSideways() {
        assertEquals(IntOffset(44, 368), place(IntRect(0, 480, 60, 600)))
        assertEquals(IntOffset(736, 368), place(IntRect(1000, 480, 1080, 600)))
    }

    @Test
    fun aBubbleWiderThanTheAreaStartsAtItsLeftEdge() {
        assertEquals(IntOffset(44, 368), place(IntRect(500, 480, 580, 600), IntSize(1200, 90)))
    }

    @Test
    fun anOddWidthDifferenceIsTruncatedTowardZero() {
        assertEquals(IntOffset(473, 368), place(IntRect(500, 480, 545, 600), IntSize(100, 90)))
        assertEquals(IntOffset(521, 368), place(IntRect(500, 480, 545, 600), IntSize(2, 90)))
    }

    @Test
    fun anAnchorIsTheItemItselfByDefault() {
        assertEquals(IntRect(100, 201, 221, 355), ViewerTooltipPlacement.anchor(Offset(100.4f, 200.6f), IntSize(121, 154), null, null))
    }

    @Test
    fun anAnchorCanBeCenteredOnTheIconInsteadOfTheItem() {
        assertEquals(IntRect(17, 0, 138, 154), ViewerTooltipPlacement.anchor(Offset.Zero, IntSize(121, 154), 77.25f, null))
    }

    @Test
    fun anAnchorCanSpanTheWholeBarAroundASmallerItem() {
        assertEquals(IntRect(10, 267, 98, 421), ViewerTooltipPlacement.anchor(Offset(10f, 300f), IntSize(88, 88), null, 154f))
    }

    @Test
    fun aTopBarIconGetsItsBubbleBelowTheWholeBar() {
        val anchor = ViewerTooltipPlacement.anchor(Offset(800f, 33f), IntSize(88, 88), null, 154f)
        assertEquals(IntOffset(694, 176), ViewerTooltipPlacement.place(anchor, IntSize(300, 86), IntRect(44, 44, 1036, 2224), GAP))
    }

    private companion object {
        const val GAP = 22
    }
}
