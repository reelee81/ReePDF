package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PdfTextCaretTest {
    private val point = Point(10f, 104f)
    private val midline = Point(10f, 106f)
    private val finger = Point(11f, 109f)
    private val caret = PdfTextCaret(3, point, midline)

    @Test
    fun theOwnPointIsUsedWhenMuPdfPlacesItOnTheCaretLetter() {
        assertSame(point, caret.choose(finger) { true })
    }

    @Test
    fun theFingerIsUsedWhenOnlyItLandsOnTheCaretLetter() {
        assertSame(finger, caret.choose(finger) { it === finger })
    }

    @Test
    fun theMidlineIsTheLastChoiceThatLands() {
        assertSame(midline, caret.choose(finger) { it === midline })
    }

    @Test
    fun theOwnPointStaysWhenNothingLands() {
        assertSame(point, caret.choose(finger) { false })
    }

    @Test
    fun aPointOnTheMidlineIsAskedOnlyOnce() {
        val onMidline = PdfTextCaret(3, midline, midline)
        val asked = mutableListOf<Point>()

        val chosen = onMidline.choose(finger) {
            asked += it
            false
        }

        assertSame(midline, chosen)
        assertEquals(listOf(midline, finger), asked)
    }
}
