package hu.reelee81.reepdf.pdf

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressTrackerTest {
    private var now = 1_000L
    private val tracker = ProgressTracker { now }

    @Test
    fun aResetStartsTheClockAndShowsNoPercentYet() {
        tracker.reset()

        assertEquals(1_000L, tracker.startedAt)
        assertNull(tracker.percent(now, IDLE_MS))
    }

    @Test
    fun stagesAddUpAndThePercentNeverGoesBack() {
        tracker.reset()
        val first = tracker.stage(100)
        first.advance(50)
        assertEquals(50, tracker.percent(now, IDLE_MS))

        tracker.stage(100)
        assertEquals(50, tracker.percent(now, IDLE_MS))
        first.advance(25)
        assertEquals(50, tracker.percent(now, IDLE_MS))
        first.advance(50)
        assertEquals(83, tracker.percent(now, IDLE_MS))
    }

    @Test
    fun aLongIdleStageTurnsThePercentIndeterminateUntilItAdvancesAgain() {
        tracker.reset()
        val stage = tracker.stage(10)
        stage.advance(5)
        stage.idle()
        assertEquals(50, tracker.percent(now + IDLE_MS - 1, IDLE_MS))
        assertNull(tracker.percent(now + IDLE_MS, IDLE_MS))

        now += IDLE_MS
        stage.idle()
        assertNull(tracker.percent(now + IDLE_MS, IDLE_MS))
        stage.advance(1)
        assertEquals(60, tracker.percent(now + IDLE_MS, IDLE_MS))
    }

    @Test
    fun cancellingStopsOldStagesAndRefusesNewOnesUntilAcceptingAgain() {
        tracker.reset()
        val old = tracker.stage(10)
        tracker.cancel()

        assertThrows(CancellationException::class.java) { old.advance(1) }
        val refused = tracker.stage(10)
        assertThrows(CancellationException::class.java) { refused.advance(1) }

        tracker.accepting = true
        tracker.reset()
        val fresh = tracker.stage(10)
        fresh.advance(1)
        assertEquals(10, tracker.percent(now, IDLE_MS))
    }

    @Test
    fun aStaleStageCannotMarkTheNewRunIdle() {
        tracker.reset()
        val old = tracker.stage(10)
        tracker.cancel()
        tracker.accepting = true
        tracker.reset()
        val fresh = tracker.stage(10)
        fresh.advance(1)

        old.idle()
        assertEquals(10, tracker.percent(now + IDLE_MS, IDLE_MS))
    }

    @Test
    fun theDoneUnitsCanBeRewoundAfterAFailedAttempt() {
        tracker.reset()
        val stage = tracker.stage(10)
        stage.advance(2)
        val before = tracker.doneUnits()
        stage.advance(5)

        tracker.resetDoneUnits(before)

        assertEquals(2L, tracker.doneUnits())
    }

    @Test
    fun anotherTrackerContinuesExactlyWhereTheFirstStopped() {
        tracker.reset()
        val first = tracker.stage(100)
        first.advance(80)
        assertEquals(80, tracker.percent(now, IDLE_MS))
        first.idle()

        val next = ProgressTracker { now }
        next.reset()
        next.continueFrom(tracker.snapshot())

        assertNull(next.percent(now + IDLE_MS, IDLE_MS))
        val reload = next.stage(1_000)
        reload.advance(10)
        assertEquals(80, next.percent(now + IDLE_MS, IDLE_MS))
        assertEquals(90L, next.doneUnits())
    }

    private companion object {
        const val IDLE_MS = 1_000L
    }
}
