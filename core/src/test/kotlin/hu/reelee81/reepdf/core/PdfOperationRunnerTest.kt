package hu.reelee81.reepdf.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOperationRunnerTest {
    private class FakeKeepAlive : PdfOperationKeepAlive {
        val events = mutableListOf<String>()

        override fun begin(request: PdfOperationRequest) {
            events += "begin ${request.id}"
        }

        override fun end() {
            events += "end"
        }
    }

    private fun TestScope.runner(keepAlive: FakeKeepAlive) = PdfOperationRunner(this, keepAlive)

    private suspend fun PdfOperationRunner.ending(): PdfOperationState = state.first { !it.isBusy }

    @Test
    fun aSuccessfulOperationEndsDoneAndReleasesTheKeepAliveOnce() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        var ran = false
        var cleaned = false

        val id = runner.start(PdfOperationKind.CROP, "crop", onCancelled = { cleaned = true }) { ran = true; null }

        assertNotNull(id)
        assertEquals(PdfOperationState.Running(PdfOperationRequest(id!!, PdfOperationKind.CROP, "crop")), runner.state.value)
        assertEquals(listOf("begin $id"), keepAlive.events)

        val ending = runner.ending()

        assertTrue(ran)
        assertFalse(cleaned)
        assertTrue(ending is PdfOperationState.Done)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun aFailingOperationKeepsTheErrorAndSkipsTheCancelCleanup() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        val error = IllegalStateException("broken")
        var cleaned = false

        runner.start(PdfOperationKind.CROP, "crop", onCancelled = { cleaned = true }) { throw error }

        val failed = runner.ending() as PdfOperationState.Failed
        assertSame(error, failed.error)
        assertFalse(cleaned)
        assertEquals("end", keepAlive.events.last())
        assertEquals(1, keepAlive.events.count { it == "end" })
    }

    @Test
    fun cancellingStopsTheWorkRunsTheCleanupAndEndsCancelled() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        val started = CompletableDeferred<Unit>()
        var cleanups = 0

        val id = runner.start(PdfOperationKind.CROP, "crop", onCancelled = { cleanups++ }) {
            started.complete(Unit)
            awaitCancellation()
        }!!
        started.await()

        runner.cancel(id)
        assertTrue(runner.state.value is PdfOperationState.Cancelling)
        assertEquals(listOf("begin $id"), keepAlive.events)

        val ending = runner.ending()

        assertEquals(1, cleanups)
        assertTrue(ending is PdfOperationState.Cancelled)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun aCancelBeforeTheWorkGetsToRunStillCleansUpAndReleases() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        var ran = false
        var cleanups = 0

        val id = runner.start(PdfOperationKind.CROP, "crop", onCancelled = { cleanups++ }) { ran = true; null }!!
        runner.cancel(id)
        val ending = runner.ending()

        assertFalse(ran)
        assertEquals(1, cleanups)
        assertTrue(ending is PdfOperationState.Cancelled)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun aCancellationRaisedByTheWorkItselfCountsAsCancel() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        var cleanups = 0

        runner.start(PdfOperationKind.CROP, "crop", onCancelled = { cleanups++ }) { throw CancellationException() }
        val ending = runner.ending()

        assertEquals(1, cleanups)
        assertTrue(ending is PdfOperationState.Cancelled)
        assertEquals(1, keepAlive.events.count { it == "end" })
    }

    @Test
    fun aSecondOperationIsRefusedWhileOneIsBusy() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        var secondRan = false

        val id = runner.start(PdfOperationKind.CROP, "first") { awaitCancellation() }!!

        assertNull(runner.start(PdfOperationKind.CROP, "second") { secondRan = true; null })
        runner.cancel(id)
        assertNull(runner.start(PdfOperationKind.CROP, "third") { secondRan = true; null })

        runner.ending()

        assertFalse(secondRan)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun progressIsPublishedOnlyForTheRunningOperation() = runTest {
        val runner = runner(FakeKeepAlive())

        val id = runner.start(PdfOperationKind.CROP, "crop") { awaitCancellation() }!!
        runner.progress(id, 40)
        assertEquals(40, (runner.state.value as PdfOperationState.Running).percent)

        runner.progress(id + 1, 90)
        assertEquals(40, (runner.state.value as PdfOperationState.Running).percent)

        runner.cancel(id)
        runner.ending()
    }

    @Test
    fun theEndingStaysUntilAcknowledgedAndLateSignalsAreIgnored() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        var cleanups = 0

        val first = runner.start(PdfOperationKind.CROP, "first", onCancelled = { cleanups++ }) { null }!!
        val done = runner.ending()

        runner.cancel(first)
        runner.progress(first, 10)
        assertSame(done, runner.state.value)
        assertEquals(0, cleanups)

        runner.acknowledge(first)
        assertSame(PdfOperationState.Idle, runner.state.value)

        val second = runner.start(PdfOperationKind.CROP, "second") { null }!!
        assertNotEquals(first, second)
        runner.cancel(first)
        assertTrue(runner.ending() is PdfOperationState.Done)
        assertEquals(listOf("begin $first", "end", "begin $second", "end"), keepAlive.events)
    }

    @Test
    fun theResultOfTheWorkArrivesWithTheEnding() = runTest {
        val result = object : PdfOperationResult {}
        val runner = runner(FakeKeepAlive())

        runner.start(PdfOperationKind.CROP, "crop") { result }

        val done = runner.ending() as PdfOperationState.Done
        assertSame(result, done.result)
    }

    @Test
    fun theWorkReportsItsOwnProgress() = runTest {
        val runner = runner(FakeKeepAlive())
        val reported = CompletableDeferred<Unit>()

        val id = runner.start(PdfOperationKind.CROP, "crop") { report ->
            report(30)
            reported.complete(Unit)
            awaitCancellation()
        }!!
        reported.await()

        assertEquals(30, (runner.state.value as PdfOperationState.Running).percent)
        runner.cancel(id)
        runner.ending()
    }

    @Test
    fun theKeepAliveIsReleasedBeforeTheEndingIsPublished() = runTest {
        lateinit var runner: PdfOperationRunner
        val busyAtEnd = mutableListOf<Boolean>()
        val keepAlive = object : PdfOperationKeepAlive {
            override fun begin(request: PdfOperationRequest) = Unit

            override fun end() {
                busyAtEnd += runner.state.value.isBusy
            }
        }
        runner = PdfOperationRunner(this, keepAlive)

        runner.start(PdfOperationKind.CROP, "done") { null }
        runner.ending()
        val cancelled = runner.start(PdfOperationKind.CROP, "cancelled") { awaitCancellation() }!!
        runner.cancel(cancelled)
        runner.ending()

        assertEquals(listOf(true, true), busyAtEnd)
    }

    @Test
    fun withAStartedScreenTheKeepAliveIsHeldUntilTheEndingIsAcknowledged() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        runner.screenStarted()

        val id = runner.start(PdfOperationKind.CROP, "crop") { null }!!
        assertTrue(runner.ending() is PdfOperationState.Done)
        assertTrue(runner.isHolding)
        assertEquals(listOf("begin $id"), keepAlive.events)

        runner.handOver(id)
        runner.screenStopped()
        runner.screenStarted()
        assertEquals(listOf("begin $id"), keepAlive.events)

        runner.acknowledge(id)
        assertSame(PdfOperationState.Idle, runner.state.value)
        assertFalse(runner.isHolding)
        runner.acknowledge(id)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun aScreenLeavingBeforeTakingOverReleasesButKeepsTheEnding() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        runner.screenStarted()

        val id = runner.start(PdfOperationKind.CROP, "crop") { null }!!
        val done = runner.ending()
        runner.screenStopped()

        assertSame(done, runner.state.value)
        assertFalse(runner.isHolding)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)

        runner.screenStarted()
        runner.handOver(id)
        runner.acknowledge(id)
        assertSame(PdfOperationState.Idle, runner.state.value)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun aScreenLeavingWhileTheWorkRunsLetsTheEndReleaseAtOnce() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        runner.screenStarted()

        val id = runner.start(PdfOperationKind.CROP, "crop") { started.complete(Unit); finish.await(); null }!!
        started.await()
        runner.screenStopped()
        assertEquals(listOf("begin $id"), keepAlive.events)

        finish.complete(Unit)
        assertTrue(runner.ending() is PdfOperationState.Done)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun anAbandonedHandOverReleasesAndTheNextScreenTakesOverAgain() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        runner.screenStarted()

        val id = runner.start(PdfOperationKind.CROP, "crop") { awaitCancellation() }!!
        runner.abandon(id)
        assertEquals(listOf("begin $id"), keepAlive.events)

        runner.cancel(id)
        val cancelled = runner.ending()
        runner.handOver(id)
        runner.screenStopped()
        assertEquals(listOf("begin $id"), keepAlive.events)

        runner.abandon(id)
        assertSame(cancelled, runner.state.value)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)

        runner.screenStarted()
        runner.handOver(id)
        runner.acknowledge(id)
        assertSame(PdfOperationState.Idle, runner.state.value)
        assertEquals(listOf("begin $id", "end"), keepAlive.events)
    }

    @Test
    fun acknowledgingAnOlderOperationLeavesTheRunningOneHeld() = runTest {
        val keepAlive = FakeKeepAlive()
        val runner = runner(keepAlive)
        runner.screenStarted()

        val first = runner.start(PdfOperationKind.CROP, "first") { null }!!
        runner.ending()
        val second = runner.start(PdfOperationKind.CROP, "second") { awaitCancellation() }!!

        runner.acknowledge(first)
        runner.abandon(first)
        assertTrue(runner.state.value.isBusy)
        assertTrue(runner.isHolding)
        assertEquals(listOf("begin $first", "begin $second"), keepAlive.events)

        runner.cancel(second)
        runner.ending()
        runner.acknowledge(second)
        assertEquals(listOf("begin $first", "begin $second", "end"), keepAlive.events)
    }
}
