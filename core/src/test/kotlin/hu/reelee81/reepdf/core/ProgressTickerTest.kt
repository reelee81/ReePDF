package hu.reelee81.reepdf.core

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProgressTickerTest {
    private val tick = 500.milliseconds

    @Test
    fun thePercentIsReportedEveryTickUntilTheBlockEnds() = runTest {
        val reports = mutableListOf<Int?>()
        var percent = 0

        val result = ProgressTicker.run(tick, { ++percent }, { reports += it }, { fail("not cancelled") }) {
            delay(tick * 3 + tick / 5)
            "built"
        }
        delay(tick * 4)

        assertEquals("built", result)
        assertEquals(listOf<Int?>(1, 2, 3), reports)
    }

    @Test
    fun anIndeterminatePercentIsReportedAsNull() = runTest {
        val reports = mutableListOf<Int?>()

        ProgressTicker.run(tick, { null }, { reports += it }, { fail("not cancelled") }) {
            delay(tick + tick / 5)
        }

        assertEquals(listOf<Int?>(null), reports)
    }

    @Test
    fun cancellingTheCallerAsksTheBlockingWorkToStop() = runTest {
        val stop = CompletableDeferred<Unit>()
        var cancels = 0
        var blockEnded = false

        val job = launch {
            ProgressTicker.run(tick, { 1 }, {}, { cancels++; stop.complete(Unit) }) {
                withContext(NonCancellable) { stop.await() }
                blockEnded = true
            }
        }
        delay(tick / 5)
        job.cancel()
        job.join()

        assertEquals(1, cancels)
        assertTrue(blockEnded)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aFailureIsPassedOnWithoutTheCancelSignal() = runTest {
        var cancels = 0

        val thrown = try {
            ProgressTicker.run(tick, { 1 }, {}, { cancels++ }) {
                delay(tick + tick / 5)
                throw IllegalStateException("broken")
            }
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("broken", thrown.message)
        assertEquals(0, cancels)
    }
}
