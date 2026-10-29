package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchStepTest {
    private fun clicksFromOff(last: Int, count: Int): List<Int> {
        var position = 0
        var up = true
        return List(count) {
            val next = SwitchStep.next(position, last, up)
            position = next.position
            up = next.rising
            position
        }
    }

    @Test
    fun threePositionsGoUpAndBackDown() {
        assertEquals(listOf(1, 2, 1, 0, 1, 2), clicksFromOff(last = 2, count = 6))
    }

    @Test
    fun theMiddleKeepsTheDirectionOfTheLastChange() {
        assertEquals(2, SwitchStep.next(1, 2, rising = true).position)
        assertEquals(0, SwitchStep.next(1, 2, rising = false).position)
    }

    @Test
    fun theEndsAlwaysTurnBack() {
        val fromTop = SwitchStep.next(2, 2, rising = true)
        assertEquals(1, fromTop.position)
        assertFalse(fromTop.rising)
        val fromBottom = SwitchStep.next(0, 2, rising = false)
        assertEquals(1, fromBottom.position)
        assertTrue(fromBottom.rising)
    }

    @Test
    fun twoPositionsToggle() {
        assertEquals(listOf(1, 0, 1, 0), clicksFromOff(last = 1, count = 4))
    }

    @Test
    fun aPositionAboveTheLastStepsDownIntoRange() {
        assertEquals(1, SwitchStep.next(2, 1, rising = true).position)
    }

    @Test
    fun theThumbStopsInTheMiddleWhileTheFingerIsNearIt() {
        assertEquals(1f, SwitchStep.detent(0.8f, 2, 0.25f), 0.0001f)
        assertEquals(1f, SwitchStep.detent(1f, 2, 0.25f), 0.0001f)
        assertEquals(1f, SwitchStep.detent(1.2f, 2, 0.25f), 0.0001f)
    }

    @Test
    fun theThumbMovesWithoutJumpsAndReachesBothEnds() {
        assertEquals(0f, SwitchStep.detent(0f, 2, 0.25f), 0.0001f)
        assertEquals(0.5f, SwitchStep.detent(0.375f, 2, 0.25f), 0.0001f)
        assertEquals(1.5f, SwitchStep.detent(1.625f, 2, 0.25f), 0.0001f)
        assertEquals(2f, SwitchStep.detent(2f, 2, 0.25f), 0.0001f)
        var previous = 0f
        for (step in 0..2000) {
            val value = SwitchStep.detent(step / 1000f, 2, 0.25f)
            assertTrue(value >= previous)
            assertTrue(value - previous < 0.01f)
            previous = value
        }
    }

    @Test
    fun twoPositionsHaveNoDetentAndTheRangeIsClamped() {
        assertEquals(0.5f, SwitchStep.detent(0.5f, 1, 0.25f), 0.0001f)
        assertEquals(1f, SwitchStep.detent(3f, 1, 0.25f), 0.0001f)
        assertEquals(0f, SwitchStep.detent(-1f, 2, 0.25f), 0.0001f)
    }

    @Test
    fun releaseSnapsToTheNearestPosition() {
        assertEquals(0, SwitchStep.nearest(0.49f, 2))
        assertEquals(1, SwitchStep.nearest(0.5f, 2))
        assertEquals(1, SwitchStep.nearest(1.4f, 2))
        assertEquals(2, SwitchStep.nearest(1.6f, 2))
        assertEquals(2, SwitchStep.nearest(5f, 2))
        assertEquals(0, SwitchStep.nearest(-1f, 2))
        assertEquals(1, SwitchStep.nearest(1.9f, 1))
    }
}
