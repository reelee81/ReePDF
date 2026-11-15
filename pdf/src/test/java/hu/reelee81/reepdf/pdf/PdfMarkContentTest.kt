package hu.reelee81.reepdf.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkContentTest {
    private val pen = PdfMark(
        "pen1", PdfMarkKind.PEN, 0x202FB0, 2f,
        PdfMarkPath.Builder().moveTo(10f, 20f).curveTo(15.5f, 25.25f, 30.125f, 40f, 50f, 60.5f).lineTo(-3.5f, 0.001f).build()
    )
    private val highlighter = PdfMark(
        "hl2", PdfMarkKind.HIGHLIGHTER, 0xFFED45, 24f,
        PdfMarkPath.Builder().moveTo(100f, 100f).lineTo(100f, 100f).build()
    )
    private val text = PdfMark(
        "text3", PdfMarkKind.TEXT_HIGHLIGHT, 0xA52A2A, 0f,
        PdfMarkPath.Builder().moveTo(1f, 2f).lineTo(11f, 2f).lineTo(11f, 7f).lineTo(1f, 7f).close().build()
    )

    @Test
    fun writtenMarksAreReadBackUnchanged() {
        val content = PdfMarkContent.write(listOf(pen, highlighter, text), "GS1")

        val read = PdfMarkContent.read(content)

        assertEquals(listOf("pen1", "hl2", "text3"), read.map { it.mark.id })
        for ((stored, expected) in read.zip(listOf(pen, highlighter, text))) assertSameMark(expected, stored.mark)
        assertEquals("/ReePDF <</Id(pen1)/K/Pen>> BDC", String(content, read[0].start, 31, Charsets.ISO_8859_1))
        assertEquals("EMC", String(content, read[2].end - 3, 3, Charsets.ISO_8859_1))
        assertTrue(String(content, Charsets.ISO_8859_1).contains("/GS1 gs"))
    }

    @Test
    fun aMarkIsReadInTheSpaceOfTheTransformsAroundIt() {
        val mark = PdfMarkContent.write(listOf(pen), null)
        val content = "q 2 0 0 2 10 20 cm\n".toByteArray() + mark + "Q\n".toByteArray()

        val read = PdfMarkContent.read(content).single().mark

        val expected = pen.transformed(doubleArrayOf(2.0, 0.0, 0.0, 2.0, 10.0, 20.0))
        assertSameMark(expected, read)
        assertEquals(4f, read.width, 0f)
    }

    @Test
    fun foreignContentIsSkippedAndOnlyOwnMarksAreFound() {
        val own = PdfMarkContent.write(listOf(highlighter), "NUE1")
        val before = "BT /F1 12 Tf (a\\)b(c) EMC) Tj ET\n/OC /P0 BDC 0 0 m 5 5 l S EMC\n" +
            "BI /W 2 /H 1 /BPC 8 /CS /G ID \u0000BDC EMCÿ EI\n<</Not /Ours>> pop\n"
        val after = "/ReePDF <</Id(BAD)>> BDC 0 0 m 1 1 l S EMC\n/ReePDF BMC 0 0 m S EMC\n"
        val content = before.toByteArray(Charsets.ISO_8859_1) + own + after.toByteArray(Charsets.ISO_8859_1)

        val read = PdfMarkContent.read(content)

        assertEquals(listOf("hl2"), read.map { it.mark.id })
        assertSameMark(highlighter, read.single().mark)
        assertEquals(before.length, read.single().start)
    }

    @Test
    fun nestedMarkedContentInsideAMarkKeepsItTogether() {
        val content = ("/ReePDF <</Id(n1)/K/Pen>> BDC q 0 0 0 RG 3 w /Span BMC 1 1 m 2 2 l EMC S /P <</MCID 0>> BDC EMC Q EMC\n" +
            "0 0 m 9 9 l S").toByteArray()

        val read = PdfMarkContent.read(content).single()

        assertEquals("n1", read.mark.id)
        assertEquals(3f, read.mark.width, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 2f, 2f), read.mark.path.points, 0f)
        assertEquals(content.size - "\n0 0 m 9 9 l S".length, read.end)
    }

    @Test
    fun aMarkWithoutAKindIsJudgedByHowItIsPainted() {
        val content = ("/ReePDF <</Id(a)>> BDC q /G gs 1 0 0 RG 5 w 0 0 m 1 1 l S Q EMC " +
            "/ReePDF <</Id(b)>> BDC q 0 0 1 rg 0 0 2 2 re f Q EMC " +
            "/ReePDF <</Id(c)>> BDC q 0 1 0 RG 0 0 m 1 1 l S Q EMC").toByteArray()

        val read = PdfMarkContent.read(content).map { it.mark }

        assertEquals(listOf(PdfMarkKind.HIGHLIGHTER, PdfMarkKind.TEXT_HIGHLIGHT, PdfMarkKind.PEN), read.map { it.kind })
        assertEquals(listOf(0xFF0000, 0x0000FF, 0x00FF00), read.map { it.color })
        assertArrayEquals(byteArrayOf(0, 1, 1, 1, 3), read[1].path.verbs)
    }

    @Test
    fun everyColorValueSurvivesTheRoundTrip() {
        for (value in 0..255) {
            val color = (value shl 16) or ((255 - value) shl 8) or (value / 2)
            val mark = PdfMark("c$value", PdfMarkKind.PEN, color, 1f, PdfMarkPath.Builder().moveTo(0f, 0f).lineTo(1f, 1f).build())

            assertEquals(color, PdfMarkContent.read(PdfMarkContent.write(listOf(mark), null)).single().mark.color)
        }
    }

    @Test
    fun withoutRemovesOnlyTheGivenMarks() {
        val content = "q 1 0 0 1 5 5 cm\n".toByteArray() + PdfMarkContent.write(listOf(pen, highlighter, text), "A") + "Q\n".toByteArray()

        val kept = PdfMarkContent.without(content, setOf("hl2"))
        val none = PdfMarkContent.without(content, setOf("pen1", "hl2", "text3"))

        assertEquals(listOf("pen1", "text3"), PdfMarkContent.read(kept).map { it.mark.id })
        assertSame(content, PdfMarkContent.without(content, setOf("missing")))
        assertTrue(PdfMarkContent.isBlank(none))
        assertFalse(PdfMarkContent.isBlank(kept))
    }

    @Test
    fun onlyContentWithoutDrawingIsBlank() {
        assertTrue(PdfMarkContent.isBlank(ByteArray(0)))
        assertTrue(PdfMarkContent.isBlank("q\n1 0 0 1 0 0 cm\n\nQ\nq 2 0 0 2 1 1 cm Q % note\n".toByteArray()))
        assertFalse(PdfMarkContent.isBlank("q /Fm0 Do Q".toByteArray()))
        assertFalse(PdfMarkContent.isBlank("BI /W 1 /H 1 ID x EI".toByteArray()))
    }

    @Test
    fun numbersAreWrittenWithoutExponentsOrNegativeZero() {
        val mark = PdfMark(
            "num", PdfMarkKind.PEN, 0, 0.0004f,
            PdfMarkPath.Builder().moveTo(-0.0004f, 12345678f).lineTo(0.1f, -2.5f).build()
        )

        val text = String(PdfMarkContent.write(listOf(mark), null), Charsets.ISO_8859_1)

        assertTrue(text, text.contains("\n0 w 1 J 1 j\n0 12345678 m\n0.1 -2.5 l\n"))
        assertTrue(text, text.contains("\n0 0 0 RG\n"))
    }

    private fun assertSameMark(expected: PdfMark, actual: PdfMark) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.color, actual.color)
        assertEquals(expected.width, actual.width, 0.001f)
        assertArrayEquals(expected.path.verbs, actual.path.verbs)
        assertArrayEquals(expected.path.points, actual.path.points, 0.001f)
    }

}
