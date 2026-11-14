package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.PdfCrops
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfPageViewTest {
    private val left = 60f
    private val bottom = 120f
    private val right = 560f
    private val top = 720f

    @Test
    fun theUnturnedViewPutsTheBoxTopLeftAtTheOrigin() {
        val view = PdfPageView(left, bottom, right, top, 0)
        val matrix = view.matrix(2f)
        assertPoint(0f, 0f, matrix, left, top)
        assertPoint(1000f, 1200f, matrix, right, bottom)
        assertEquals(500f, view.width, 0f)
        assertEquals(600f, view.height, 0f)
    }

    @Test
    fun aQuarterTurnTurnsTheBoxCounterclockwise() {
        val view = PdfPageView(left, bottom, right, top, 1)
        val matrix = view.matrix(1f)
        assertEquals(600f, view.width, 0f)
        assertEquals(500f, view.height, 0f)
        assertPoint(0f, 0f, matrix, right, top)
        assertPoint(0f, 500f, matrix, left, top)
        assertPoint(600f, 500f, matrix, left, bottom)
        assertPoint(600f, 0f, matrix, right, bottom)
    }

    @Test
    fun turnsAreTakenModuloFour() {
        assertEquals(3, PdfPageView(left, bottom, right, top, -1).quarterTurns)
        assertEquals(1, PdfPageView(left, bottom, right, top, 5).quarterTurns)
        assertArrayEquals(
            PdfPageView(left, bottom, right, top, 2).matrix(1.5f),
            PdfPageView(left, bottom, right, top, -2).matrix(1.5f),
            0f
        )
    }

    @Test
    fun everyTurnShowsTheCropWhereTheCropMathPutsIt() {
        val crop = PdfCropRect(0.1f, 0.2f, 0.45f, 0.9f)
        val width = right - left
        val height = top - bottom
        for (turns in 0..3) {
            val view = PdfPageView(left, bottom, right, top, turns)
            val matrix = view.matrix(1f)
            val first = transform(matrix, left + width * crop.left, bottom + height * crop.bottom)
            val second = transform(matrix, left + width * crop.right, bottom + height * crop.top)
            val shown = floatArrayOf(
                minOf(first[0], second[0]) / view.width,
                minOf(first[1], second[1]) / view.height,
                maxOf(first[0], second[0]) / view.width,
                maxOf(first[1], second[1]) / view.height
            )
            assertArrayEquals("turns $turns", PdfCrops.toDisplay(crop, turns), shown, 1e-5f)
        }
    }

    @Test
    fun anEmptyOrBrokenBoxIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { PdfPageView(10f, 10f, 10f, 20f, 0) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageView(10f, 30f, 20f, 20f, 0) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageView(Float.NaN, 0f, 20f, 20f, 0) }
    }

    @Test
    fun readingUsesTheVisibleBoxOfThePage() {
        val file = pdfWithBoxes()
        try {
            val view = PdfPageView.read(file, 1, 3)
            assertEquals(60f, view.left, 0f)
            assertEquals(120f, view.bottom, 0f)
            assertEquals(610f, view.right, 0f)
            assertEquals(720f, view.top, 0f)
            assertEquals(3, view.quarterTurns)
            val plain = PdfPageView.read(file, 0, 0)
            assertArrayEquals(floatArrayOf(0f, 0f, 595f, 842f), floatArrayOf(plain.left, plain.bottom, plain.right, plain.top), 1e-3f)
            assertThrows(IllegalArgumentException::class.java) { PdfPageView.read(file, 2, 0) }
        } finally {
            file.delete()
        }
    }

    private fun pdfWithBoxes(): File {
        val file = Files.createTempFile("page-view", ".pdf").toFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            pdf.addNewPage(PageSize.A4)
            val page = pdf.addNewPage(PageSize(Rectangle(10f, 20f, 600f, 800f)))
            page.setCropBox(Rectangle(60f, 120f, 600f, 600f))
            page.setRotation(90)
        }
        return file
    }

    private fun assertPoint(x: Float, y: Float, matrix: FloatArray, userX: Float, userY: Float) {
        val point = transform(matrix, userX, userY)
        assertEquals(x, point[0], 1e-3f)
        assertEquals(y, point[1], 1e-3f)
    }

    private fun transform(matrix: FloatArray, x: Float, y: Float) = floatArrayOf(
        matrix[0] * x + matrix[2] * y + matrix[4],
        matrix[1] * x + matrix[3] * y + matrix[5]
    )
}
