package hu.reelee81.reepdf.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCropTest {
    private val crop = PdfCropRect(0.1f, 0.2f, 0.6f, 0.9f)

    @Test
    fun invalidOrTooSmallCropsAreRejectedAndValuesAreClamped() {
        assertNull(PdfCropRect.of(0.5f, 0.5f, 0.505f, 0.9f))
        assertNull(PdfCropRect.of(Float.NaN, 0f, 1f, 1f))
        assertCrop(0f, 0f, 1f, 0.5f, PdfCropRect.of(-0.2f, -1f, 1.4f, 0.5f))
    }

    @Test
    fun aFullPageFrameMeansNoCrop() {
        assertTrue(PdfCropRect(0f, 0.001f, 0.999f, 1f).isFullPage)
        assertFalse(crop.isFullPage)
        val crops = PdfCrops.withCrop(mapOf(3 to crop), listOf(3, 4), PdfCropRect(0f, 0f, 1f, 1f))
        assertTrue(crops.isEmpty())
    }

    @Test
    fun encodedCropsDecodeToTheSameValuesAndBrokenEntriesAreSkipped() {
        val crops = mapOf(0 to crop, 17 to PdfCropRect(0f, 0f, 0.5f, 0.5f))
        assertEquals(crops, PdfCrops.decode(PdfCrops.encode(crops)))
        val decoded = PdfCrops.decode("x:1,2,3,4;2:0.1,0.2,0.6,0.9;5:0,0;-1:0,0,1,1;9:0,0,1,1")
        assertEquals(1, decoded.size)
        assertCrop(0.1f, 0.2f, 0.6f, 0.9f, decoded[2])
        assertTrue(PdfCrops.decode("").isEmpty())
    }

    @Test
    fun removingPagesShiftsTheLaterCrops() {
        val crops = mapOf(1 to crop, 4 to PdfCropRect(0f, 0f, 0.5f, 0.5f), 7 to PdfCropRect(0.5f, 0.5f, 1f, 1f))
        val result = PdfCrops.afterRemoval(crops, setOf(2, 3, 4))
        assertEquals(mapOf(1 to crop, 4 to PdfCropRect(0.5f, 0.5f, 1f, 1f)), result)
    }

    @Test
    fun reorderingMovesTheCropsWithTheirPages() {
        val crops = mapOf(0 to crop, 3 to PdfCropRect(0f, 0f, 0.5f, 0.5f))
        val result = PdfCrops.afterReorder(crops, intArrayOf(2, 3, 0, 1))
        assertEquals(mapOf(1 to PdfCropRect(0f, 0f, 0.5f, 0.5f), 2 to crop), result)
    }

    @Test
    fun theLeftHalfOfAPageRotatedLeftIsShownAsTheBottomHalf() {
        val leftHalf = PdfCropRect(0f, 0f, 0.5f, 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), PdfCrops.toDisplay(leftHalf, 0), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1f), PdfCrops.toDisplay(leftHalf, 1), 0.0001f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 1f, 1f), PdfCrops.toDisplay(leftHalf, 2), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0.5f), PdfCrops.toDisplay(leftHalf, 3), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), PdfCrops.toDisplay(null, 1), 0.0001f)
    }

    @Test
    fun aFrameDrawnOnTheRotatedPreviewIsStoredInTheOriginalPageAndBack() {
        for (turns in 0..3) {
            val stored = PdfCrops.fromDisplay(0.1f, 0.2f, 0.7f, 0.6f, turns)!!
            val shown = PdfCrops.toDisplay(stored, turns)
            assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.7f, 0.6f), shown, 0.0001f)
        }
        assertCrop(0f, 0f, 0.5f, 1f, PdfCrops.fromDisplay(0f, 0.5f, 1f, 1f, 1))
    }

    @Test
    fun aSignedGroupMergedIntoOnePageKeepsTheFollowingCropsAligned() {
        val per = 4
        val crops = (0 until 12).associateWith { PdfCropRect(0f, 0f, 0.5f + it / 100f, 0.5f) }
        val signedStarts = listOf(4)
        val merged = signedStarts.flatMap { start -> start + 1 until start + per }.toSet()
        val result = PdfCrops.afterRemoval(PdfCrops.withCrop(crops, signedStarts, null), merged)
        assertEquals(crops[3], result[3])
        assertNull(result[4])
        assertEquals(crops[8], result[5])
        assertEquals(crops[11], result[8])
        assertEquals(8, result.size)
    }

    private fun assertCrop(left: Float, bottom: Float, right: Float, top: Float, actual: PdfCropRect?) {
        assertNotNull(actual)
        assertArrayEquals(
            floatArrayOf(left, bottom, right, top),
            floatArrayOf(actual!!.left, actual.bottom, actual.right, actual.top),
            0.0001f
        )
    }
}
