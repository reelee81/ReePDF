package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Quad
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PdfTextQuadsTest {
    @Test
    fun anUprightCharacterBecomesItsOwnBoxRelativeToThePageOrigin() {
        val rects = FloatArray(2 * PdfTextHits.RECT_SIZE)

        PdfTextQuads.put(rects, 1, Quad(10f, 20f, 50f, 20f, 10f, 32f, 50f, 32f), 5f, 8f)

        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 5f, 12f, 45f, 24f), rects, 0f)
    }

    @Test
    fun aRotatedCharacterBecomesTheBoxAroundAllFourCorners() {
        val rects = FloatArray(PdfTextHits.RECT_SIZE)

        PdfTextQuads.put(rects, 0, Quad(100f, 10f, 100f, 50f, 88f, 10f, 88f, 50f), 0f, 0f)

        assertArrayEquals(floatArrayOf(88f, 10f, 100f, 50f), rects, 0f)
    }
}
