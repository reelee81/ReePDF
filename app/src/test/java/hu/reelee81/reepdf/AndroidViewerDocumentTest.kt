package hu.reelee81.reepdf

import hu.reelee81.reepdf.AndroidViewerDocument.Companion.toPdf
import hu.reelee81.reepdf.AndroidViewerDocument.Companion.toViewer
import hu.reelee81.reepdf.pdf.PdfEditProjection
import hu.reelee81.reepdf.pdf.PdfMarkKind
import hu.reelee81.reepdf.pdf.PdfMarkText
import hu.reelee81.reepdf.viewer.ViewerMark
import hu.reelee81.reepdf.viewer.ViewerMarkKind
import hu.reelee81.reepdf.viewer.ViewerMarkPath
import hu.reelee81.reepdf.viewer.ViewerMarkText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidViewerDocumentTest {
    private val box = ViewerMarkPath(
        byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.CLOSE),
        floatArrayOf(40f, 60f, 120f, 60f, 120f, 74f, 40f, 74f)
    )

    @Test
    fun aTypedTextGoesToTheFileAndComesBackUnchanged() {
        val text = ViewerMarkText.upright("Árvíztűrő\ntükörfúrógép", 700, 12f, 40f, 60f)
        val mark = ViewerMark(0, ViewerMarkKind.TYPED, 0x202FB0, 0f, box, text)
        for (toPage in listOf(doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0), doubleArrayOf(0.0, 0.5, 0.5, 0.0, 10.0, 20.0))) {
            val toUser = requireNotNull(PdfEditProjection.inverse(toPage))

            val written = mark.toPdf(toUser)
            val back = requireNotNull(written.transformed(toPage).text?.toViewer())

            assertEquals(PdfMarkKind.TYPED, written.kind)
            assertEquals(0x202FB0, written.color)
            assertEquals(text.text, back.text)
            assertEquals(700, back.weight)
            assertEquals(12f, back.size, 1e-4f)
            assertArrayEquals(text.matrix, back.matrix, 1e-4f)
        }
    }

    @Test
    fun theFileSizeOfATextFollowsTheScaleOfThePage() {
        val text = ViewerMarkText.upright("Half", 400, 12f, 10f, 10f)
        val written = ViewerMark(0, ViewerMarkKind.TYPED, 0, 0f, box, text).toPdf(doubleArrayOf(2.0, 0.0, 0.0, -2.0, 0.0, 800.0))

        assertEquals(24f, requireNotNull(written.text).size, 1e-4f)
    }

    @Test
    fun aDrawnMarkCarriesNoTextAndAnUnusableTextStaysUneditable() {
        val pen = ViewerMark(0, ViewerMarkKind.PEN, 0, 2f, box).toPdf(doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 842.0))
        assertNull(pen.text)

        val far = PdfMarkText("Far", 400, 12f, doubleArrayOf(1.0, 0.0, 0.0, -1.0, 1e300, 10.0))
        assertNull(far.toViewer())
    }
}
