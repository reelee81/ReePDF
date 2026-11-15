package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditBarLayoutTest {
    @Test
    fun theBackArrowStaysLeftAndTheOtherIconsAlignRightTwentyDpApart() {
        for (width in listOf(393f, 1080f / 2.625f, 873f)) {
            assertDp(28f, ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width))
            assertDp(width - 28f, ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width))
            for (index in ViewerEditBarLayout.SCROLL until ViewerEditBarLayout.SAVE) {
                assertDp(44f, ViewerEditBarLayout.center(index + 1, width) - ViewerEditBarLayout.center(index, width))
            }
        }
    }

    @Test
    fun onANarrowScreenTheGapsShrinkBeforeTheIconsTouch() {
        val width = 360f
        val step = ViewerEditBarLayout.step(width)

        assertTrue(step < 44f)
        assertDp(step, ViewerEditBarLayout.center(ViewerEditBarLayout.SCROLL, width) - ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width))
        assertDp(width - 28f, ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width))
    }

    @Test
    fun theToolRowIsCenteredUnderTheTabAndKeepsTheBackArrowsMargin() {
        for (width in listOf(260f, 320f, 360f, 393f, 1080f / 2.625f, 873f)) {
            val row = listOf(
                ViewerEditTool.TEXT,
                ViewerEditTool.UNDERLINE,
                ViewerEditTool.STRIKETHROUGH,
                ViewerEditTool.PEN,
                ViewerEditTool.HIGHLIGHTER,
                ViewerEditTool.SIGNATURE
            ).map { ViewerEditBarLayout.rowCenter(it, width) }
            val step = ViewerEditBarLayout.step(width)

            assertDp(ViewerEditBarLayout.center(ViewerEditBarLayout.GROUP, width), (row.first() + row.last()) / 2)
            for (i in 1 until row.size) assertDp(step, row[i] - row[i - 1])
            assertTrue(row.first() >= ViewerEditBarLayout.center(0, width))
            assertTrue(row.last() <= ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width))
        }
    }

    @Test
    fun onANarrowScreenThePanelIsCenteredAndItsTailPointsAtTheIcon() {
        val place = ViewerEditPanelPlace.of(width = 393f, panelWidth = 352f, iconCenter = 66f, margin = 16f, corner = 20f)
        val small = ViewerEditPanelPlace.of(width = 360f, panelWidth = 328f, iconCenter = 66f, margin = 16f, corner = 20f)

        assertEquals(20.5f, place.left, 0.001f)
        assertEquals(45.5f, place.tail, 0.001f)
        assertEquals(16f, small.left, 0.001f)
    }

    @Test
    fun onAWideScreenThePanelSitsUnderTheIconAndStaysOnScreen() {
        val under = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 400f, margin = 16f, corner = 20f)
        val left = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 120f, margin = 16f, corner = 20f)
        val right = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 860f, margin = 16f, corner = 20f)

        assertEquals(224f, under.left, 0.001f)
        assertEquals(176f, under.tail, 0.001f)
        assertEquals(16f, left.left, 0.001f)
        assertEquals(104f, left.tail, 0.001f)
        assertEquals(505f, right.left, 0.001f)
        assertEquals(332f, right.tail, 0.001f)
    }

    private fun assertDp(expected: Float, actual: Float) = assertEquals(expected, actual, 0.05f)
}
