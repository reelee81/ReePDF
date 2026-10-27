package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.NupConfig
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPageSizesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class RecordingProgress : PdfProgress {
        val events = mutableListOf<String>()

        override fun advance(units: Int) {
            events += "advance $units"
        }

        override fun idle() {
            events += "idle"
        }
    }

    private fun config(rows: Int, cols: Int, landscape: Boolean = false) =
        NupConfig(rows, cols, 0, isLandscape = landscape, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Test
    fun onlyASingleUpLayoutNeedsThePageSizes() {
        assertTrue(PdfPageSizes.isNeeded(config(1, 1)))
        assertTrue(PdfPageSizes.isNeeded(config(0, -3)))
        assertFalse(PdfPageSizes.isNeeded(config(1, 2)))
        assertFalse(PdfPageSizes.isNeeded(config(2, 1)))
    }

    @Test
    fun theFallbackIsTheA4PageOfTheLayout() {
        assertEquals(PageSize.A4.width to PageSize.A4.height, PdfPageSizes.fallback(config(1, 1)))
        assertEquals(PageSize.A4.height to PageSize.A4.width, PdfPageSizes.fallback(config(1, 1, landscape = true)))
        assertEquals(PageSize.A4.width to PageSize.A4.height, PdfPageSizes.fallback(config(2, 2)))
        assertEquals(PageSize.A4.height to PageSize.A4.width, PdfPageSizes.fallback(config(2, 2, landscape = true)))
    }

    @Test
    fun theSizesAreReadBatchByBatchPreferringTheCropBox() {
        val source = writeSource(
            listOf(Rectangle(100f, 200f), Rectangle(300f, 400f), Rectangle(500f, 600f), Rectangle(700f, 800f), Rectangle(90f, 80f)),
            cropBoxOf = mapOf(1 to Rectangle(10f, 10f, 150f, 250f))
        )
        val progress = RecordingProgress()

        val sizes = PdfPageSizes.read(source, 5, 2, 1f, 2f, progress)

        assertArrayEquals(floatArrayOf(100f, 150f, 500f, 700f, 90f), sizes.widths, 0f)
        assertArrayEquals(floatArrayOf(200f, 250f, 600f, 800f, 80f), sizes.heights, 0f)
        assertEquals(listOf("advance 2", "advance 2", "advance 1", "idle"), progress.events)
    }

    @Test
    fun pagesMissingFromTheSourceKeepTheFallback() {
        val source = writeSource(listOf(Rectangle(100f, 200f), Rectangle(300f, 400f)))
        val progress = RecordingProgress()

        val sizes = PdfPageSizes.read(source, 4, 3, 1f, 2f, progress)

        assertArrayEquals(floatArrayOf(100f, 300f, 1f, 1f), sizes.widths, 0f)
        assertArrayEquals(floatArrayOf(200f, 400f, 2f, 2f), sizes.heights, 0f)
        assertEquals(listOf("advance 3", "advance 1", "idle"), progress.events)
    }

    @Test
    fun theSizesAreReadAcrossThePartsOfTheSource() {
        val source = writeSource(
            listOf(Rectangle(100f, 200f), Rectangle(300f, 400f), Rectangle(500f, 600f), Rectangle(700f, 800f), Rectangle(90f, 80f)),
            cropBoxOf = mapOf(3 to Rectangle(10f, 10f, 150f, 250f)),
            partPages = 2
        )
        val progress = RecordingProgress()

        val sizes = PdfPageSizes.read(source, 6, 3, 1f, 2f, progress)

        assertEquals(3, source.layout.parts)
        assertArrayEquals(floatArrayOf(100f, 300f, 500f, 150f, 90f, 1f), sizes.widths, 0f)
        assertArrayEquals(floatArrayOf(200f, 400f, 600f, 250f, 80f, 2f), sizes.heights, 0f)
        assertEquals(listOf("advance 3", "advance 3", "idle"), progress.events)
    }

    @Test
    fun forThumbnailsReadsOnlyForASingleUpLayout() {
        val source = writeSource(listOf(Rectangle(100f, 200f), Rectangle(300f, 400f)))
        val stages = mutableListOf<Long>()
        val progress = RecordingProgress()

        val multiUp = PdfPageSizes.forThumbnails(source, 2, config(1, 2), 20) { stages += it; progress }
        val singleUp = PdfPageSizes.forThumbnails(source, 2, config(1, 1), 20) { stages += it; progress }

        assertNull(multiUp)
        assertArrayEquals(floatArrayOf(100f, 300f), singleUp!!.widths, 0f)
        assertArrayEquals(floatArrayOf(200f, 400f), singleUp.heights, 0f)
        assertEquals(listOf(2L), stages)
        assertEquals(listOf("advance 2", "idle"), progress.events)
    }

    private fun writeSource(pages: List<Rectangle>, cropBoxOf: Map<Int, Rectangle> = emptyMap(), partPages: Int = pages.size): PdfSourceParts {
        val dir = temp.newFolder()
        for ((part, chunk) in pages.indices.chunked(partPages).withIndex()) {
            PdfDocument(PdfWriter(File(dir, "${part + 1}".padStart(4, '0') + ".pdf"))).use { pdf ->
                for (index in chunk) {
                    val page = pdf.addNewPage(PageSize(pages[index]))
                    cropBoxOf[index]?.let { page.setCropBox(it) }
                }
            }
        }
        return PdfSourceParts.open(dir)
    }
}
