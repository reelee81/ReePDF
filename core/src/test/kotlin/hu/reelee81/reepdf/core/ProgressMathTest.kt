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
    fun withoutLaterFilesTheImportUnitsAreTheWrittenPagesAndTheLayout() {
        assertEquals(4_000L, ProgressMath.importUnits(1_000, 0, 1_000, 1))
        assertEquals(2_250L, ProgressMath.importUnits(1_000, 0, 1_000, 4))
    }

    @Test
    fun countingTheLaterFilesGivesEveryFileOfAnImportTheSameTotal() {
        val files = listOf(1_000, 2_000, 9_000, 9_000, 9_000)
        var copied = 0
        val totals = files.mapIndexed { index, pages ->
            val total = copied + ProgressMath.importUnits(pages, files.drop(index + 1).sum(), copied + pages, 1)
            copied += pages
            total
        }

        assertEquals(List(5) { 120_000L }, totals)
        assertEquals(0L, 1_000L * 100 / totals.first())
    }

    @Test
    fun anOperationFollowedByThumbnailsFillsOnlyItsShareOfTheBar() {
        assertEquals(90, ProgressMath.sharedPercent(100, ProgressMath.THUMBNAIL_PHASE_START))
        assertEquals(45, ProgressMath.sharedPercent(50, ProgressMath.THUMBNAIL_PHASE_START))
        assertEquals(37, ProgressMath.sharedPercent(37, 100))
        assertNull(ProgressMath.sharedPercent(null, ProgressMath.THUMBNAIL_PHASE_START))
    }

    @Test
    fun theThumbnailPhaseAfterAnOperationRunsFromNinetyToAHundredPercent() {
        val from = ProgressMath.THUMBNAIL_PHASE_START
        assertEquals(90, ProgressMath.thumbnailPercent(from, 10, 0))
        assertEquals(93, ProgressMath.thumbnailPercent(from, 10, 3))
        assertEquals(96, ProgressMath.thumbnailPercent(from, 3, 2))
        assertEquals(100, ProgressMath.thumbnailPercent(from, 10, 10))
        assertEquals(100, ProgressMath.thumbnailPercent(from, 10, 12))
        assertEquals(90, ProgressMath.thumbnailPercent(from, 10, -1))
        assertEquals(100, ProgressMath.thumbnailPercent(from, 0, 0))
    }

    @Test
    fun aThumbnailWaitOnItsOwnRunsFromZeroToAHundredPercent() {
        assertEquals(0, ProgressMath.thumbnailPercent(0, 6, 0))
        assertEquals(33, ProgressMath.thumbnailPercent(0, 6, 2))
        assertEquals(100, ProgressMath.thumbnailPercent(0, 6, 6))
    }

    @Test
    fun theScreensOwnMeasurementHidesTheFinishedHundredPercent() {
        assertEquals(0, ProgressMath.screenPercent(0, null))
        assertEquals(99, ProgressMath.screenPercent(99, null))
        assertNull(ProgressMath.screenPercent(100, null))
        assertNull(ProgressMath.screenPercent(null, null))
    }

    @Test
    fun pageSizeReadingBeforeAThumbnailWaitFillsOnlyItsShareOfTheBar() {
        val share = ProgressMath.THUMBNAIL_PHASE_START
        assertEquals(0, ProgressMath.screenPercent(0, share))
        assertEquals(45, ProgressMath.screenPercent(50, share))
        assertEquals(89, ProgressMath.screenPercent(99, share))
        assertEquals(90, ProgressMath.screenPercent(100, share))
        assertNull(ProgressMath.screenPercent(null, share))
    }

    @Test
    fun aThumbnailWaitOnItsOwnContinuesFromNinetyOnlyAfterAPercentWasShown() {
        assertEquals(0, ProgressMath.thumbnailPhaseStart(0, percentShown = false))
        assertEquals(90, ProgressMath.thumbnailPhaseStart(0, percentShown = true))
        assertEquals(90, ProgressMath.thumbnailPhaseStart(90, percentShown = false))
        assertEquals(90, ProgressMath.thumbnailPhaseStart(90, percentShown = true))
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
