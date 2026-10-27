package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOperationStateTest {
    private val first = PdfOperationRequest(1L, PdfOperationKind.CROP, "first")
    private val second = PdfOperationRequest(2L, PdfOperationKind.CROP, "second")

    @Test
    fun aStartedOperationRunsWithIndeterminateProgressAndReportsMeasuredPercent() {
        val running = PdfOperationState.Idle.start(first)

        assertEquals(PdfOperationState.Running(first, null), running)
        assertTrue(running!!.isBusy)
        assertEquals(PdfOperationState.Running(first, 42), running.progress(1L, 42))
        assertEquals(PdfOperationState.Running(first, null), running.progress(1L, 42).progress(1L, null))
    }

    @Test
    fun onlyOneOperationCanRunAtATime() {
        val running = PdfOperationState.Idle.start(first)!!

        assertNull(running.start(second))
        assertNull(running.cancel(1L).start(second))
    }

    @Test
    fun aNewOperationCanStartAfterAnyEnding() {
        val running = PdfOperationState.Idle.start(first)!!
        val endings = listOf(
            running.finish(1L),
            running.fail(1L, IllegalStateException()),
            running.cancel(1L).cancelled(1L),
            running.cancelled(1L)
        )

        endings.forEach { ending ->
            assertFalse(ending.isBusy)
            assertEquals(PdfOperationState.Running(second, null), ending.start(second))
        }
    }

    @Test
    fun cancellingWinsOverALateFinishOrFailure() {
        val cancelling = PdfOperationState.Idle.start(first)!!.cancel(1L)

        assertEquals(PdfOperationState.Cancelling(first), cancelling)
        assertSame(cancelling, cancelling.finish(1L))
        assertSame(cancelling, cancelling.fail(1L, IllegalStateException()))
        assertSame(cancelling, cancelling.progress(1L, 50))
        assertEquals(PdfOperationState.Cancelled(first), cancelling.cancelled(1L))
    }

    @Test
    fun theFailureKeepsItsCause() {
        val error = IllegalStateException("broken")
        val failed = PdfOperationState.Idle.start(first)!!.fail(1L, error)

        assertSame(error, (failed as PdfOperationState.Failed).error)
        assertEquals(first, failed.request)
    }

    @Test
    fun theDoneStateCarriesTheResultOfTheWork() {
        val result = object : PdfOperationResult {}
        val done = PdfOperationState.Idle.start(first)!!.finish(1L, result)

        assertSame(result, (done as PdfOperationState.Done).result)
    }

    @Test
    fun percentIsClampedToTheVisibleRange() {
        val running = PdfOperationState.Idle.start(first)!!

        assertEquals(PdfOperationState.Running(first, 0), running.progress(1L, -5))
        assertEquals(PdfOperationState.Running(first, 100), running.progress(1L, 130))
    }

    @Test
    fun signalsOfAnOtherOperationAreIgnored() {
        val running = PdfOperationState.Idle.start(second)!!

        assertSame(running, running.progress(1L, 10))
        assertSame(running, running.cancel(1L))
        assertSame(running, running.finish(1L))
        assertSame(running, running.fail(1L, IllegalStateException()))
        assertSame(running, running.cancelled(1L))
        assertSame(running, running.acknowledge(1L))

        val done = running.finish(2L)
        assertSame(done, done.acknowledge(1L))
    }

    @Test
    fun theEndingStaysVisibleUntilTheScreenAcknowledgesIt() {
        val done = PdfOperationState.Idle.start(first)!!.finish(1L)

        assertEquals(PdfOperationState.Done(first), done)
        assertSame(done, done.cancel(1L))
        assertSame(done, done.cancelled(1L))
        assertEquals(PdfOperationState.Idle, done.acknowledge(1L))
    }

    @Test
    fun aRunningOperationCannotBeAcknowledgedAway() {
        val running = PdfOperationState.Idle.start(first)!!

        assertSame(running, running.acknowledge(1L))
        assertSame(PdfOperationState.Idle, PdfOperationState.Idle.acknowledge(1L))
    }
}
