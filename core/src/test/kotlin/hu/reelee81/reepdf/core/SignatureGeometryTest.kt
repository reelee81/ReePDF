package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureGeometryTest {
    private val tolerance = 0.001f

    @Test
    fun theTwoDpiChoicesHaveTheirReferenceWidthsAndUnknownFallsBackTo144Dpi() {
        assertEquals(1190f, SignatureGeometry.refPxForSignatureDpi(0), 0f)
        assertEquals(2480f, SignatureGeometry.refPxForSignatureDpi(1), 0f)
        assertEquals(1190f, SignatureGeometry.refPxForSignatureDpi(2), 0f)
        assertEquals(1190f, SignatureGeometry.refPxForSignatureDpi(-1), 0f)
    }

    @Test
    fun aLandscapePageSwapsTheA4Sides() {
        assertEquals(210f, SignatureGeometry.pageWidthMm(isLandscape = false), 0f)
        assertEquals(297f, SignatureGeometry.pageHeightMm(isLandscape = false), 0f)
        assertEquals(297f, SignatureGeometry.pageWidthMm(isLandscape = true), 0f)
        assertEquals(210f, SignatureGeometry.pageHeightMm(isLandscape = true), 0f)
    }

    @Test
    fun onAPortraitPageTheSignatureIsScaledByThePageWidth() {
        val (maxX, maxY) = SignatureGeometry.maxPositionMm(595, 297, 1190f, isLandscape = false)
        assertEquals(105f, maxX, tolerance)
        assertEquals(297f - 297 * 210f / 1190f, maxY, tolerance)
    }

    @Test
    fun onALandscapePageTheSignatureIsScaledByThePageHeight() {
        val (maxX, maxY) = SignatureGeometry.maxPositionMm(2480, 1240, 2480f, isLandscape = true)
        assertEquals(87f, maxX, tolerance)
        assertEquals(105f, maxY, tolerance)
    }

    @Test
    fun aSignatureAsLargeAsThePageLeavesNoRoom() {
        val (maxX, maxY) = SignatureGeometry.maxPositionMm(1190, 1683, 1190f, isLandscape = false)
        assertEquals(0f, maxX, tolerance)
        assertEquals(0f, maxY, tolerance)
    }

    @Test
    fun aTooWideSignatureGivesANegativeLimitWithoutClamping() {
        val (maxX, maxY) = SignatureGeometry.maxPositionMm(4960, 100, 2480f, isLandscape = false)
        assertEquals(-210f, maxX, tolerance)
        assertEquals(297f - 100 * 210f / 2480f, maxY, tolerance)
    }

    @Test
    fun aPortraitPageIsRasterizedToTheReferenceWidth() {
        val raster = SignatureGeometry.pageRaster(595, 842, 1190f, isLandscape = false)

        assertEquals(1190, raster.width)
        assertEquals(1684, raster.height)
        assertEquals(2f, raster.scale, tolerance)
        assertEquals(0f, raster.offsetX, tolerance)
        assertEquals(0f, raster.offsetY, tolerance)
    }

    @Test
    fun aLandscapePageIsRasterizedToTheReferenceHeightAndCentred() {
        val raster = SignatureGeometry.pageRaster(842, 595, 2480f, isLandscape = true)

        assertEquals(3510, raster.width)
        assertEquals(2480, raster.height)
        assertEquals(2480f / 595f, raster.scale, tolerance)
        assertEquals((3510 - 842 * (2480f / 595f)) / 2f, raster.offsetX, tolerance)
        assertEquals(0f, raster.offsetY, tolerance)
    }

    @Test
    fun aLandscapePageInPortraitModeKeepsTheReferenceWidth() {
        val raster = SignatureGeometry.pageRaster(842, 595, 1190f, isLandscape = false)

        assertEquals(1190, raster.width)
        assertEquals(841, raster.height)
        assertEquals(1190f / 842f, raster.scale, tolerance)
        assertEquals(0f, raster.offsetX, tolerance)
        assertEquals((841 - 595 * (1190f / 842f)) / 2f, raster.offsetY, tolerance)
    }

    @Test
    fun theSignatureIsPlacedInMillimetresAndKeepsItsPixelSizeAtTheReferenceResolution() {
        val placement = SignatureGeometry.placement(1190, 1684, 595, 297, 1190f, 10f, 20f, isLandscape = false)

        assertEquals(10f * 1190f / 210f, placement.left, tolerance)
        assertEquals(20f * 1684f / 297f, placement.top, tolerance)
        assertEquals(595, placement.width)
        assertEquals(297, placement.height)
        assertEquals(1, placement.sampleSize)
    }

    @Test
    fun onALandscapeRasterTheSignatureFollowsTheHeight() {
        val placement = SignatureGeometry.placement(3510, 2480, 1000, 400, 1240f, 0f, 5f, isLandscape = true)

        assertEquals(0f, placement.left, tolerance)
        assertEquals(5f * 2480f / 210f, placement.top, tolerance)
        assertEquals(2000, placement.width)
        assertEquals(800, placement.height)
    }

    @Test
    fun theSampleSizeHalvesWhileBothHalvesStillCoverTheTarget() {
        assertEquals(1, SignatureGeometry.sampleSize(100, 100, 100, 100))
        assertEquals(4, SignatureGeometry.sampleSize(4000, 2000, 1000, 500))
        assertEquals(1, SignatureGeometry.sampleSize(4000, 100, 1000, 500))
    }

    @Test
    fun theImageIsFittedIntoThePageAndCentred() {
        val wide = SignatureGeometry.fitIntoPage(2000, 1000, 595f, 842f)
        val tall = SignatureGeometry.fitIntoPage(100, 400, 595f, 842f)

        assertEquals(0f, wide.x, tolerance)
        assertEquals(272.25f, wide.y, tolerance)
        assertEquals(595f, wide.width, tolerance)
        assertEquals(297.5f, wide.height, tolerance)
        assertEquals(192.25f, tall.x, tolerance)
        assertEquals(0f, tall.y, tolerance)
        assertEquals(210.5f, tall.width, tolerance)
        assertEquals(842f, tall.height, tolerance)
    }
}
