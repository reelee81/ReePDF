package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
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

class ViewerStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val document = FakeDocument(PAGE_COUNT)
    private val state = ViewerState("test.pdf", false, scope)

    @After
    fun tearDown() {
        state.close()
        scope.cancel()
    }

    @Test
    fun aPageRenderedAfterAFastScrollRedrawsItsReaders() {
        state.open(ViewerWait.Reason.OPENING, 0) { document }
        state.onSize(VIEW_WIDTH, VIEW_HEIGHT, SPACING)
        document.finishAll()
        val pages = checkNotNull(state.layout)
        val zoom = checkNotNull(state.viewport).zoom
        state.scrollToY(pages.top(FAR_PAGE) * zoom)
        val page = state.currentPage
        assertTrue(page >= FAR_PAGE)
        val reads = HashSet<Any>()
        val before = Snapshot.observe(readObserver = { reads += it }) { state.pageImage(page) }
        assertNull(before)
        Snapshot.sendApplyNotifications()
        var redrawn = false
        val observer = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) redrawn = true }
        try {
            document.finishAll()
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }
        assertTrue(redrawn)
        assertNotNull(state.pageImage(page))
    }

    private fun opened(): ViewerViewport {
        state.open(ViewerWait.Reason.OPENING, 0) { document }
        state.onSize(VIEW_WIDTH, VIEW_HEIGHT, SPACING)
        document.finishAll()
        return checkNotNull(state.viewport)
    }

    private fun zoomedIn(): ViewerViewport {
        opened()
        zoomedInFrom()
        return checkNotNull(state.viewport)
    }

    @Test
    fun aLightPageDrawsItsTilesOneByOneAsBefore() {
        zoomedIn()

        assertTrue(document.runningBatches.isEmpty())
        assertTrue(document.pendingTiles > 1)
        document.finishAll()

        assertTrue(state.tilesOf(state.currentPage).isNotEmpty())
    }

    @Test
    fun theMissingTilesOfAHeavyPageAreDrawnInScreenSizedBatches() {
        document.pageImageMillis = HEAVY_MILLIS
        val view = zoomedIn()
        val page = state.currentPage
        val drawn = HashSet<ViewerTile>()

        while (document.runningBatches.isNotEmpty()) {
            val batch = document.runningBatches.single()
            assertTrue(batch.all { it.page == batch.first().page && it.zoom == view.zoom })
            val width = batch.maxOf { it.left + it.width } - batch.minOf { it.left }
            val height = batch.maxOf { it.top + it.height } - batch.minOf { it.top }
            assertTrue(width.toLong() * height <= VIEW_WIDTH.toLong() * VIEW_HEIGHT)
            assertTrue(batch.none { it in drawn })
            drawn += batch
            document.finishAll()
        }

        assertEquals(0, document.pendingTiles)
        assertTrue(drawn.size > 1)
        assertTrue(state.tilesOf(page).isNotEmpty())
        assertEquals(drawn.filter { it.page == page }.toSet(), state.tilesOf(page).map { it.first }.toSet())
    }

    @Test
    fun aZoomedPageKeepsItsPageImageInsteadOfDrawingItAgain() {
        opened()
        val page = state.currentPage
        val before = checkNotNull(state.pageImage(page))

        zoomedInFrom()

        assertEquals(0, document.pendingPageImages)
        assertTrue(state.pageImage(page) === before)
    }

    @Test
    fun aCachedPageImageIsDrawnAgainWhenTheZoomReturnsToIt() {
        opened()
        state.zoomFrom(checkNotNull(state.viewport), SMALL_ZOOM, VIEW_WIDTH / 2.0, VIEW_HEIGHT / 2.0)
        state.endZoom()
        document.finishAll()
        val page = checkNotNull(state.pagePoint(VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f)).first
        val small = checkNotNull(state.pageImage(page))

        state.zoomFrom(checkNotNull(state.viewport), ZOOMED, VIEW_WIDTH / 2.0, VIEW_HEIGHT / 2.0)
        state.endZoom()
        document.finishAll()
        assertTrue(checkNotNull(state.pageImage(page)) !== small)
        state.zoomFrom(checkNotNull(state.viewport), SMALL_ZOOM, VIEW_WIDTH / 2.0, VIEW_HEIGHT / 2.0)
        state.endZoom()

        assertEquals(0, document.pendingPageImages)
        assertTrue(state.pageImage(page) === small)
    }

    @Test
    fun aFailedTileKeepsThePageVisibleAndIsNotDrawnAgainAtThisZoom() {
        opened()
        document.failTiles = true
        zoomedInFrom()
        val page = state.currentPage
        assertTrue(document.pendingTiles > 1)

        document.finishAll()
        state.onShown()

        assertTrue(state.failedPages.isEmpty())
        assertNotNull(state.pageImage(page))
        assertEquals(0, document.pendingTiles)
        state.zoomFrom(checkNotNull(state.viewport), ZOOMED * 2, VIEW_WIDTH / 2.0, VIEW_HEIGHT / 2.0)
        state.endZoom()
        assertTrue(document.pendingTiles > 1)
    }

    @Test
    fun aFailedBatchKeepsThePageVisibleAndIsNotDrawnAgainAtThisZoom() {
        document.pageImageMillis = HEAVY_MILLIS
        opened()
        document.failTiles = true
        zoomedInFrom()
        val page = state.currentPage
        assertEquals(1, document.runningBatches.size)

        while (document.runningBatches.isNotEmpty()) document.finishAll()
        state.onShown()

        assertTrue(state.failedPages.isEmpty())
        assertNotNull(state.pageImage(page))
        assertTrue(document.runningBatches.isEmpty())
        assertEquals(0, document.pendingTiles)
    }

    @Test
    fun aSecondBatchWaitsForTheRunningOne() {
        document.pageImageMillis = HEAVY_MILLIS
        zoomedIn()
        val first = document.runningBatches.single()

        state.scrollBy(0.0, 600.0)
        assertEquals(listOf(first), document.runningBatches)
        document.finishAll()

        val next = document.runningBatches.single()
        assertTrue(next.isNotEmpty())
        assertTrue(next.none { it in first })
    }

    @Test
    fun aBatchIsCancelledOnlyWhenNoneOfItsTilesIsWanted() {
        document.pageImageMillis = HEAVY_MILLIS
        val view = zoomedIn()
        val first = document.runningBatches.single()

        state.scrollBy(0.0, 300.0)
        assertEquals(listOf(first), document.runningBatches)
        state.scrollToY(checkNotNull(state.layout).top(FAR_PAGE) * view.zoom)
        assertTrue(document.runningBatches.isEmpty())
        document.finishPageImages()

        val now = document.runningBatches.single()
        assertTrue(now.none { it in first })
        assertTrue(now.all { it.page >= FAR_PAGE - 1 })
        assertEquals(0, document.pendingTiles)
    }

    private fun zoomedInFrom() {
        val start = checkNotNull(state.viewport)
        state.zoomBy(ZOOMED / start.zoom, VIEW_WIDTH / 2.0, VIEW_HEIGHT / 2.0)
        state.endZoom()
    }

    @Test
    fun aHitAlreadyInViewStaysWhereItIs() {
        val start = opened()

        state.reveal(0, Rect(100f, 100f, 200f, 120f))

        assertEquals(start.scrollY, checkNotNull(state.viewport).scrollY, 0.0)
    }

    @Test
    fun aHiddenHitIsScrolledToTheUpperThirdOfTheUncoveredArea() {
        val start = opened()
        val pages = checkNotNull(state.layout)

        state.reveal(5, Rect(100f, 100f, 200f, 120f))
        assertEquals((pages.top(5) + 100) * start.zoom - VIEW_HEIGHT / 3.0, checkNotNull(state.viewport).scrollY, 1e-2)

        state.setUncoveredHeight(900)
        state.reveal(20, Rect(100f, 100f, 200f, 120f))
        assertEquals((pages.top(20) + 100) * start.zoom - 300.0, checkNotNull(state.viewport).scrollY, 1e-2)
        assertEquals(start.scrollX, checkNotNull(state.viewport).scrollX, 0.0)
    }

    @Test
    fun aScreenPointIsFoundOnItsPageInPageCoordinatesAndBack() {
        val zoom = opened().zoom

        val (page, point) = checkNotNull(state.pagePoint(500f, 100f * zoom))
        val (gapPage, gapPoint) = checkNotNull(state.pagePoint(500f, 845f * zoom))
        val box = checkNotNull(state.screenRect(0, Rect(10f, 20f, 30f, 40f)))

        assertEquals(0, page)
        assertEquals(500f / zoom, point.x, 1e-3f)
        assertEquals(100f, point.y, 1e-3f)
        assertEquals(0, gapPage)
        assertEquals(PAGE_HEIGHT, gapPoint.y, 1e-3f)
        assertEquals(10f * zoom, box.left, 1e-3f)
        assertEquals(20f * zoom, box.top, 1e-3f)
        assertEquals(30f * zoom, box.right, 1e-3f)
        assertEquals(40f * zoom, box.bottom, 1e-3f)
    }

    @Test
    fun backClosesTheSelectionFirstThenTheSearchThenNothing() {
        opened()
        state.startSearch()
        state.selection.selectAll(0)

        assertTrue(state.handleBack())
        assertFalse(state.selection.isActive)
        assertTrue(state.search.isActive)
        assertTrue(state.handleBack())
        assertFalse(state.search.isActive)
        assertFalse(state.handleBack())
    }

    @Test
    fun openingTheSearchClearsTheSelection() {
        opened()
        state.selection.selectAll(0)

        state.startSearch()

        assertFalse(state.selection.isActive)
        assertTrue(state.search.isActive)
    }

    @Test
    fun pageTextsAreReadOnlyWhileAScreenReaderListens() {
        opened()
        assertNull(state.pageText(0))

        state.readsPageTexts = true
        assertEquals("text 0", state.pageText(0))
        state.readsPageTexts = false

        assertNull(state.pageText(0))
    }

    @Test
    fun theSelectionBoundsAndHandlesFollowTheSelectedTextOnScreen() {
        val zoom = opened().zoom
        assertNull(state.selectionBounds())
        document.highlights = { page -> if (page == 0) floatArrayOf(10f, 20f, 50f, 30f, 5f, 40f, 70f, 52f) else FloatArray(0) }

        state.selection.selectAll(0)

        val bounds = checkNotNull(state.selectionBounds())
        val start = checkNotNull(state.handlePoint(checkNotNull(state.selection.startHandle())))
        val end = checkNotNull(state.handlePoint(checkNotNull(state.selection.endHandle())))
        assertEquals(listOf(5f, 20f, 70f, 52f).map { it * zoom }, listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
        assertEquals(listOf(10f, 30f, 70f, 52f).map { it * zoom }, listOf(start.x, start.y, end.x, end.y))
    }

    @Test
    fun aHandleIsGrabbedNearItsDropButNotFarAway() {
        val zoom = opened().zoom
        document.highlights = { page -> if (page == 0) floatArrayOf(100f, 20f, 300f, 30f) else FloatArray(0) }
        state.selection.selectAll(0)

        val start = ViewerSelectionHandles.grab(state, Offset(100f * zoom - 12f, 30f * zoom + 12f), 12f, 12f)
        val end = ViewerSelectionHandles.grab(state, Offset(300f * zoom + 30f, 30f * zoom + 12f), 12f, 12f)
        val none = ViewerSelectionHandles.grab(state, Offset(200f * zoom, 30f * zoom + 60f), 12f, 12f)

        assertEquals(ViewerSelection.Handle.START, checkNotNull(start).handle)
        assertEquals(10f * zoom, start.lineHeight, 1e-3f)
        assertEquals(ViewerSelection.Handle.END, checkNotNull(end).handle)
        assertNull(none)
    }

    private class FakeDocument(override val pageCount: Int) : ViewerDocument {
        private class Request(val result: CompletableDeferred<ViewerRender>, val width: Int, val height: Int, val tile: Boolean)

        private class Batch(val result: CompletableDeferred<ViewerTilesRender>, val tiles: List<ViewerTile>)

        private val pending = ArrayList<Request>()
        private val pendingBatches = ArrayList<Batch>()
        var highlights: (Int) -> FloatArray = { FloatArray(0) }
        var pageImageMillis = 10L
        var failTiles = false

        val pendingPageImages: Int
            get() = pending.count { !it.tile }

        val pendingTiles: Int
            get() = pending.count { it.tile }

        val runningBatches: List<List<ViewerTile>>
            get() = pendingBatches.map { it.tiles }

        override suspend fun pageSizes() =
            ViewerPageSizes(FloatArray(pageCount) { PAGE_WIDTH }, FloatArray(pageCount) { PAGE_HEIGHT })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender {
            val request = Request(CompletableDeferred(), width, height, width <= TILE && height <= TILE)
            pending += request
            try {
                return request.result.await()
            } finally {
                pending -= request
            }
        }

        override suspend fun renderTiles(tiles: List<ViewerTile>): ViewerTilesRender {
            val batch = Batch(CompletableDeferred(), tiles)
            pendingBatches += batch
            try {
                return batch.result.await()
            } finally {
                pendingBatches -= batch
            }
        }

        override suspend fun search(page: Int, query: String) = ViewerTextHits.NONE

        override suspend fun count(page: Int, query: String) = 0

        override fun releaseCount() = Unit

        override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? = null

        override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint? = null

        override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = highlights(page)

        override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ""

        override suspend fun text(page: Int) = "text $page"

        fun finishPageImages() {
            pending.filter { !it.tile }.forEach(::complete)
        }

        private fun complete(request: Request) {
            if (request.tile && failTiles) {
                request.result.complete(ViewerRender.Failed)
                return
            }
            val bytes = request.width.toLong() * request.height * 4
            val millis = if (request.tile) 1L else pageImageMillis
            request.result.complete(ViewerRender.Done(ViewerImage(FakeBitmap(request.width, request.height), bytes), millis))
        }

        fun finishAll() {
            pending.toList().forEach(::complete)
            pendingBatches.toList().forEach { batch ->
                if (failTiles) {
                    batch.result.complete(ViewerTilesRender.Failed)
                } else {
                    val images = batch.tiles.map { ViewerImage(FakeBitmap(it.width, it.height), it.width.toLong() * it.height * 4) }
                    batch.result.complete(ViewerTilesRender.Done(images))
                }
            }
        }

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() = Unit
    }

    private class FakeBitmap(override val width: Int, override val height: Int) : ImageBitmap {
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = true
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int
        ) = Unit

        override fun prepareToDraw() = Unit
    }

    private companion object {
        const val PAGE_COUNT = 1_000
        const val FAR_PAGE = 800
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val VIEW_WIDTH = 1_000
        const val VIEW_HEIGHT = 2_000
        const val SPACING = 8.0
        const val ZOOMED = 4f
        const val SMALL_ZOOM = 0.8f
        const val HEAVY_MILLIS = 1_500L
        const val TILE = 512
    }
}
