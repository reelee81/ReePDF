package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextLettersTest {
    @Test
    fun theCaretsSitOnTheMidlineAtTheBottomCornersOfTheLetters() {
        val letters = page { line("abc") }

        assertPoint(10f, 106f, letters.before(0))
        assertPoint(22f, 106f, letters.before(2))
        assertPoint(16f, 106f, letters.after(0))
        assertPoint(28f, 106f, letters.after(2))
    }

    @Test
    fun aSlantedLetterKeepsItsCaretsAtItsBottomCorners() {
        val letters = page {
            startLine(1f, 0f)
            add('f'.code, Quad(15f, 100f, 21f, 100f, 10f, 112f, 16f, 112f), SIZE, false)
            endLine()
        }

        assertPoint(10f, 106f, letters.before(0))
        assertPoint(16f, 106f, letters.after(0))
    }

    @Test
    fun aRightToLeftLetterStartsAtItsRightCorner() {
        val letters = page {
            startLine(1f, 0f)
            add(0x5D0, box(10f, 100f, 6f, 12f), SIZE, true)
            endLine()
        }

        assertPoint(16f, 106f, letters.before(0))
        assertPoint(10f, 106f, letters.after(0))
    }

    @Test
    fun theCaretsOfALineLieOnTheMidlineOfItsFirstLetter() {
        val letters = page {
            startLine(1f, 0f)
            add('k'.code, box(10f, 100f, 5f, 10f), SIZE, false)
            add('S'.code, box(15f, 102f, 8f, 16f), SIZE, false)
            add('k'.code, box(23f, 110f, 5f, 10f), SIZE, false)
            endLine()
        }

        assertPoint(28f, 105f, letters.after(2))
    }

    @Test
    fun aDownwardLineProjectsTheCaretsAlongItsDirection() {
        val letters = page {
            startLine(0f, 1f)
            add('a'.code, Quad(112f, 10f, 112f, 16f, 100f, 10f, 100f, 16f), SIZE, false)
            add('b'.code, Quad(112f, 16f, 112f, 22f, 100f, 16f, 100f, 22f), SIZE, false)
            endLine()
        }

        assertPoint(106f, 10f, letters.before(0))
        assertPoint(106f, 22f, letters.after(1))
    }

    @Test
    fun theTextReplacesControlAndInvalidCodesLikeTheMuPdfCopy() {
        val letters = page {
            startLine(1f, 0f)
            for ((index, code) in listOf('A'.code, 0x0D, 0x1F600, 0x110000).withIndex()) {
                add(code, box(10f + index * 6f, 100f, 6f, 12f), SIZE, false)
            }
            endLine()
        }

        assertEquals("A�😀�", letters.text(0, 3))
    }

    @Test
    fun aWordEndsAtASpaceAndAtTheEndOfItsLine() {
        val letters = page {
            line("Telje- (kW)")
            line("sítmény", top = 114f)
        }

        assertEquals("Telje", letters.word(37f, 106f, TOLERANCE)?.text)
        assertEquals("kW", letters.word(61f, 106f, TOLERANCE)?.text)
        assertEquals("sítmény", letters.word(12f, 120f, TOLERANCE)?.text)
    }

    @Test
    fun aGapWiderThanASpaceSeparatesASignFromTheWord() {
        val letters = page {
            startLine(1f, 0f)
            add(0xF8E7, box(67.3f, 104f, 6f, 6f), 6f, false)
            "gyártási".forEachIndexed { index, char -> add(char.code, box(79.3f + index * 6f, 100f, 6f, 12f), SIZE, false) }
            endLine()
        }

        assertEquals("", letters.word(70f, 107f, TOLERANCE)?.text)
        assertEquals("gyártási", letters.word(82f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun onlyAGapWiderThanASpaceSplitsTheLetters() {
        val letters = page {
            startLine(1f, 0f)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false)
            add('b'.code, box(17.7f, 100f, 6f, 12f), SIZE, false)
            add('c'.code, box(25.6f, 100f, 6f, 12f), SIZE, false)
            endLine()
        }

        assertEquals("ab", letters.word(12f, 106f, TOLERANCE)?.text)
        assertEquals("c", letters.word(28f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun rightToLeftLettersMeasureTheirGapBackwards() {
        val letters = page {
            startLine(1f, 0f)
            add(0x5D0, box(40f, 100f, 6f, 12f), SIZE, true)
            add(0x5D1, box(34f, 100f, 6f, 12f), SIZE, true)
            add(0x5D2, box(10f, 100f, 6f, 12f), SIZE, true)
            endLine()
        }

        assertEquals("אב", letters.word(42f, 106f, TOLERANCE)?.text)
        assertEquals("ג", letters.word(12f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun theCaretGoesBeforeOrAfterTheNearestLetterByTheHalfUnderTheFinger() {
        val letters = page { line("a bc") }

        assertPoint(10f, 106f, letters.caret(12f, 106f)!!.point)
        assertPoint(16f, 106f, letters.caret(14f, 106f)!!.point)
        assertPoint(22f, 106f, letters.caret(21f, 103f)!!.point)
        assertPoint(34f, 106f, letters.caret(33f, 110f)!!.point)
        assertPoint(34f, 106f, letters.caret(200f, 106f)!!.point)
    }

    @Test
    fun aRightToLeftLetterEndsOnItsLeftHalf() {
        val letters = page {
            startLine(1f, 0f)
            add(0x5D0, box(10f, 100f, 6f, 12f), SIZE, true)
            endLine()
        }

        assertPoint(10f, 106f, letters.caret(11f, 106f)!!.point)
        assertPoint(16f, 106f, letters.caret(15f, 106f)!!.point)
    }

    @Test
    fun aPointBetweenTwoLinesGoesToTheNearerLine() {
        val letters = page {
            line("felső", top = 88f)
            line("alsó", top = 102f)
        }

        assertPoint(10f, 94f, letters.caret(12f, 100.8f)!!.point)
        assertPoint(10f, 108f, letters.caret(12f, 101.2f)!!.point)
    }

    @Test
    fun aPointBetweenStaggeredCellsStaysInItsOwnColumn() {
        val letters = page {
            line("Sorszám", left = 58.8f, top = 458.3f, width = 6.27f)
            line("Forgalmi", left = 112.9f, top = 451.1f, width = 5.81f)
            line("rendszám", left = 111.3f, top = 465.5f, width = 6.21f)
            line("Gépjármű fajtája", left = 170.9f, top = 458.3f, width = 5.59f)
        }

        assertEquals(457.1f, letters.caret(135f, 464f)!!.point.y, 0.001f)
        assertEquals(471.5f, letters.caret(135f, 464.6f)!!.point.y, 0.001f)
    }

    @Test
    fun theCaretIndexIsTheLetterOrTheOneAfterIt() {
        val letters = page { line("abc") }

        assertEquals(0, letters.caret(12f, 106f)!!.index)
        assertEquals(1, letters.caret(14f, 106f)!!.index)
        assertEquals(3, letters.caret(200f, 106f)!!.index)
    }

    @Test
    fun aLaterLineCoveringTheMidlineMovesThePointWithinItsOwnBand() {
        val letters = page {
            label()
            filler()
        }

        val caret = letters.caret(80f, 335f)!!

        assertEquals(335.1f, caret.midline.y, 0.001f)
        assertTrue("${caret.point.y} is not between the two bands", caret.point.y > 332.1f && caret.point.y < 333.2f)
        assertEquals(caret.midline.x, caret.point.x, 0f)
    }

    @Test
    fun anEarlierCoveringLineLeavesThePointOnTheMidline() {
        val letters = page {
            filler()
            label()
        }

        val caret = letters.caret(80f, 335f)!!

        assertSame(caret.midline, caret.point)
    }

    @Test
    fun aLaterLineBesideTheCaretLeavesThePointOnTheMidline() {
        val letters = page {
            label()
            filler(left = 300f)
        }

        val caret = letters.caret(80f, 335f)!!

        assertSame(caret.midline, caret.point)
    }

    @Test
    fun theMuPdfHandlesMustBelongToTheCaretLetter() {
        val letters = page { line("abc") }
        val b = box(16f, 100f, 6f, 12f)
        val handles = Quad(b.ul_x, b.ul_y, b.ur_x, b.ur_y, b.ll_x, b.ll_y, b.ll_x, b.ll_y)
        val end = Point(40f, 106f)

        assertTrue(letters.snapsTo(1, handles, Point(17f, 106f)))
        assertFalse(letters.snapsTo(0, handles, Point(17f, 106f)))
        assertFalse(letters.snapsTo(2, handles, Point(17f, 106f)))
        val last = box(22f, 100f, 6f, 12f)
        assertTrue(letters.snapsTo(3, Quad(last.ul_x, last.ul_y, 0f, 0f, last.ll_x, last.ll_y, end.x, end.y), end))
        assertFalse(letters.snapsTo(3, Quad(last.ul_x, last.ul_y, 0f, 0f, last.ll_x, last.ll_y, 39f, end.y), end))
    }

    private fun PdfTextLetters.Builder.label() {
        startLine(1f, 0f)
        "Handling".forEachIndexed { index, char -> add(char.code, box(77f + index * 5f, 332.1f, 5f, 6f), 6f, false) }
        endLine()
    }

    private fun PdfTextLetters.Builder.filler(left: Float = 75f) {
        startLine(1f, 0f)
        repeat(40) { add(' '.code, box(left + it * 5f, 333.2f, 5f, 10f), 10f, false) }
        endLine()
    }

    @Test
    fun theEndsAreTheStartOfTheFirstLineAndTheEndOfTheLast() {
        val letters = page {
            startLine(1f, 0f)
            endLine()
            line("first")
            line("middle", top = 120f)
            line("last", top = 140f)
            startLine(1f, 0f)
            endLine()
        }

        val (start, end) = letters.ends()!!

        assertPoint(10f, 106f, start)
        assertPoint(34f, 146f, end)
    }

    @Test
    fun aPageWithoutLettersHasNoEndsNoWordAndNoCaret() {
        val letters = page {
            startLine(1f, 0f)
            endLine()
        }

        assertEquals(0, letters.size)
        assertNull(letters.ends())
        assertNull(letters.word(10f, 10f, TOLERANCE))
        assertNull(letters.caret(10f, 10f))
    }

    @Test
    fun theLettersOfALargePageAllFitIn() {
        val letters = page {
            for (row in 0 until 100) line("x".repeat(30), top = row * 14f)
        }

        assertEquals(3_000, letters.size)
        assertPoint(190f, 1392f, letters.after(2_999))
    }

    private fun assertPoint(x: Float, y: Float, point: Point) {
        assertEquals(x, point.x, 0.001f)
        assertEquals(y, point.y, 0.001f)
    }

    companion object {
        const val SIZE = 12f
        const val TOLERANCE = 2f

        fun box(left: Float, top: Float, width: Float, height: Float) =
            Quad(left, top, left + width, top, left, top + height, left + width, top + height)

        internal fun page(build: PdfTextLetters.Builder.() -> Unit): PdfTextLetters =
            PdfTextLetters.Builder().apply(build).build()

        internal fun PdfTextLetters.Builder.line(text: String, left: Float = 10f, top: Float = 100f, width: Float = 6f) {
            startLine(1f, 0f)
            text.forEachIndexed { index, char -> add(char.code, box(left + index * width, top, width, SIZE), SIZE, false) }
            endLine()
        }
    }
}
