package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPageExtractTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun onlyTheRequestedPageIsCopiedWithItsSizeAndContent() {
        val source = writeSource(5)
        val out = File(temp.root, "single.pdf")

        PdfPageExtract.write(source, 3, out)

        PdfDocument(PdfReader(out)).use { single ->
            PdfDocument(PdfReader(source)).use { src ->
                assertEquals(1, single.numberOfPages)
                assertEquals(202f, single.firstPage.mediaBox.width, 0f)
                assertEquals(300f, single.firstPage.mediaBox.height, 0f)
                assertArrayEquals(src.getPage(3).contentBytes, single.firstPage.contentBytes)
            }
        }
        assertEquals(5, PdfDocument(PdfReader(source)).use { it.numberOfPages })
    }

    @Test
    fun anExistingOutputIsReplaced() {
        val source = writeSource(2)
        val out = File(temp.root, "single.pdf").apply { writeText("old page") }

        PdfPageExtract.write(source, 2, out)

        PdfDocument(PdfReader(out)).use { single ->
            assertEquals(1, single.numberOfPages)
            assertEquals(201f, single.firstPage.mediaBox.width, 0f)
        }
    }

    private fun writeSource(pages: Int): File {
        val file = File(temp.root, "source.pdf")
        PdfDocument(PdfWriter(file)).use { doc ->
            repeat(pages) { index ->
                val page = doc.addNewPage(PageSize(200f + index, 300f))
                PdfCanvas(page).rectangle(10.0, 10.0, 20.0 + index, 30.0).fill().release()
            }
        }
        return file
    }
}
