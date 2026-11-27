package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditBarLayoutTest {
    @Test
    fun theBackArrowStaysLeftAndTheOtherIconsAlignRightTwentyDpApart() {
        for (width in listOf(452f, 500f, 873f)) {
            assertFalse(ViewerEditBarLayout.scrolls(width))
            assertDp(28f, ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width))
            assertDp(width - 28f, ViewerEditBarLayout.center(ViewerEditBarLayout.ERASE_ALL, width))
            for (index in ViewerEditBarLayout.BACK until ViewerEditBarLayout.ERASE_ALL) {
                assertTrue(ViewerEditBarLayout.center(index + 1, width) - ViewerEditBarLayout.center(index, width) >= 44f - 0.05f)
            }
            for (index in ViewerEditBarLayout.SAVE until ViewerEditBarLayout.ERASE_ALL) {
                assertDp(44f, ViewerEditBarLayout.center(index + 1, width) - ViewerEditBarLayout.center(index, width))
            }
        }
        assertDp(44f, ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, 452f) - ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, 452f))
    }

    @Test
    fun theSaveIconIsTheFirstAfterTheBackArrowRightBeforeTheEye() {
        for (width in listOf(260f, 300f, 340f, 393f, 452f, 873f)) {
            val save = ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width)
            val others = (ViewerEditBarLayout.SCROLL until ViewerEditBarLayout.ITEMS).map { ViewerEditBarLayout.center(it, width) }

            assertTrue(save > ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width))
            assertTrue(others.all { it > save })
            assertDp(44f, ViewerEditBarLayout.center(ViewerEditBarLayout.SCROLL, width) - save)
        }
    }

    @Test
    fun undoAndRedoFollowTheEraserInTheTopRow() {
        for (width in listOf(260f, 393f, 452f, 873f)) {
            val eraser = ViewerEditBarLayout.center(ViewerEditBarLayout.ERASER, width)
            val undo = ViewerEditBarLayout.center(ViewerEditBarLayout.UNDO, width)
            val redo = ViewerEditBarLayout.center(ViewerEditBarLayout.REDO, width)

            assertDp(44f, undo - eraser)
            assertDp(44f, redo - undo)
            assertDp(44f, ViewerEditBarLayout.center(ViewerEditBarLayout.ERASE_VISIBLE, width) - redo)
        }
    }

    @Test
    fun belowFourHundredFiftyTwoDpTheIconsAfterTheBackArrowScrollWithoutShrinkingTheGaps() {
        for (width in listOf(451.9f, 1080f / 2.625f, 393f, 360f, 320f, 300f, 260f)) {
            assertTrue(ViewerEditBarLayout.scrolls(width))
            assertDp(60f, ViewerEditBarLayout.STRIP_START)
            assertDp(width - 16f, ViewerEditBarLayout.stripEnd(width))
            assertDp(28f, ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width))
            assertDp(72f, ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width))
            for (index in ViewerEditBarLayout.BACK until ViewerEditBarLayout.ERASE_ALL) {
                assertDp(44f, ViewerEditBarLayout.center(index + 1, width) - ViewerEditBarLayout.center(index, width))
            }
            val end = ViewerEditBarLayout.length(ViewerEditBarLayout.ITEMS - 1) - (ViewerEditBarLayout.stripEnd(width) - ViewerEditBarLayout.STRIP_START)
            assertTrue(end > 0f)
            assertDp(width - 28f, ViewerEditBarLayout.center(ViewerEditBarLayout.ERASE_ALL, width, scroll = end))
            assertDp(72f - end, ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, width, scroll = end))
            assertDp(28f, ViewerEditBarLayout.center(ViewerEditBarLayout.BACK, width, scroll = end))
        }
        assertDp(130f, ViewerEditBarLayout.center(ViewerEditBarLayout.SWATCH, 300f, scroll = 30f))
    }

    @Test
    fun everyNewIconMovesTheScrollThresholdByOneStep() {
        assertDp(288f, ViewerEditBarLayout.length(7))
        assertDp(376f, ViewerEditBarLayout.length(9))
        assertDp(452f, ViewerEditBarLayout.STRIP_START + ViewerEditBarLayout.length(ViewerEditBarLayout.ITEMS - 1) + 16f)
        assertDp(496f, ViewerEditBarLayout.STRIP_START + ViewerEditBarLayout.length(ViewerEditBarLayout.ITEMS) + 16f)
        assertDp(328f, 16f + ViewerEditBarLayout.length(ROW.size) + ViewerEditBarLayout.ROW_PAD * 2 + 16f)
    }

    @Test
    fun theToolRowIsCenteredUnderTheTabWhereItFits() {
        for (width in listOf(368f, 393f, 1080f / 2.625f, 452f, 500f, 873f)) {
            val row = ROW.map { ViewerEditBarLayout.rowCenter(it, width) }

            assertFalse(ViewerEditBarLayout.rowScrolls(width))
            assertDp(ViewerEditBarLayout.center(ViewerEditBarLayout.GROUP, width), (row.first() + row.last()) / 2)
            for (i in 1 until row.size) assertDp(44f, row[i] - row[i - 1])
            assertTrue(row.first() >= 32f - 0.05f)
            assertTrue(row.last() <= width - 32f + 0.05f)
        }
    }

    @Test
    fun belowThreeHundredSixtyEightDpTheToolRowGoesAsFarTowardsTheTabAsTheRightMarginAllows() {
        for (width in listOf(328f, 340f, 360f, 364f, 367.9f)) {
            val row = ROW.map { ViewerEditBarLayout.rowCenter(it, width) }

            assertFalse(ViewerEditBarLayout.rowScrolls(width))
            assertTrue((row.first() + row.last()) / 2 < ViewerEditBarLayout.center(ViewerEditBarLayout.GROUP, width))
            assertDp(width - 32f, row.last())
            for (i in 1 until row.size) assertDp(44f, row[i] - row[i - 1])
            assertTrue(row.first() >= 32f - 0.05f)
        }
        assertDp(32f, ViewerEditBarLayout.rowCenter(ViewerEditTool.TEXT, 328f))
    }

    @Test
    fun belowThreeHundredTwentyEightDpTheToolRowScrollsBetweenTheTwoMargins() {
        for (width in listOf(327.9f, 300f, 260f)) {
            assertTrue(ViewerEditBarLayout.rowScrolls(width))
            val row = ROW.map { ViewerEditBarLayout.rowCenter(it, width) }
            assertDp(32f, row.first())
            for (i in 1 until row.size) assertDp(44f, row[i] - row[i - 1])
            assertTrue(row.last() > width - 32f)

            val end = ViewerEditBarLayout.length(ROW.size) + ViewerEditBarLayout.ROW_PAD * 2 - (ViewerEditBarLayout.stripEnd(width) - 16f)
            val scrolled = ROW.map { ViewerEditBarLayout.rowCenter(it, width, scroll = end) }
            assertDp(width - 32f, scrolled.last())
            assertDp(32f - end, scrolled.first())
        }
    }

    @Test
    fun aTopRowIconCountsAsShownUntilItHasScrolledCompletelyOutOfTheStrip() {
        assertTrue(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.SWATCH, 300f, scroll = 0f))
        assertTrue(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.SWATCH, 300f, scroll = 109.9f))
        assertFalse(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.SWATCH, 300f, scroll = 110f))
        assertFalse(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.ERASE_ALL, 300f, scroll = 0f))
        assertFalse(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.ERASE_ALL, 300f, scroll = 130f))
        assertTrue(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.ERASE_ALL, 300f, scroll = 130.1f))
        assertFalse(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.SWATCH, 340f, scroll = ViewerEditBarLayout.length(ViewerEditBarLayout.ITEMS - 1) - (ViewerEditBarLayout.stripEnd(340f) - ViewerEditBarLayout.STRIP_START)))
        for (scroll in listOf(0f, 500f)) {
            assertTrue(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.SWATCH, 873f, scroll))
            assertTrue(ViewerEditBarLayout.iconShown(ViewerEditBarLayout.GROUP, 873f, scroll))
        }
    }

    @Test
    fun aToolRowIconCountsAsShownUntilItHasScrolledCompletelyOutOfTheRow() {
        assertTrue(ViewerEditBarLayout.rowIconShown(ViewerEditTool.TEXT, 260f, scroll = 25.9f))
        assertFalse(ViewerEditBarLayout.rowIconShown(ViewerEditTool.TEXT, 260f, scroll = 26f))
        assertFalse(ViewerEditBarLayout.rowIconShown(ViewerEditTool.SIGNATURE, 260f, scroll = 42f))
        assertTrue(ViewerEditBarLayout.rowIconShown(ViewerEditTool.SIGNATURE, 260f, scroll = 42.1f))
        for (scroll in listOf(0f, 500f)) {
            assertTrue(ViewerEditBarLayout.rowIconShown(ViewerEditTool.TEXT, 393f, scroll))
        }
    }

    @Test
    fun theToolRowsSixteenDpMarginsAreMeasuredToTheSelectionCircleOfEveryIcon() {
        assertDp(4f, ViewerEditBarLayout.ROW_PAD)
        assertDp(ViewerEditBarLayout.ACTIVE, ViewerEditBarLayout.ICON + ViewerEditBarLayout.ROW_PAD * 2)
        for (width in listOf(873f, 393f, 340f, 328f, 327.9f, 300f, 260f)) {
            val end = if (ViewerEditBarLayout.rowScrolls(width)) {
                ViewerEditBarLayout.length(ROW.size) + ViewerEditBarLayout.ROW_PAD * 2 - (ViewerEditBarLayout.stripEnd(width) - 16f)
            } else {
                0f
            }
            val first = ViewerEditBarLayout.rowCenter(ViewerEditTool.TEXT, width)
            val last = ViewerEditBarLayout.rowCenter(ViewerEditTool.SIGNATURE, width, scroll = end)

            assertTrue(first - ViewerEditBarLayout.ACTIVE / 2 >= 16f - 0.05f)
            assertTrue(last + ViewerEditBarLayout.ACTIVE / 2 <= width - 16f + 0.05f)
        }
    }

    @Test
    fun aTapReachesOnlyAnIconWhoseDrawingIsAtLeastPartlyInTheStrip() {
        val viewport = ViewerEditBarLayout.stripEnd(340f) - ViewerEditBarLayout.STRIP_START
        val redo = ViewerEditBarLayout.center(ViewerEditBarLayout.REDO, 340f) - ViewerEditBarLayout.STRIP_START
        val save = ViewerEditBarLayout.center(ViewerEditBarLayout.SAVE, 340f) - ViewerEditBarLayout.STRIP_START

        assertFalse(ViewerEditBarLayout.shown(redo, scroll = 0f, viewport = viewport))
        assertFalse(ViewerEditBarLayout.shown(redo, scroll = 2f, viewport = viewport))
        assertTrue(ViewerEditBarLayout.shown(redo, scroll = 2.1f, viewport = viewport))
        assertTrue(ViewerEditBarLayout.shown(save, scroll = 0f, viewport = viewport))
        assertTrue(ViewerEditBarLayout.shown(save, scroll = 21.9f, viewport = viewport))
        assertFalse(ViewerEditBarLayout.shown(save, scroll = 22f, viewport = viewport))
    }

    @Test
    fun onANarrowScreenThePanelIsCenteredAndItsTailPointsAtTheIcon() {
        val place = ViewerEditPanelPlace.of(width = 393f, panelWidth = 352f, iconCenter = 66f, margin = 16f, corner = 20f, underIcon = false)
        val small = ViewerEditPanelPlace.of(width = 360f, panelWidth = 328f, iconCenter = 66f, margin = 16f, corner = 20f, underIcon = false)

        assertEquals(20.5f, place.left, 0.001f)
        assertEquals(45.5f, place.tail, 0.001f)
        assertEquals(16f, small.left, 0.001f)
    }

    @Test
    fun onANarrowScreenTheUnderlinePanelSitsUnderItsIconAsFarAsTheEdgesAllow() {
        val middle = ViewerEditPanelPlace.of(width = 393f, panelWidth = 208f, iconCenter = 123f, margin = 16f, corner = 20f, underIcon = true)
        val nearLeft = ViewerEditPanelPlace.of(width = 393f, panelWidth = 208f, iconCenter = 66f, margin = 16f, corner = 20f, underIcon = true)
        val nearRight = ViewerEditPanelPlace.of(width = 393f, panelWidth = 208f, iconCenter = 300f, margin = 16f, corner = 20f, underIcon = true)

        assertEquals(19f, middle.left, 0.001f)
        assertEquals(104f, middle.tail, 0.001f)
        assertEquals(16f, nearLeft.left, 0.001f)
        assertEquals(50f, nearLeft.tail, 0.001f)
        assertEquals(169f, nearRight.left, 0.001f)
        assertEquals(131f, nearRight.tail, 0.001f)
    }

    @Test
    fun onAWideScreenThePanelSitsUnderTheIconAndStaysOnScreen() {
        val under = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 400f, margin = 16f, corner = 20f, underIcon = false)
        val left = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 120f, margin = 16f, corner = 20f, underIcon = false)
        val right = ViewerEditPanelPlace.of(width = 873f, panelWidth = 352f, iconCenter = 860f, margin = 16f, corner = 20f, underIcon = false)

        assertEquals(224f, under.left, 0.001f)
        assertEquals(176f, under.tail, 0.001f)
        assertEquals(16f, left.left, 0.001f)
        assertEquals(104f, left.tail, 0.001f)
        assertEquals(505f, right.left, 0.001f)
        assertEquals(332f, right.tail, 0.001f)
    }

    private fun assertDp(expected: Float, actual: Float) = assertEquals(expected, actual, 0.05f)

    private companion object {
        val ROW = listOf(
            ViewerEditTool.TEXT,
            ViewerEditTool.UNDERLINE,
            ViewerEditTool.STRIKETHROUGH,
            ViewerEditTool.PEN,
            ViewerEditTool.HIGHLIGHTER,
            ViewerEditTool.FORM_MARK,
            ViewerEditTool.SIGNATURE
        )
    }
}
