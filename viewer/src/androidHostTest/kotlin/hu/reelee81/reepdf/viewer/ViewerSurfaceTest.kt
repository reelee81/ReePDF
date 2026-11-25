package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSurfaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun atTheEnd(kept: Pair<Int, Rect>?): ViewerSurface = ViewerSurface(scope, keep = { kept }).apply {
        attach(Source, ViewerPageSizes(FloatArray(PAGES) { 595f }, FloatArray(PAGES) { 842f }))
        onSize(1_000, 2_000, SPACING)
        scrollToY(1e12)
        setUncovered(1_200)
    }

    @Test
    fun aHitInViewBeforeTurningToLandscapeIsStillInViewAfter() {
        val surface = atTheEnd(LAST to LOW)
        val before = checkNotNull(surface.screenRect(LAST, LOW))

        surface.onSize(2_000, 1_000, SPACING)
        val after = checkNotNull(surface.screenRect(LAST, LOW))

        assertTrue(before.top >= 0f && before.bottom <= 1_200f)
        assertTrue(after.top >= 0f && after.bottom <= 1_000f)
    }

    @Test
    fun aHitOutOfViewDoesNotPullTheViewWhenTurning() {
        val far = atTheEnd(0 to LOW)
        val plain = atTheEnd(null)

        far.onSize(2_000, 1_000, SPACING)
        plain.onSize(2_000, 1_000, SPACING)

        assertEquals(checkNotNull(plain.viewport).scrollY, checkNotNull(far.viewport).scrollY, 0.0)
        assertEquals(checkNotNull(plain.viewport).zoom, checkNotNull(far.viewport).zoom, 0f)
    }

    private object Source : ViewerPageSource {
        override val pageCount = PAGES

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(PAGES) { 595f }, FloatArray(PAGES) { 842f })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val PAGES = 20
        const val LAST = PAGES - 1
        const val SPACING = 8.0
        val LOW = Rect(100f, 300f, 200f, 330f)
    }
}
