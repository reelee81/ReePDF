package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressNotifyPacingTest {
    private val none = -2
    private val indeterminate = -1

    @Test
    fun nothingIsShownBeforeTheRevealTime() {
        assertEquals(5_000L, ProgressNotifyPacing.waitMs(1_000L, none, 1_000L, 30, 1_000L))
        assertEquals(1_500L, ProgressNotifyPacing.waitMs(1_000L, none, 1_000L, indeterminate, 4_500L))
    }

    @Test
    fun afterTheRevealTheFirstValueIsShownAtOnce() {
        assertEquals(0L, ProgressNotifyPacing.waitMs(0L, none, 0L, 30, 5_000L))
        assertEquals(0L, ProgressNotifyPacing.waitMs(0L, none, 0L, indeterminate, 9_000L))
    }

    @Test
    fun anUnchangedValueIsNotShownAgain() {
        assertNull(ProgressNotifyPacing.waitMs(0L, 30, 6_000L, 30, 9_000L))
        assertNull(ProgressNotifyPacing.waitMs(0L, indeterminate, 6_000L, indeterminate, 9_000L))
    }

    @Test
    fun percentChangesAreShownAtMostOncePerSecond() {
        assertEquals(600L, ProgressNotifyPacing.waitMs(0L, 30, 6_000L, 31, 6_400L))
        assertEquals(0L, ProgressNotifyPacing.waitMs(0L, 30, 6_000L, 31, 7_000L))
    }

    @Test
    fun switchingBetweenIndeterminateAndPercentIsNotThrottled() {
        assertEquals(0L, ProgressNotifyPacing.waitMs(0L, indeterminate, 6_000L, 31, 6_100L))
        assertEquals(0L, ProgressNotifyPacing.waitMs(0L, 31, 6_000L, indeterminate, 6_100L))
    }
}
