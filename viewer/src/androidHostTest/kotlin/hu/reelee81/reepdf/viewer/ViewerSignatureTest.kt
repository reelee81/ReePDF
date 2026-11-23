package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerSignatureTest {
    private val signature = ViewerSignature(
        2f,
        listOf(floatArrayOf(0.1f, 0.2f, 0.12345f, 0.39999f, 1f, 0f), floatArrayOf(0.5f, 0.25f))
    )

    @Test
    fun aSignatureIsWrittenAsTextWithFourDecimals() {
        assertEquals(
            "ReePDF signature 1\nwidth 2\n0.1 0.2 0.1235 0.4 1 0\n0.5 0.25\n",
            ViewerSignatureFormat.encode(signature)
        )
    }

    @Test
    fun aWrittenSignatureIsReadBackAndWrittenAgainTheSame() {
        val text = ViewerSignatureFormat.encode(signature)

        val read = ViewerSignatureFormat.decode(text)!!

        assertEquals(2f, read.width, 0f)
        assertEquals(2, read.strokes.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.1235f, 0.4f, 1f, 0f), read.strokes[0], 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), read.strokes[1], 0f)
        assertEquals(text, ViewerSignatureFormat.encode(read))
    }

    @Test
    fun windowsLineEndsAndExtraSpacesAreAccepted() {
        val read = ViewerSignatureFormat.decode("ReePDF signature 1\r\nwidth 2.5 \r\n 0.1  0.2 0.3 0.1\r\n")!!

        assertEquals(2.5f, read.width, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.1f), read.strokes.single(), 0f)
    }

    @Test
    fun theSignatureHasItsOwnHalfPointWidthsAndStartsWithOne() {
        assertArrayEquals(floatArrayOf(0.5f, 1f, 1.5f, 2f, 2.5f), ViewerSignature.WIDTHS, 0f)
        assertEquals(1f, ViewerSignature.WIDTHS[ViewerSignature.DEFAULT_WIDTH], 0f)
        for (width in ViewerSignature.WIDTHS) {
            val read = ViewerSignatureFormat.decode(ViewerSignatureFormat.encode(signature.withWidth(width)))!!

            assertEquals(width, read.width, 0f)
        }
    }

    @Test
    fun brokenTextIsNotASignature() {
        val broken = listOf(
            "",
            "ReePDF signature 1\nwidth 2\n",
            "ReePDF signature 2\nwidth 2\n0 0\n",
            "ReePDF signature 1\nheight 2\n0 0\n",
            "ReePDF signature 1\nwidth 10\n0 0\n",
            "ReePDF signature 1\nwidth 1.25\n0 0\n",
            "ReePDF signature 1\nwidth 3\n0 0\n",
            "ReePDF signature 1\nwidth x\n0 0\n",
            "ReePDF signature 1\nwidth 2\n0 0 0.5\n",
            "ReePDF signature 1\nwidth 2\n\n",
            "ReePDF signature 1\nwidth 2\n0 0 a 0\n",
            "ReePDF signature 1\nwidth 2\n1.0001 0\n",
            "ReePDF signature 1\nwidth 2\n0 0.4001\n",
            "ReePDF signature 1\nwidth 2\n-0.1 0\n",
            "ReePDF signature 1\nwidth 2\nNaN 0\n",
            "ReePDF signature 1\nwidth 2\n0 Infinity\n"
        )

        for (text in broken) assertNull(text, ViewerSignatureFormat.decode(text))
    }

    @Test
    fun aSignatureMayNotGrowBeyondItsLimits() {
        val strokes = "0 0\n".repeat(ViewerSignature.MAX_STROKES)
        val points = "0 0 ".repeat(ViewerSignature.MAX_POINTS / 2).trim()

        assertNotNull(ViewerSignatureFormat.decode("ReePDF signature 1\nwidth 2\n$strokes"))
        assertNull(ViewerSignatureFormat.decode("ReePDF signature 1\nwidth 2\n${strokes}0 0\n"))
        assertNotNull(ViewerSignatureFormat.decode("ReePDF signature 1\nwidth 2\n$points\n$points\n"))
        assertNull(ViewerSignatureFormat.decode("ReePDF signature 1\nwidth 2\n$points\n$points 0 0\n"))
    }

    @Test
    fun aHalfPointWidthIsWrittenWithTheLanguageDecimalSeparator() {
        assertEquals(listOf("0,5", "1", "1,5", "2", "2,5"), ViewerSignature.WIDTHS.map { widthText(it, ",") })
        assertEquals("1.5", widthText(1.5f, "."))
        assertEquals("10", widthText(10f, ","))
        assertEquals("ReePDF signature 1\nwidth 1.5\n0 0\n", ViewerSignatureFormat.encode(ViewerSignature(1.5f, listOf(floatArrayOf(0f, 0f)))))
    }
}
