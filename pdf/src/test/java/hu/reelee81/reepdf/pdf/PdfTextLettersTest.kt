package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import kotlin.math.roundToInt
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
            startLine(1f, 0f, false)
            add('f'.code, Quad(15f, 100f, 21f, 100f, 10f, 112f, 16f, 112f), SIZE, false, null)
            endLine()
        }

        assertPoint(10f, 106f, letters.before(0))
        assertPoint(16f, 106f, letters.after(0))
    }

    @Test
    fun aRightToLeftLetterStartsAtItsRightCorner() {
        val letters = page {
            startLine(1f, 0f, false)
            add(0x5D0, box(10f, 100f, 6f, 12f), SIZE, true, null)
            endLine()
        }

        assertPoint(16f, 106f, letters.before(0))
        assertPoint(10f, 106f, letters.after(0))
    }

    @Test
    fun theCaretsOfALineLieOnTheMidlineOfItsFirstLetter() {
        val letters = page {
            startLine(1f, 0f, false)
            add('k'.code, box(10f, 100f, 5f, 10f), SIZE, false, null)
            add('S'.code, box(15f, 102f, 8f, 16f), SIZE, false, null)
            add('k'.code, box(23f, 110f, 5f, 10f), SIZE, false, null)
            endLine()
        }

        assertPoint(28f, 105f, letters.after(2))
    }

    @Test
    fun aDownwardLineProjectsTheCaretsAlongItsDirection() {
        val letters = page {
            startLine(0f, 1f, false)
            add('a'.code, Quad(112f, 10f, 112f, 16f, 100f, 10f, 100f, 16f), SIZE, false, null)
            add('b'.code, Quad(112f, 16f, 112f, 22f, 100f, 16f, 100f, 22f), SIZE, false, null)
            endLine()
        }

        assertPoint(106f, 10f, letters.before(0))
        assertPoint(106f, 22f, letters.after(1))
    }

    @Test
    fun theTextReplacesControlAndInvalidCodesLikeTheMuPdfCopy() {
        val letters = page {
            startLine(1f, 0f, false)
            for ((index, code) in listOf('A'.code, 0x0D, 0x1F600, 0x110000).withIndex()) {
                add(code, box(10f + index * 6f, 100f, 6f, 12f), SIZE, false, null)
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
            startLine(1f, 0f, false)
            add(0xF8E7, box(67.3f, 104f, 6f, 6f), 6f, false, null)
            "gyártási".forEachIndexed { index, char -> add(char.code, box(79.3f + index * 6f, 100f, 6f, 12f), SIZE, false, null) }
            endLine()
        }

        assertEquals("", letters.word(70f, 107f, TOLERANCE)?.text)
        assertEquals("gyártási", letters.word(82f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun onlyAGapWiderThanASpaceSplitsTheLetters() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, null)
            add('b'.code, box(17.7f, 100f, 6f, 12f), SIZE, false, null)
            add('c'.code, box(25.6f, 100f, 6f, 12f), SIZE, false, null)
            endLine()
        }

        assertEquals("ab", letters.word(12f, 106f, TOLERANCE)?.text)
        assertEquals("c", letters.word(28f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun rightToLeftLettersMeasureTheirGapBackwards() {
        val letters = page {
            startLine(1f, 0f, false)
            add(0x5D0, box(40f, 100f, 6f, 12f), SIZE, true, null)
            add(0x5D1, box(34f, 100f, 6f, 12f), SIZE, true, null)
            add(0x5D2, box(10f, 100f, 6f, 12f), SIZE, true, null)
            endLine()
        }

        assertEquals("\u05D0\u05D1", letters.word(42f, 106f, TOLERANCE)?.text)
        assertEquals("\u05D2", letters.word(12f, 106f, TOLERANCE)?.text)
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
            startLine(1f, 0f, false)
            add(0x5D0, box(10f, 100f, 6f, 12f), SIZE, true, null)
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
        startLine(1f, 0f, false)
        "Handling".forEachIndexed { index, char -> add(char.code, box(77f + index * 5f, 332.1f, 5f, 6f), 6f, false, null) }
        endLine()
    }

    private fun PdfTextLetters.Builder.filler(left: Float = 75f) {
        startLine(1f, 0f, false)
        repeat(40) { add(' '.code, box(left + it * 5f, 333.2f, 5f, 10f), 10f, false, null) }
        endLine()
    }

    @Test
    fun theEndsAreTheStartOfTheFirstLineAndTheEndOfTheLast() {
        val letters = page {
            startLine(1f, 0f, false)
            endLine()
            line("first")
            line("middle", top = 120f)
            line("last", top = 140f)
            startLine(1f, 0f, false)
            endLine()
        }

        val (start, end) = letters.ends()!!

        assertPoint(10f, 106f, start)
        assertPoint(34f, 146f, end)
    }

    @Test
    fun aPageWithoutLettersHasNoEndsNoWordAndNoCaret() {
        val letters = page {
            startLine(1f, 0f, false)
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

    @Test
    fun aRunLiesOnTheBaselineOfItsFirstLetterFromItsStartToItsEnd() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, Point(10f, 109f))
            add('b'.code, box(16f, 100f, 6f, 12f), SIZE, false, Point(16f, 109f))
            endLine()
        }

        val lines = letters.baselines(arrayOf(box(10f, 100f, 12f, 12f)), 5f, 50f)

        assertEquals(0, lines.estimated)
        assertRun(floatArrayOf(5f, 59f, 17f, 59f, 0f, -1f, SIZE, 0f), lines.runs, 0)
    }

    @Test
    fun turnedAndMirroredLinesKeepTheirBaselineOnTheSideOfTheirText() {
        val letters = page {
            startLine(0f, 1f, false)
            add('a'.code, Quad(112f, 10f, 112f, 16f, 100f, 10f, 100f, 16f), SIZE, false, Point(103f, 10f))
            add('b'.code, Quad(112f, 16f, 112f, 22f, 100f, 16f, 100f, 22f), SIZE, false, Point(103f, 16f))
            endLine()
            startLine(-1f, 0f, false)
            add('c'.code, Quad(50f, 212f, 44f, 212f, 50f, 200f, 44f, 200f), 10f, false, Point(50f, 203f))
            endLine()
            startLine(1f, 0f, false)
            add('d'.code, Quad(10f, 312f, 16f, 312f, 10f, 300f, 16f, 300f), 8f, false, Point(10f, 302f))
            endLine()
        }

        val lines = letters.baselines(
            arrayOf(
                Quad(112f, 10f, 112f, 22f, 100f, 10f, 100f, 22f),
                Quad(50f, 212f, 44f, 212f, 50f, 200f, 44f, 200f),
                Quad(10f, 312f, 16f, 312f, 10f, 300f, 16f, 300f)
            ),
            0f, 0f
        )

        assertEquals(0, lines.estimated)
        assertRun(floatArrayOf(103f, 10f, 103f, 22f, 1f, 0f, SIZE, 0f), lines.runs, 0)
        assertRun(floatArrayOf(50f, 203f, 44f, 203f, 0f, 1f, 10f, 0f), lines.runs, 1)
        assertRun(floatArrayOf(10f, 302f, 16f, 302f, 0f, 1f, 8f, 0f), lines.runs, 2)
    }

    @Test
    fun anUprightColumnRunsDownAlongItsMiddleAndPointsToItsRightSide() {
        val letters = page { column("あいう") }

        val lines = letters.baselines(arrayOf(Quad(110f, 20f, 110f, 50f, 100f, 20f, 100f, 50f)), 0f, 0f)

        assertEquals(0, lines.estimated)
        assertRun(floatArrayOf(105f, 20f, 105f, 50f, 1f, 0f, 10f, 1f), lines.runs, 0)
    }

    @Test
    fun anUprightColumnIsOneWordWithItsCaretsDownItsMiddle() {
        val letters = page {
            column("あいう", left = 200f)
            column("えお", left = 185f)
        }

        assertEquals("あいう", letters.word(205f, 35f, TOLERANCE)?.text)
        assertEquals("えお", letters.word(190f, 22f, TOLERANCE)?.text)
        assertPoint(205f, 20f, letters.before(0))
        assertPoint(205f, 50f, letters.after(2))
        assertPoint(205f, 30f, letters.caret(207f, 33f)!!.point)
        assertPoint(205f, 40f, letters.caret(203f, 38f)!!.point)
        assertEquals(2, letters.caret(203f, 38f)!!.index)
        assertPoint(190f, 40f, letters.caret(190f, 45f)!!.point)
    }

    @Test
    fun runsInAnyOrderFindTheirOwnFirstLetter() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, Point(10f, 109f))
            endLine()
            startLine(1f, 0f, false)
            add('b'.code, box(10f, 120f, 6f, 10f), 9f, false, Point(10f, 128f))
            endLine()
        }

        val lines = letters.baselines(arrayOf(box(10f, 120f, 6f, 10f), box(10f, 100f, 6f, 12f)), 0f, 0f)

        assertEquals(0, lines.estimated)
        assertRun(floatArrayOf(10f, 128f, 16f, 128f, 0f, -1f, 9f, 0f), lines.runs, 0)
        assertRun(floatArrayOf(10f, 109f, 16f, 109f, 0f, -1f, SIZE, 0f), lines.runs, 1)
    }

    @Test
    fun aRunWithoutAKnownFirstLetterIsEstimatedFromItsBox() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, null)
            endLine()
        }

        val lines = letters.baselines(arrayOf(box(10f, 100f, 6f, 12f), box(200f, 100f, 30f, 10f)), 0f, 0f)

        assertEquals(2, lines.estimated)
        assertRun(floatArrayOf(10f, 109.6f, 16f, 109.6f, 0f, -1f, 9.6f, 0f), lines.runs, 0)
        assertRun(floatArrayOf(200f, 108f, 230f, 108f, 0f, -1f, 8f, 0f), lines.runs, 1)
        assertEquals(1, page {}.baselines(arrayOf(box(0f, 0f, 5f, 5f)), 0f, 0f).estimated)
    }

    @Test
    fun aBoxMovesSoThatItsMiddleSitsOnTheMiddleOfTheLetters() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, Point(10f, 109f))
            add('b'.code, box(16f, 100f, 6f, 12f), SIZE, false, Point(16f, 109f))
            endLine()
        }

        val rects = letters.centered(arrayOf(box(10f, 100f, 12f, 12f)), 5f, 50f)

        assertRect(floatArrayOf(5f, 49.76f, 17f, 61.76f), rects, 0)
    }

    @Test
    fun aSearchBoxFindsItsLetterByItsMiddleAndATurnedLineMovesAlongItsText() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, Point(10f, 109f))
            endLine()
            startLine(0f, 1f, false)
            add('b'.code, Quad(112f, 10f, 112f, 16f, 100f, 10f, 100f, 16f), SIZE, false, Point(103f, 10f))
            endLine()
        }

        val rects = letters.centered(arrayOf(box(10.5f, 100f, 5f, 12f), Quad(112f, 10f, 112f, 16f, 100f, 10f, 100f, 16f)), 0f, 0f)

        assertRect(floatArrayOf(10.5f, 99.76f, 15.5f, 111.76f), rects, 0)
        assertRect(floatArrayOf(100.24f, 10f, 112.24f, 16f), rects, 1)
    }

    @Test
    fun aFlatBoxGetsAStandardHeightAroundTheMiddleOfTheLetters() {
        val letters = page {
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 0.6f), SIZE, false, Point(10f, 100.6f))
            endLine()
        }

        val rects = letters.centered(arrayOf(box(10f, 100f, 6f, 0.6f)), 0f, 0f)

        assertRect(floatArrayOf(10f, 90.34f, 16f, 104.38f), rects, 0)
    }

    @Test
    fun uprightColumnsAndLettersWithoutABaselineKeepTheirBoxes() {
        val letters = page {
            column("あいう")
            startLine(1f, 0f, false)
            add('a'.code, box(10f, 100f, 6f, 12f), SIZE, false, null)
            endLine()
        }

        val rects = letters.centered(
            arrayOf(Quad(110f, 20f, 110f, 50f, 100f, 20f, 100f, 50f), box(10f, 100f, 6f, 12f), box(300f, 300f, 6f, 12f)),
            0f,
            0f
        )

        assertRect(floatArrayOf(100f, 20f, 110f, 50f), rects, 0)
        assertRect(floatArrayOf(10f, 100f, 16f, 112f), rects, 1)
        assertRect(floatArrayOf(300f, 300f, 306f, 312f), rects, 2)
    }

    private fun assertRect(expected: FloatArray, rects: FloatArray, index: Int) {
        val size = PdfTextHits.RECT_SIZE
        assertEquals(expected.toList(), rects.copyOfRange(index * size, index * size + size).map { (it * 1000f).roundToInt() / 1000f })
    }

    private fun assertRun(expected: FloatArray, runs: FloatArray, index: Int) {
        val size = PdfTextBaselines.RUN_SIZE
        assertEquals(expected.toList(), runs.copyOfRange(index * size, index * size + size).map { (it * 1000f).roundToInt() / 1000f })
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
            startLine(1f, 0f, false)
            text.forEachIndexed { index, char -> add(char.code, box(left + index * width, top, width, SIZE), SIZE, false, null) }
            endLine()
        }

        internal fun PdfTextLetters.Builder.column(text: String, left: Float = 100f, top: Float = 20f, size: Float = 10f) {
            startLine(0f, 1f, true)
            text.forEachIndexed { index, char ->
                val start = top + index * size
                add(char.code, Quad(left + size, start, left + size, start + size, left, start, left, start + size), size, false, Point(left, start))
            }
            endLine()
        }
    }
}
