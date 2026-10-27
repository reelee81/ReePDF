package hu.reelee81.reepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageNavigationStateTest {
    @Test
    fun asynchronousScrollKeepsTargetWhileEarlierPagesAreVisible() {
        val state = PdfPageNavigationState()
        state.request(799)
        assertEquals(799, state.takeScrollRequest(1000))

        state.observeVisiblePage(0)
        assertEquals(799, state.currentPageIndex)
        assertEquals(799, state.pendingPageIndex)
        assertNull(state.takeScrollRequest(1000))
        assertFalse(state.confirmArrival(0))
    }

    @Test
    fun recreationRetriesSavedPendingTargetInsteadOfVisibleFirstPage() {
        val original = PdfPageNavigationState()
        original.request(899)
        original.takeScrollRequest(1000)
        original.observeVisiblePage(0)

        val restored = PdfPageNavigationState()
        restored.restore(original.visiblePageIndex, original.pendingPageIndex)
        assertEquals(899, restored.currentPageIndex)
        assertEquals(899, restored.takeScrollRequest(1000))
    }

    @Test
    fun repeatedResizeRetriesUntilArrival() {
        val state = PdfPageNavigationState()
        state.request(999)
        repeat(4) {
            assertEquals(999, state.takeScrollRequest(1000))
            assertNull(state.takeScrollRequest(1000))
            state.invalidateScroll()
            assertFalse(state.confirmArrival(999))
        }
        assertEquals(999, state.takeScrollRequest(1000))
        state.observeVisiblePage(999)
        assertTrue(state.confirmArrival(999))
        assertNull(state.pendingPageIndex)
        assertEquals(999, state.currentPageIndex)
    }

    @Test
    fun completedNavigationDoesNotResetLaterUserScroll() {
        val state = PdfPageNavigationState()
        state.request(50)
        state.takeScrollRequest(100)
        state.observeVisiblePage(50)
        state.confirmArrival(50)
        state.observeVisiblePage(61)
        state.invalidateScroll()

        assertEquals(61, state.currentPageIndex)
        assertNull(state.takeScrollRequest(100))
    }

    @Test
    fun restoredCompletedNavigationLeavesNativeScrollRestorationAlone() {
        val state = PdfPageNavigationState()
        state.restore(450, null)
        assertEquals(450, state.currentPageIndex)
        assertNull(state.takeScrollRequest(1000))
    }

    @Test
    fun staleArrivalCannotCompleteNewRequest() {
        val state = PdfPageNavigationState()
        state.request(100)
        state.takeScrollRequest(1000)
        state.request(700)
        assertFalse(state.confirmArrival(100))
        assertFalse(state.confirmArrival(700))
        assertEquals(700, state.takeScrollRequest(1000))
        assertFalse(state.confirmArrival(100))
        assertTrue(state.confirmArrival(700))
    }

    @Test
    fun shorterDocumentClampsTargetButStillWaitsForArrival() {
        val state = PdfPageNavigationState()
        state.request(999)
        assertEquals(9, state.takeScrollRequest(10))
        assertEquals(9, state.currentPageIndex)
        assertTrue(state.confirmArrival(9))
    }

    @Test
    fun emptyDocumentDoesNotIssueInvalidScroll() {
        val state = PdfPageNavigationState()
        state.request(10)
        assertNull(state.takeScrollRequest(0))
        assertFalse(state.confirmArrival(10))
        assertEquals(10, state.takeScrollRequest(100))
    }

    @Test
    fun invalidIndicesAreClampedToFirstPage() {
        val state = PdfPageNavigationState()
        state.request(-3)
        assertEquals(0, state.takeScrollRequest(100))
        state.restore(-10, -1)
        assertEquals(0, state.visiblePageIndex)
        assertEquals(0, state.currentPageIndex)
    }

    @Test
    fun lastLandscapePageOverhangingByASubPixelCountsAsReached() {
        assertTrue(PdfPageNavigationState.isPageReached(0f, 1304.0073f, 1080f, 2067.19f, 0f, 0f, 1080f, 2067f))
    }

    @Test
    fun pageCoveringTheViewportCentreCountsAsReached() {
        assertTrue(PdfPageNavigationState.isPageReached(0f, -300f, 1080f, 1200f, 0f, 0f, 1080f, 2067f))
    }

    @Test
    fun pageClearlyCutOffOrOutsideTheViewportIsNotReached() {
        assertFalse(PdfPageNavigationState.isPageReached(0f, 1304f, 1080f, 2069f, 0f, 0f, 1080f, 2067f))
        assertFalse(PdfPageNavigationState.isPageReached(0f, 2100f, 1080f, 2800f, 0f, 0f, 1080f, 2067f))
        assertFalse(PdfPageNavigationState.isPageReached(1080f, 0f, 2160f, 700f, 0f, 0f, 1080f, 2067f))
    }

    @Test
    fun documentErrorReleasesPendingRequest() {
        val state = PdfPageNavigationState()
        state.request(100)
        state.takeScrollRequest(1000)
        state.cancel()
        assertNull(state.pendingPageIndex)
        assertNull(state.takeScrollRequest(1000))
        assertFalse(state.confirmArrival(100))
    }
}
