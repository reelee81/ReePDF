package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressMathTest {
    @Test
    fun aSignedPageWeighsMoreAtHighDpiAndWithEffects() {
        assertEquals(100, ProgressMath.signedPageWeight(highDpi = false, scanEffect = false, grayscale = false))
        assertEquals(280, ProgressMath.signedPageWeight(highDpi = true, scanEffect = false, grayscale = false))
        assertEquals(120, ProgressMath.signedPageWeight(highDpi = false, scanEffect = true, grayscale = false))
        assertEquals(364, ProgressMath.signedPageWeight(highDpi = true, scanEffect = true, grayscale = false))
        assertEquals(110, ProgressMath.signedPageWeight(highDpi = false, scanEffect = false, grayscale = true))
        assertEquals(322, ProgressMath.signedPageWeight(highDpi = true, scanEffect = false, grayscale = true))
        assertEquals(132, ProgressMath.signedPageWeight(highDpi = false, scanEffect = true, grayscale = true))
        assertEquals(419, ProgressMath.signedPageWeight(highDpi = true, scanEffect = true, grayscale = true))
    }

    @Test
    fun aOneUpLayoutCountsEverySourcePageTwiceMoreThanItsOutput() {
        assertEquals(30L, ProgressMath.layoutUnits(10, 1))
        assertEquals(0L, ProgressMath.layoutUnits(0, 1))
    }

    @Test
    fun aMultiUpLayoutCountsTheSourcePagesAndTheRoundedUpOutputPages() {
        assertEquals(13L, ProgressMath.layoutUnits(10, 4))
        assertEquals(10L, ProgressMath.layoutUnits(8, 4))
        assertEquals(0L, ProgressMath.layoutUnits(0, 4))
    }

    @Test
    fun withoutWorkOrDoneUnitsThePercentIsIndeterminate() {
        assertNull(ProgressMath.measuredPercent(0, 0, 0, 5_000, -1, 1_000))
        assertNull(ProgressMath.measuredPercent(100, 0, 0, 5_000, -1, 1_000))
    }

    @Test
    fun thePercentIsRoundedDownAndDoneIsLimitedToTheTotal() {
        assertEquals(25, ProgressMath.measuredPercent(200, 50, 0, 5_000, -1, 1_000))
        assertEquals(66, ProgressMath.measuredPercent(3, 2, 0, 5_000, -1, 1_000))
        assertEquals(100, ProgressMath.measuredPercent(100, 150, 0, 5_000, -1, 1_000))
        assertEquals(50, ProgressMath.measuredPercent(3_000_000_000, 1_500_000_000, 0, 5_000, -1, 1_000))
    }

    @Test
    fun thePercentNeverGoesBackwards() {
        assertEquals(40, ProgressMath.measuredPercent(100, 10, 0, 5_000, 40, 1_000))
    }

    @Test
    fun aRecentIdleStillMeasuresButALongIdleIsIndeterminate() {
        assertEquals(50, ProgressMath.measuredPercent(100, 50, 1_000, 1_500, -1, 1_000))
        assertEquals(50, ProgressMath.measuredPercent(100, 50, 1_000, 1_999, -1, 1_000))
        assertNull(ProgressMath.measuredPercent(100, 50, 1_000, 2_000, -1, 1_000))
    }
}
