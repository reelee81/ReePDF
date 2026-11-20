package hu.reelee81.reepdf.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun everyKindIsWrittenWithItsOwnPaint() {
        val content = String(PdfMarkContent.write(listOf(pen, highlighter, text), "GS1"), Charsets.ISO_8859_1)

        assertEquals(
            "/ReePDF <</Id(pen1)/K/Pen>> BDC\nq\n0.1255 0.1843 0.6902 RG\n2 w 1 J 1 j\n" +
                "10 20 m\n15.5 25.25 30.125 40 50 60.5 c\n-3.5 0.001 l\nS\nQ\nEMC\n" +
                "/ReePDF <</Id(hl2)/K/Hl>> BDC\nq\n/GS1 gs\n1 0.9294 0.2706 RG\n24 w 1 J 1 j\n" +
                "100 100 m\n100 100 l\nS\nQ\nEMC\n" +
                "/ReePDF <</Id(text3)/K/Text>> BDC\nq\n/GS1 gs\n0.6471 0.1647 0.1647 rg\n" +
                "1 2 m\n11 2 l\n11 7 l\n1 7 l\nh\nf\nQ\nEMC\n",
            content
        )
    }

    @Test
    fun anUnderlineAndAStrikethroughAreOpaqueFillsReadBackAsTheirKind() {
        val underline = PdfMark(
            "u4", PdfMarkKind.UNDERLINE, 0x35C369, 0f,
            PdfMarkPath.Builder().moveTo(1f, 10f).lineTo(11f, 10f).lineTo(11f, 10.5f).lineTo(1f, 10.5f).close().build()
        )
        val strikethrough = PdfMark(
            "s5", PdfMarkKind.STRIKETHROUGH, 0xDD0000, 0f,
            PdfMarkPath.Builder().moveTo(1f, 20f).lineTo(11f, 22f).lineTo(11f, 23f).lineTo(1f, 21f).close().build()
        )

        val content = PdfMarkContent.write(listOf(underline, strikethrough), null)
        val read = PdfMarkContent.read(content)

        assertEquals(
            "/ReePDF <</Id(u4)/K/Under>> BDC\nq\n0.2078 0.7647 0.4118 rg\n1 10 m\n11 10 l\n11 10.5 l\n1 10.5 l\nh\nf\nQ\nEMC\n" +
                "/ReePDF <</Id(s5)/K/Strike>> BDC\nq\n0.8667 0 0 rg\n1 20 m\n11 22 l\n11 23 l\n1 21 l\nh\nf\nQ\nEMC\n",
            String(content, Charsets.ISO_8859_1)
        )
        assertSameMark(underline, read[0].mark)
        assertSameMark(strikethrough, read[1].mark)
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
    fun aPieceThatOnlyClipsIsBlank() {
        assertTrue(PdfMarkContent.isBlank("q 2 0 0 2 1 1 cm\n10 20 30 40 re W n\nQ\n".toByteArray()))
        assertTrue(PdfMarkContent.isBlank("q 0 0 m 10 0 l 10 10 l h W* n Q".toByteArray()))
        assertFalse(PdfMarkContent.isBlank("q 10 20 30 40 re W n 0 0 10 10 re f Q".toByteArray()))
        assertFalse(PdfMarkContent.isBlank("0 0 m 10 10 l S".toByteArray()))
    }

    @Test
    fun otherContentIsWhatStandsBesideTheMarks() {
        val marks = PdfMarkContent.write(listOf(pen, highlighter), "A")
        val clipped = "q 1 0 0 1 5 5 cm\n0 0 50 50 re W n\n".toByteArray() + marks + "Q\n".toByteArray()
        val foreign = marks + "q /Fm0 Do Q\n".toByteArray()

        assertFalse(PdfMarkContent.hasOtherContent(marks, PdfMarkContent.read(marks)))
        assertFalse(PdfMarkContent.hasOtherContent(clipped, PdfMarkContent.read(clipped)))
        assertTrue(PdfMarkContent.hasOtherContent(foreign, PdfMarkContent.read(foreign)))
        assertTrue(PdfMarkContent.hasOtherContent("q /Fm0 Do Q".toByteArray(), emptyList()))
        assertFalse(PdfMarkContent.hasOtherContent(ByteArray(0), emptyList()))
    }

    @Test
    fun aClipBeforeAMarkIsReadInTheSpaceAroundItAndEndsWithItsState() {
        val clipped = "q 2 0 0 2 10 10 cm\n0 0 50 40 re W n\n".toByteArray() + PdfMarkContent.write(listOf(pen), null) +
            "Q\n".toByteArray() + PdfMarkContent.write(listOf(text), "A")

        val stored = PdfMarkContent.read(clipped)

        assertArrayEquals(doubleArrayOf(10.0, 10.0, 110.0, 90.0), stored[0].clip!!, 1e-9)
        assertNull(stored[1].clip)
    }

    @Test
    fun nestedClipsNarrowEachOther() {
        val content = "q 0 0 100 100 re W n\nq 50 20 m 150 20 l 150 80 l h W* n\n".toByteArray() +
            PdfMarkContent.write(listOf(pen), null) + "Q Q\n".toByteArray()

        assertArrayEquals(doubleArrayOf(50.0, 20.0, 100.0, 80.0), PdfMarkContent.read(content).single().clip!!, 1e-9)
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
