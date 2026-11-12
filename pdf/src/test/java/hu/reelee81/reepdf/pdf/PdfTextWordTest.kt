package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import hu.reelee81.reepdf.pdf.PdfTextLettersTest.Companion.TOLERANCE
import hu.reelee81.reepdf.pdf.PdfTextLettersTest.Companion.box
import hu.reelee81.reepdf.pdf.PdfTextLettersTest.Companion.line
import hu.reelee81.reepdf.pdf.PdfTextLettersTest.Companion.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextWordTest {
    @Test
    fun aTouchInsideALetterFindsItsWholeWordBetweenTheSpaces() {
        val letters = page { line("az adó alapja") }

        assertEquals("adó", letters.word(37f, 106f, TOLERANCE)?.text)
        assertEquals("alapja", letters.word(80f, 101f, TOLERANCE)?.text)
    }

    @Test
    fun aWordLosesThePunctuationAtItsEdgesButKeepsTheOneInside() {
        val letters = page { line("(kW), adatai: [Gjt. teszt.png quick-cargo-service 50% § -") }

        assertEquals("kW", letters.word(19f, 106f, TOLERANCE)?.text)
        assertEquals("adatai", letters.word(67f, 106f, TOLERANCE)?.text)
        assertEquals("Gjt", letters.word(109f, 106f, TOLERANCE)?.text)
        assertEquals("teszt.png", letters.word(163f, 106f, TOLERANCE)?.text)
        assertEquals("quick-cargo-service", letters.word(223f, 106f, TOLERANCE)?.text)
        assertEquals("50%", letters.word(319f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun aTouchOnPunctuationOutsideAWordSelectsOnlyThatMark() {
        val letters = page { line("(kW), adatai: [Gjt. § ...") }

        assertEquals("(", letters.word(13f, 106f, TOLERANCE)?.text)
        assertEquals(")", letters.word(31f, 106f, TOLERANCE)?.text)
        assertEquals(",", letters.word(37f, 106f, TOLERANCE)?.text)
        assertEquals(":", letters.word(85f, 106f, TOLERANCE)?.text)
        assertEquals("[", letters.word(97f, 106f, TOLERANCE)?.text)
        assertEquals(".", letters.word(121f, 106f, TOLERANCE)?.text)
        assertEquals("§", letters.word(133f, 106f, TOLERANCE)?.text)
        assertEquals(".", letters.word(151f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun theAnchorsOfATrimmedWordSitAtItsFirstAndLastLetter() {
        val word = page { line("(kW),") }.word(19f, 106f, TOLERANCE)!!

        val (from, to) = word.anchors({ _, _ -> "kW" }) { null }

        assertEquals(16f, from.x, 0f)
        assertEquals(28f, to.x, 0f)
    }

    @Test
    fun theHighlightMustTouchTheWholeTouchedWordIncludingItsTrimmedPunctuation() {
        val word = page { line("(kW), adatai") }.word(19f, 106f, TOLERANCE)!!

        assertEquals("kW", word.text)
        assertTrue(word.touches(box(16f, 100f, 12f, 12f), TOLERANCE))
        assertTrue(word.touches(box(5f, 100f, 4f, 12f), TOLERANCE))
        assertFalse(word.touches(box(200f, 100f, 6f, 12f), TOLERANCE))
        assertFalse(word.touches(box(16f, 130f, 12f, 12f), TOLERANCE))
    }

    @Test
    fun theStaggeredHeaderCellsGiveOnlyTheTouchedWord() {
        val letters = page {
            line("Sorszám", left = 58.8f, top = 458.3f, width = 6.27f)
            line("Forgalmi", left = 112.9f, top = 451.1f, width = 5.81f)
            line("rendszám", left = 111.3f, top = 465.5f, width = 6.21f)
            line("Gépjármű fajtája", left = 170.9f, top = 458.3f, width = 5.59f)
        }

        assertEquals("Sorszám", letters.word(101f, 469.9f, TOLERANCE)?.text)
        assertEquals("Sorszám", letters.word(60f, 458.5f, TOLERANCE)?.text)
        assertEquals("Forgalmi", letters.word(158f, 462.9f, TOLERANCE)?.text)
        assertEquals("rendszám", letters.word(160f, 465.7f, TOLERANCE)?.text)
        assertEquals("fajtája", letters.word(258f, 470f, TOLERANCE)?.text)
    }

    @Test
    fun theRightEdgeOfTheLastLetterOfALineStillFindsItsWord() {
        val letters = page {
            line("egy ketto a")
            line("harom negy", top = 114.4f)
        }

        assertEquals("a", letters.word(75.9f, 106f, TOLERANCE)?.text)
        assertEquals("ketto", letters.word(57.9f, 111.9f, TOLERANCE)?.text)
    }

    @Test
    fun aTouchOnASpaceFindsTheNearerWord() {
        val letters = page { line("az adó") }

        assertEquals("az", letters.word(22.5f, 106f, TOLERANCE)?.text)
        assertEquals("adó", letters.word(27.5f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun aTouchBesideTheLettersFindsTheNearestWordOnlyWithinTheTolerance() {
        val letters = page { line("adó") }

        assertEquals("adó", letters.word(20f, 113.5f, TOLERANCE)?.text)
        assertNull(letters.word(20f, 114.5f, TOLERANCE))
    }

    @Test
    fun aLetterUnderTheTouchWinsOverACloserLetterBesideIt() {
        val letters = page {
            line("felső", top = 88f)
            line("alsó", top = 100.5f)
        }

        assertEquals("alsó", letters.word(15f, 100.6f, TOLERANCE)?.text)
    }

    @Test
    fun overlappingLettersPreferTheLetterWithTheNearerCentre() {
        val letters = page {
            line("abc")
            line("xyz", left = 24f)
        }

        assertEquals("abc", letters.word(24.5f, 106f, TOLERANCE)?.text)
        assertEquals("xyz", letters.word(27f, 106f, TOLERANCE)?.text)
    }

    @Test
    fun theOwnAnchorsAreKeptWhenTheyCopyExactlyTheWord() {
        var snapped = false

        val (from, to) = middleWord().anchors({ _, _ -> "adó" }) {
            snapped = true
            null
        }

        assertEquals(28f, from.x, 0f)
        assertEquals(46f, to.x, 0f)
        assertFalse(snapped)
    }

    @Test
    fun theSnappedPointsAreUsedWhenOnlyTheyCopyTheWord() {
        val snapFrom = Point(1f, 2f)
        val snapTo = Point(3f, 4f)

        val (from, to) = middleWord().anchors({ a, _ -> if (a === snapFrom) "adó" else "adó alapja" }) { snapFrom to snapTo }

        assertSame(snapFrom, from)
        assertSame(snapTo, to)
    }

    @Test
    fun theOwnAnchorsStayWhenNeitherCopiesTheWord() {
        val (from, to) = middleWord().anchors({ _, _ -> "adó alapja" }) { Point(1f, 2f) to Point(3f, 4f) }

        assertEquals(28f, from.x, 0f)
        assertEquals(46f, to.x, 0f)
    }

    @Test
    fun theOwnAnchorsStayWhenSnappingFails() {
        val (from, to) = middleWord().anchors({ _, _ -> null }) { null }

        assertEquals(28f, from.x, 0f)
        assertEquals(46f, to.x, 0f)
    }

    private fun middleWord(): PdfTextWord = page { line("az adó alapja") }.word(37f, 106f, TOLERANCE)!!
}
