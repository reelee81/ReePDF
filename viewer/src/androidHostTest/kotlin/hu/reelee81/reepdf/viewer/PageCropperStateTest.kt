package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCropperStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val page = FakePage()
    private val states = mutableListOf<PageCropperState>()

    @After
    fun tearDown() {
        states.forEach(PageCropperState::close)
        scope.cancel()
    }

    private class FakePage : ViewerPageSource {
        var failing = false
        var closed = 0
            private set

        override val pageCount = 1

        override suspend fun pageSizes(): ViewerPageSizes {
            if (failing) throw IllegalStateException("broken page")
            return ViewerPageSizes(floatArrayOf(PAGE_WIDTH), floatArrayOf(PAGE_HEIGHT))
        }

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() {
            closed++
        }
    }

    private fun spec(left: Float = 0.2f, top: Float = 0.25f, right: Float = 0.8f, bottom: Float = 0.75f, allPages: Boolean = false) =
        PageCropperSpec(left, top, right, bottom, allPages, MIN_SIZE)

    private fun state(spec: PageCropperSpec = spec()) = PageCropperState(spec, scope).also(states::add)

    private fun opened(spec: PageCropperSpec = spec(), width: Int = VIEW_WIDTH, height: Int = VIEW_HEIGHT): PageCropperState {
        val state = state(spec)
        state.open { page }
        state.surface.onSize(width, height, SPACING, GUTTER)
        return state
    }

    private fun PageCropperState.zoom() = checkNotNull(surface.viewport).zoom

    private fun PageCropperState.shown() = checkNotNull(frameOnScreen())

    private fun PageCropperState.grab(x: Float, y: Float) = grab(x, y, MARK_LENGTH, MARK_SLOP, LINE_SLOP)

    private fun PageCropperState.dragBy(x: Float, y: Float, dx: Float, dy: Float) {
        assertTrue(grab(x, y))
        dragTo(x + dx, y + dy)
        endDrag()
    }

    private fun assertFrame(expected: Rect, actual: Rect, tolerance: Float = 1e-4f) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    @Test
    fun theStartFrameIsKeptInsideThePageAndABrokenOneBecomesTheWholePage() {
        assertFrame(Rect(0f, 0.25f, 1f, 0.75f), state(spec(left = -0.5f, right = 1.5f)).frame, 0f)
        assertEquals(PageCropperState.FULL_FRAME, state(spec(left = 0.6f, right = 0.4f)).frame)
        assertEquals(PageCropperState.FULL_FRAME, state(spec(top = Float.NaN)).frame)
        assertFalse(state().isModified)
    }

    @Test
    fun theFramedPageOpensAtFitWidthInsideTheGutter() {
        val state = state()
        assertFalse(state.isReady)
        state.open { page }
        state.surface.onSize(VIEW_WIDTH, VIEW_HEIGHT, SPACING, GUTTER)

        assertTrue(state.isReady)
        assertNull(state.openError)
        assertEquals(((VIEW_WIDTH - 2 * GUTTER) / PAGE_WIDTH).toFloat(), state.zoom(), 1e-6f)
        state.reset()
        val whole = state.shown()
        assertEquals(GUTTER.toFloat(), whole.left, 1e-2f)
        assertEquals((VIEW_WIDTH - GUTTER).toFloat(), whole.right, 1e-2f)
        assertEquals((VIEW_HEIGHT - PAGE_HEIGHT * state.zoom()) / 2f, whole.top, 1e-2f)
    }

    @Test
    fun aCornerMarkMovesTwoSides() {
        val state = opened()
        val shown = state.shown()
        val zoom = state.zoom()
        state.dragBy(shown.left + 3f, shown.top + 3f, -PAGE_WIDTH * zoom * 0.1f, PAGE_HEIGHT * zoom * 0.05f)

        assertFrame(Rect(0.1f, 0.3f, 0.8f, 0.75f), state.frame)
        assertTrue(state.isModified)
    }

    @Test
    fun aMiddleMarkMovesOneSide() {
        val state = opened()
        val shown = state.shown()
        val zoom = state.zoom()
        state.dragBy(shown.right + 10f, shown.center.y, PAGE_WIDTH * zoom * 0.1f, 300f)

        assertFrame(Rect(0.2f, 0.25f, 0.9f, 0.75f), state.frame)
    }

    @Test
    fun theFrameLineAndTheInnerLinesMoveTheWholeFrame() {
        val state = opened()
        val zoom = state.zoom()
        val shown = state.shown()
        state.dragBy(shown.left + shown.width * 0.3f, shown.top - 10f, PAGE_WIDTH * zoom * 0.1f, 0f)
        assertFrame(Rect(0.3f, 0.25f, 0.9f, 0.75f), state.frame)

        val moved = state.shown()
        state.dragBy(moved.left + moved.width / 3f + 5f, moved.top + moved.height * 0.6f, 0f, -PAGE_HEIGHT * zoom * 0.05f)
        assertFrame(Rect(0.3f, 0.2f, 0.9f, 0.7f), state.frame)
    }

    @Test
    fun elsewhereTheFingerScrollsInsteadOfGrabbing() {
        val state = opened()
        val shown = state.shown()
        assertFalse(state.grab(shown.left + shown.width / 6f, shown.top + shown.height / 6f))
        assertFalse(state.grab(shown.left - LINE_SLOP - MARK_SLOP - 5f, shown.center.y + shown.height / 6f))
        assertFalse(state.grab(shown.center.x + shown.width / 4f, shown.bottom + LINE_SLOP + 1f))
        assertTrue(state.grab(shown.center.x + shown.width / 4f, shown.bottom + LINE_SLOP - 1f))
        state.endDrag()
        state.dragTo(0f, 0f)
        assertFalse(state.isModified)
    }

    @Test
    fun aCornerWinsOverTheOtherGripsOfASmallFrame() {
        val state = opened(spec(left = 0.5f, top = 0.5f, right = 0.53f, bottom = 0.52f))
        val shown = state.shown()
        val zoom = state.zoom()
        state.dragBy(shown.right, shown.center.y - 2f, PAGE_WIDTH * zoom * 0.1f, -PAGE_HEIGHT * zoom * 0.1f)

        assertFrame(Rect(0.5f, 0.4f, 0.63f, 0.52f), state.frame)
    }

    @Test
    fun aDragKeepsTheMinimumSizeAndStaysOnThePage() {
        val state = opened()
        val shown = state.shown()
        state.dragBy(shown.left, shown.top, 10_000f, 10_000f)
        assertFrame(Rect(0.8f - MIN_SIZE, 0.75f - MIN_SIZE, 0.8f, 0.75f), state.frame)

        val moving = opened()
        val line = moving.shown()
        moving.dragBy(line.left + line.width * 0.3f, line.top - 10f, 10_000f, 10_000f)
        assertFrame(Rect(0.4f, 0.5f, 1f, 1f), moving.frame)
    }

    @Test
    fun theFrameFollowsThePageWhileTheSurfaceScrollsUnderTheFinger() {
        val state = opened(width = WIDE_WIDTH, height = WIDE_HEIGHT)
        val shown = state.shown()
        val x = shown.center.x + shown.width / 4f
        val y = shown.top + shown.height / 3f
        val before = state.frame

        assertTrue(state.grab(x, y))
        state.surface.scrollBy(0.0, 100.0)
        state.dragTo(x, y)
        state.endDrag()

        val moved = 100f / (PAGE_HEIGHT * state.zoom())
        assertFrame(Rect(before.left, before.top + moved, before.right, before.bottom + moved), state.frame)
    }

    @Test
    fun resetShowsTheWholePageAndCountsAsAChangeOnlyFromACrop() {
        val cropped = opened()
        cropped.reset()
        assertEquals(PageCropperState.FULL_FRAME, cropped.frame)
        assertTrue(cropped.isModified)

        val whole = opened(spec(0f, 0f, 1f, 1f))
        whole.reset()
        assertFalse(whole.isModified)
    }

    @Test
    fun theAllPagesSwitchIsNotAFrameChange() {
        val state = opened(spec(allPages = true))
        assertTrue(state.allPages)
        state.toggleAllPages()
        assertFalse(state.allPages)
        assertFalse(state.isModified)
        assertFalse(state.handleBack())
    }

    @Test
    fun backLeavesAnUnchangedCropAndAsksAboutAChangedOne() {
        val state = opened()
        assertFalse(state.handleBack())
        val shown = state.shown()
        state.dragBy(shown.left, shown.top, 20f, 20f)

        assertTrue(state.handleBack())
        assertTrue(state.asking)
        assertTrue(state.handleBack())
        assertFalse(state.asking)
        assertTrue(state.handleBack())
        state.dismissQuestion()
        assertFalse(state.asking)
    }

    @Test
    fun pinchingStaysBetweenFitWidthAndTheDoubleTapStep() {
        val state = opened()
        val fit = state.zoom()

        state.surface.zoomBy(10f, 0.0, 0.0)
        state.surface.endZoom()
        assertEquals(fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR, state.zoom(), 1e-5f)
        state.surface.zoomBy(0.01f, 0.0, 0.0)
        state.surface.endZoom()
        assertEquals(fit, state.zoom(), 1e-5f)
    }

    @Test
    fun aRotationRefitsThePageAndKeepsTheFrame() {
        val state = opened()
        val shown = state.shown()
        state.dragBy(shown.left, shown.top, 30f, 40f)
        val frame = state.frame

        state.surface.onSize(WIDE_WIDTH, WIDE_HEIGHT, SPACING, GUTTER)

        assertEquals(frame, state.frame)
        assertEquals(((WIDE_WIDTH - 2 * GUTTER) / PAGE_WIDTH).toFloat(), state.zoom(), 1e-5f)
        assertEquals(GUTTER.toFloat(), checkNotNull(state.surface.screenRect(0, Rect(0f, 0f, 1f, 1f))).left, 1e-2f)
    }

    @Test
    fun aPageThatCannotBeReadEndsTheOpening() {
        page.failing = true
        val state = opened()

        assertNotNull(state.openError)
        assertFalse(state.isReady)
        assertEquals(1, page.closed)
        assertFalse(state.grab(100f, 100f))
    }

    @Test
    fun closingClosesThePageOnce() {
        val state = opened()
        state.close()
        state.close()

        assertEquals(1, page.closed)
        assertFalse(state.isReady)
    }

    @Test
    fun theShadeCoversThePageAroundTheFrame() {
        val state = opened()
        val page = checkNotNull(state.pageOnScreen())
        val shown = state.shown()

        assertEquals(GUTTER.toFloat(), page.left, 1e-2f)
        assertEquals(PAGE_WIDTH * state.zoom(), page.width, 1e-2f)
        assertEquals(PAGE_HEIGHT * state.zoom(), page.height, 1e-2f)
        assertEquals(page.left + page.width * 0.2f, shown.left, 1e-2f)
        assertEquals(page.top + page.height * 0.75f, shown.bottom, 1e-2f)
    }

    @Test
    fun onlyTheSideMarksAreKeptFromTheBackGestureInsideTheSurface() {
        assertEquals(emptyList<Rect>(), state().sideMarkZones(MARK_SLOP))

        val state = opened(spec(0f, 0f, 1f, 0.5f))
        val shown = state.shown()
        val zones = state.sideMarkZones(MARK_SLOP)

        assertEquals(6, zones.size)
        val left = Rect(0f, shown.top - MARK_SLOP, shown.left + MARK_SLOP, shown.top + MARK_SLOP)
        assertFrame(left, zones[0], 1e-2f)
        assertFrame(Rect(0f, shown.center.y - MARK_SLOP, shown.left + MARK_SLOP, shown.center.y + MARK_SLOP), zones[1], 1e-2f)
        assertFrame(Rect(shown.right - MARK_SLOP, shown.bottom - MARK_SLOP, VIEW_WIDTH.toFloat(), shown.bottom + MARK_SLOP), zones[5], 1e-2f)
        assertTrue(zones.all { it.height <= 2 * MARK_SLOP })
    }

    @Test
    fun theMarksShrinkWithASmallFrame() {
        assertEquals(MARK_LENGTH, PageCropperState.markLength(Rect(0f, 0f, 500f, 400f), MARK_LENGTH), 0f)
        assertEquals(10f, PageCropperState.markLength(Rect(0f, 0f, 30f, 400f), MARK_LENGTH), 1e-6f)
    }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val MIN_SIZE = 0.02f
        const val VIEW_WIDTH = 1_080
        const val VIEW_HEIGHT = 2_000
        const val WIDE_WIDTH = 2_400
        const val WIDE_HEIGHT = 1_000
        const val SPACING = 8.0
        const val GUTTER = 72.0
        const val MARK_LENGTH = 66f
        const val MARK_SLOP = 72f
        const val LINE_SLOP = 48f
    }
}
