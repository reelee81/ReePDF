package hu.reelee81.reepdf.viewer

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

class SignaturePlacerStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val page = FakePage()
    private val states = mutableListOf<SignaturePlacerState>()

    @After
    fun tearDown() {
        states.forEach(SignaturePlacerState::close)
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

    private fun spec(startX: Float = START_X, startY: Float = START_Y, maxX: Float = MAX_X, maxY: Float = MAX_Y) =
        SignaturePlacerSpec(210f, 297f, SIGNATURE_WIDTH, SIGNATURE_HEIGHT, maxX, maxY, startX, startY)

    private fun state(spec: SignaturePlacerSpec = spec()) =
        SignaturePlacerState(spec, scope).also(states::add)

    private fun opened(spec: SignaturePlacerSpec = spec(), width: Int = VIEW_WIDTH, height: Int = VIEW_HEIGHT): SignaturePlacerState {
        val state = state(spec)
        state.open { page }
        state.surface.onSize(width, height, SPACING)
        return state
    }

    private fun SignaturePlacerState.zoom() = checkNotNull(surface.viewport).zoom

    private fun SignaturePlacerState.pixelsPerMmX() = zoom() * PAGE_WIDTH / 210f

    private fun SignaturePlacerState.pixelsPerMmY() = zoom() * PAGE_HEIGHT / 297f

    @Test
    fun theStartPositionIsKeptWithinTheLimits() {
        val outside = state(spec(startX = -5f, startY = 400f))
        assertEquals(0f, outside.xMm, 0f)
        assertEquals(MAX_Y, outside.yMm, 0f)
        val noRoom = state(spec(maxX = -3f, maxY = -1f))
        assertEquals(0f, noRoom.xMm, 0f)
        assertEquals(0f, noRoom.yMm, 0f)
        assertFalse(outside.isModified)
    }

    @Test
    fun openingShowsTheOnePageAtFitWidth() {
        val state = state()
        assertFalse(state.isReady)
        state.open { page }
        state.surface.onSize(VIEW_WIDTH, VIEW_HEIGHT, SPACING)

        assertTrue(state.isReady)
        assertEquals(1, checkNotNull(state.surface.layout).pageCount)
        assertEquals(VIEW_WIDTH / PAGE_WIDTH, state.zoom(), 1e-6f)
        assertNull(state.openError)
    }

    @Test
    fun theSignatureIsPlacedOnThePageInMillimetres() {
        val box = checkNotNull(opened().signatureBox())
        assertEquals(START_X * PAGE_WIDTH / 210f, box.left, 1e-3f)
        assertEquals(START_Y * PAGE_HEIGHT / 297f, box.top, 1e-3f)
        assertEquals((START_X + SIGNATURE_WIDTH) * PAGE_WIDTH / 210f, box.right, 1e-3f)
        assertEquals((START_Y + SIGNATURE_HEIGHT) * PAGE_HEIGHT / 297f, box.bottom, 1e-3f)
    }

    @Test
    fun theSignatureIsScrolledIntoViewWhenThePageIsTallerThanTheScreen() {
        val state = opened(width = WIDE_WIDTH, height = WIDE_HEIGHT)
        val shown = checkNotNull(state.signatureOnScreen())
        assertTrue(shown.top >= 0f)
        assertTrue(shown.bottom <= WIDE_HEIGHT)
    }

    @Test
    fun aDragKeepsThePointWhereTheSignatureWasGrabbed() {
        val state = opened()
        val shown = checkNotNull(state.signatureOnScreen())
        val x = shown.left + 10f
        val y = shown.top + 5f

        assertTrue(state.grab(x, y, 0f))
        state.dragTo(x - 100f, y - 50f)
        state.endDrag()

        assertEquals(START_X - 100f / state.pixelsPerMmX(), state.xMm, 1e-3f)
        assertEquals(START_Y - 50f / state.pixelsPerMmY(), state.yMm, 1e-3f)
        assertTrue(state.isModified)
    }

    @Test
    fun aDragStopsAtThePageEdgesAndComesBackWithTheFinger() {
        val state = opened()
        val shown = checkNotNull(state.signatureOnScreen())
        val x = shown.center.x
        val y = shown.center.y

        assertTrue(state.grab(x, y, 0f))
        state.dragTo(x + 5_000f, y + 5_000f)
        assertEquals(MAX_X, state.xMm, 0f)
        assertEquals(MAX_Y, state.yMm, 0f)
        state.dragTo(x - 5_000f, y - 5_000f)
        assertEquals(0f, state.xMm, 0f)
        assertEquals(0f, state.yMm, 0f)
        state.dragTo(x, y)
        state.endDrag()
        assertEquals(START_X, state.xMm, 1e-3f)
        assertEquals(START_Y, state.yMm, 1e-3f)
    }

    @Test
    fun onlyATouchOnTheSignatureOrItsMarginGrabsIt() {
        val state = opened()
        val shown = checkNotNull(state.signatureOnScreen())

        assertFalse(state.grab(shown.left - 20f, shown.top, 10f))
        assertTrue(state.grab(shown.left - 8f, shown.top - 8f, 10f))
        state.endDrag()
        state.dragTo(0f, 0f)
        assertEquals(START_X, state.xMm, 0f)
        assertFalse(state.isModified)
    }

    @Test
    fun backLeavesAnUnchangedPlacerAndAsksAboutAChangedOne() {
        val state = opened()
        assertFalse(state.handleBack())
        assertFalse(state.asking)

        val shown = checkNotNull(state.signatureOnScreen())
        state.grab(shown.center.x, shown.center.y, 0f)
        state.dragTo(shown.center.x + 30f, shown.center.y)
        state.endDrag()

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
    fun aRotationKeepsThePositionAndTheSignatureInView() {
        val state = opened()
        val shown = checkNotNull(state.signatureOnScreen())
        state.grab(shown.center.x, shown.center.y, 0f)
        state.dragTo(shown.center.x - 40f, shown.center.y + 60f)
        state.endDrag()
        val x = state.xMm
        val y = state.yMm

        state.surface.onSize(WIDE_WIDTH, WIDE_HEIGHT, SPACING)

        assertEquals(x, state.xMm, 0f)
        assertEquals(y, state.yMm, 0f)
        assertEquals(WIDE_WIDTH / PAGE_WIDTH, state.zoom(), 1e-5f)
        val rotated = checkNotNull(state.signatureOnScreen())
        assertTrue(rotated.top >= 0f)
        assertTrue(rotated.bottom <= WIDE_HEIGHT)
    }

    @Test
    fun aPageThatCannotBeReadEndsTheOpening() {
        page.failing = true
        val state = opened()

        assertNotNull(state.openError)
        assertFalse(state.isReady)
        assertEquals(1, page.closed)
    }

    @Test
    fun closingClosesThePageOnce() {
        val state = opened()
        state.close()
        state.close()

        assertEquals(1, page.closed)
        assertFalse(state.isReady)
    }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val SIGNATURE_WIDTH = 40f
        const val SIGNATURE_HEIGHT = 20f
        const val MAX_X = 170f
        const val MAX_Y = 277f
        const val START_X = 155f
        const val START_Y = 245f
        const val VIEW_WIDTH = 1_080
        const val VIEW_HEIGHT = 2_000
        const val WIDE_WIDTH = 2_400
        const val WIDE_HEIGHT = 1_000
        const val SPACING = 8.0
    }
}
