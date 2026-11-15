package hu.reelee81.reepdf.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageMarksTest {
    private fun line(id: String, x: Float, y: Float) =
        PdfMark(id, PdfMarkKind.PEN, 0x112233, 2f, PdfMarkPath.Builder().moveTo(x, y).lineTo(x + 10f, y).build())

    @Test
    fun appendedMarksAreTurnedIntoPageSpace() {
        val toPage = doubleArrayOf(1.0, 0.0, 0.0, -1.0, -5.0, 100.0)
        val data = PdfPageEditData(
            toPage,
            emptyList(),
            listOf(PdfPageEditData.Stream(12, PdfMarkContent.write(listOf(line("a", 10f, 20f)), null))),
            emptyList()
        )

        val marks = data.read()

        val mark = marks.marks.single()
        assertEquals(12, mark.stream)
        assertEquals("a", mark.mark.id)
        assertArrayEquals(floatArrayOf(5f, 80f, 15f, 80f), mark.mark.path.points, 0.001f)
        assertArrayEquals(intArrayOf(12), marks.streams)
        assertSame(toPage, marks.toPage)
    }

    @Test
    fun aLayerMarkIsShownInEveryCellOfItsSource() {
        val inverse = PdfEditProjection.inverse(doubleArrayOf(0.5, 0.0, 0.0, 0.5, 100.0, 0.0))!!
        val layer = "q ".toByteArray() + inverse.joinToString(" ").toByteArray() + " cm\n".toByteArray() +
            PdfMarkContent.write(listOf(line("b", 150f, 50f)), null) + "Q\n".toByteArray()
        val cells = PdfPageEditData.cells(floatArrayOf(0f, 0.5f, 0f, 0f, 0.5f, 100f, 0f, 3f, 0.5f, 0f, 0f, 0.5f, 400f, 0f, 0f, 0.5f, 0f, 0f, 0.5f, 100f, 300f))
        val data = PdfPageEditData(
            PdfMatrix.IDENTITY,
            cells,
            emptyList(),
            listOf(PdfPageEditData.Stream(40, layer, sourceIndex = 0), PdfPageEditData.Stream(41, "q /Fm0 Do Q".toByteArray(), sourceIndex = 3))
        )

        val marks = data.read()

        assertEquals(3, cells.size)
        assertEquals(listOf("b", "b"), marks.marks.map { it.mark.id })
        assertEquals(listOf(40, 40), marks.marks.map { it.stream })
        assertArrayEquals(floatArrayOf(150f, 50f, 160f, 50f), marks.marks[0].mark.path.points, 0.001f)
        assertArrayEquals(floatArrayOf(150f, 350f, 160f, 350f), marks.marks[1].mark.path.points, 0.001f)
        assertEquals(2f, marks.marks[0].mark.width, 0.001f)
        assertArrayEquals(intArrayOf(40, 41), marks.streams)
    }

    @Test
    fun aStreamEditKeepsBlanksOrDropsMarks() {
        val content = "q 1 0 0 1 0 0 cm\n".toByteArray() + PdfMarkContent.write(listOf(line("a", 0f, 0f), line("b", 1f, 1f)), null) + "Q".toByteArray()

        assertEquals(listOf("b"), PdfMarkContent.read(PdfStreamEdit(false, setOf("a")).applyTo(content)).map { it.mark.id })
        assertEquals(0, PdfStreamEdit(false, setOf("a", "b")).applyTo(content).size)
        assertEquals(0, PdfStreamEdit.BLANK.applyTo(content).size)
        assertArrayEquals(content, PdfStreamEdit.ORIGINAL.applyTo(content))
        assertTrue(PdfStreamEdit.ORIGINAL.isOriginal)
    }
}
