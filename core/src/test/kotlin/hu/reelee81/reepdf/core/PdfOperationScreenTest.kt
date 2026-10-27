package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.PdfOperationScreen.FreshStart
import hu.reelee81.reepdf.core.PdfOperationScreen.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOperationScreenTest {
    private val idle: PdfOperationState = PdfOperationState.Idle
    private val request = PdfOperationRequest(4L, PdfOperationKind.CROP, "crop")
    private val running = PdfOperationState.Running(request, 30)
    private val cancelling = PdfOperationState.Cancelling(request)
    private val done = PdfOperationState.Done(request)
    private val error = IllegalStateException("broken")
    private val failed = PdfOperationState.Failed(request, error)
    private val cancelled = PdfOperationState.Cancelled(request)

    @Test
    fun aFreshStartClearsTheCacheAndDropsAnUnhandledEnding() {
        assertEquals(FreshStart.ClearCache(null), PdfOperationScreen.onFreshStart(idle))
        assertEquals(FreshStart.ClearCache(4L), PdfOperationScreen.onFreshStart(done))
        assertEquals(FreshStart.ClearCache(4L), PdfOperationScreen.onFreshStart(failed))
        assertEquals(FreshStart.ClearCache(4L), PdfOperationScreen.onFreshStart(cancelled))
    }

    @Test
    fun aFreshStartDuringAnOperationDiscardsItInsteadOfDeletingItsFiles() {
        assertEquals(FreshStart.Discard(4L), PdfOperationScreen.onFreshStart(running))
        assertEquals(FreshStart.Discard(4L), PdfOperationScreen.onFreshStart(cancelling))
    }

    @Test
    fun everythingButIdleKeepsTheScreenWaiting() {
        assertFalse(PdfOperationScreen.isPending(idle))
        listOf(running, cancelling, done, failed, cancelled).forEach { assertTrue(PdfOperationScreen.isPending(it)) }
    }

    @Test
    fun swipingTheTaskAwayCancelsOnlyABusyOperation() {
        assertEquals(4L, PdfOperationScreen.onFinishing(running))
        assertEquals(4L, PdfOperationScreen.onFinishing(cancelling))
        listOf(idle, done, failed, cancelled).forEach { assertNull(PdfOperationScreen.onFinishing(it)) }
    }

    @Test
    fun theScreenThatStartedTheOperationAdoptsItAndThenIgnoresRepeats() {
        assertEquals(Step.Adopt(4L), PdfOperationScreen.step(running, shownId = null, overlayOwned = true, discarding = false))
        assertEquals(Step.Ignore, PdfOperationScreen.step(running, shownId = 4L, overlayOwned = true, discarding = false))
        assertEquals(Step.Ignore, PdfOperationScreen.step(cancelling, shownId = 4L, overlayOwned = true, discarding = false))
    }

    @Test
    fun aRecreatedScreenShowsTheMatchingDialog() {
        assertEquals(Step.Attach(4L, "crop", updateUi = false), PdfOperationScreen.step(running, null, overlayOwned = false, discarding = false))
        assertEquals(Step.Attach(4L, "crop", updateUi = true), PdfOperationScreen.step(cancelling, null, overlayOwned = false, discarding = false))
        assertEquals(Step.Attach(4L, "crop", updateUi = true), PdfOperationScreen.step(running, null, overlayOwned = false, discarding = true))
    }

    @Test
    fun eachEndingLeadsToItsOwnHandling() {
        val result = object : PdfOperationResult {}

        assertEquals(Step.Finish(4L, result), PdfOperationScreen.step(PdfOperationState.Done(request, result), 4L, overlayOwned = true, discarding = false))
        assertEquals(Step.Fail(4L, error), PdfOperationScreen.step(failed, 4L, overlayOwned = true, discarding = false))
        assertEquals(Step.Restore(4L), PdfOperationScreen.step(cancelled, 4L, overlayOwned = true, discarding = false))
        assertEquals(Step.Restore(4L), PdfOperationScreen.step(cancelled, null, overlayOwned = false, discarding = false))
    }

    @Test
    fun aDiscardedOperationEndsAsDiscardedWhateverItsEnding() {
        listOf(done, failed, cancelled).forEach {
            assertEquals(Step.Discarded(4L), PdfOperationScreen.step(it, 4L, overlayOwned = true, discarding = true))
        }
    }

    @Test
    fun anEndingAlreadyHandedOverIsNotHandledAgain() {
        listOf(done, failed, cancelled).forEach {
            assertEquals(Step.Ignore, PdfOperationScreen.step(it, null, overlayOwned = false, discarding = false, handedOverId = 4L))
        }
        assertEquals(Step.Restore(4L), PdfOperationScreen.step(cancelled, null, overlayOwned = false, discarding = false, handedOverId = 3L))
        assertEquals(Step.Adopt(4L), PdfOperationScreen.step(running, null, overlayOwned = true, discarding = false, handedOverId = 4L))
    }

    @Test
    fun anIdleStateNeedsNothing() {
        assertEquals(Step.Ignore, PdfOperationScreen.step(idle, 4L, overlayOwned = true, discarding = true))
    }
}
