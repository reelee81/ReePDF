package hu.reelee81.reepdf.pdf

import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPageCostTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theStreamsOfAPageAreAddedUpWithoutCrossingToOtherPages() {
        open { pdf ->
            assertCost(10L + 100 + 1_000 + 5_000 + 7, PdfPageCost(pdf, objectBytes = 0).of(1))
        }
    }

    @Test
    fun inheritedResourcesAreCounted() {
        open { pdf ->
            assertCost(20L + 300 + 5_000, PdfPageCost(pdf, objectBytes = 0).of(2))
        }
    }

    @Test
    fun aSharedObjectCountsOnceInABatch() {
        open { pdf ->
            val cost = PdfPageCost(pdf, objectBytes = 0)
            cost.of(1)
            assertCost(20L + 300, cost.of(2))
        }
    }

    @Test
    fun theBudgetEndsTheBatch() {
        open { pdf ->
            assertEnd(1, PdfPageCost.batchEnd(pdf, 1, 2, 20, budget = 6_117, objectBytes = 0))
            assertEnd(2, PdfPageCost.batchEnd(pdf, 1, 2, 20, budget = 6_118, objectBytes = 0))
            assertEnd(1, PdfPageCost.batchEnd(pdf, 1, 2, 1, budget = Long.MAX_VALUE, objectBytes = 0))
            assertEnd(2, PdfPageCost.batchEnd(pdf, 2, 2, 20, budget = 1, objectBytes = 0))
        }
    }

    @Test
    fun theObjectsOfAPageAreCountedWithoutPageNodes() {
        open { pdf ->
            assertCost(59, objects(pdf, 1))
        }
    }

    @Test
    fun theObjectsOfInheritedResourcesAreCounted() {
        open { pdf ->
            assertCost(28, objects(pdf, 2))
        }
    }

    @Test
    fun aSharedObjectIsCountedOnceInABatch() {
        open { pdf ->
            val cost = PdfPageCost(pdf, objectBytes = 1)
            cost.of(1)
            assertCost(20L + 300 + 19, cost.of(2))
        }
    }

    @Test
    fun byDefaultEveryObjectCostsObjectBytes() {
        open { pdf ->
            assertCost(6_117L + 59 * PdfPageCost.OBJECT_BYTES, PdfPageCost(pdf).of(1))
        }
    }

    @Test
    fun pagesWithManyObjectsEndTheBatchEarly() {
        PdfDocument(PdfIo.reader(generated("objects.pdf", pages = 10, objectsPerPage = 2_000).absolutePath)).use { pdf ->
            assertEnd(10, PdfPageCost.batchEnd(pdf, 1, 10, 100, budget = 10L * 1024 * 1024, objectBytes = 0))
            assertEnd(4, PdfPageCost.batchEnd(pdf, 1, 10, 100, budget = 10L * 1024 * 1024))
        }
    }

    @Test
    fun aNormalDocumentStaysOneBatch() {
        PdfDocument(PdfIo.reader(generated("normal.pdf", pages = 100, objectsPerPage = 0).absolutePath)).use { pdf ->
            assertEnd(100, PdfPageCost.batchEnd(pdf, 1, 100, 100, budget = 256L * 1024 * 1024 / 8))
        }
    }

    private fun objects(pdf: PdfDocument, page: Int): Long =
        PdfPageCost(pdf, objectBytes = 1).of(page) - PdfPageCost(pdf, objectBytes = 0).of(page)

    private fun open(block: (PdfDocument) -> Unit) {
        PdfDocument(PdfIo.reader(fixture().absolutePath)).use(block)
    }

    private fun generated(name: String, pages: Int, objectsPerPage: Int): File {
        val file = temp.newFile(name)
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { doc ->
            val font = PdfFontFactory.createFont(StandardFonts.HELVETICA)
            repeat(pages) { index ->
                val page = doc.addNewPage(PageSize.A4)
                PdfCanvas(page).beginText().setFontAndSize(font, 12f).moveText(50.0, 700.0)
                    .showText("Page ${index + 1}").endText().release()
                if (objectsPerPage > 0) {
                    val array = PdfArray()
                    repeat(objectsPerPage) { i ->
                        val dict = PdfDictionary()
                        dict.put(PdfName.N, PdfNumber(i))
                        dict.put(PdfName.T, PdfString("x"))
                        array.add(dict.makeIndirect(doc))
                    }
                    page.pdfObject.put(PdfName("ReeObjects"), array)
                }
                page.flush()
            }
        }
        return file
    }

    private fun fixture(): File = rawPdf(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Resources << /XObject << /Inh 9 0 R /S 8 0 R >> >> >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 5 0 R " +
                "/Resources << /XObject << /A 6 0 R /S 8 0 R >> >> /Annots [11 0 R] /Tree 2 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 7 0 R >>",
            stream("", 10),
            stream("/Type /XObject /Subtype /Form /BBox [0 0 1 1] /Resources << /XObject << /I 10 0 R >> >>", 100, "13 0 R"),
            stream("", 20),
            stream("/Type /XObject /Subtype /Form /BBox [0 0 1 1]", 5_000),
            stream("/Type /XObject /Subtype /Form /BBox [0 0 1 1]", 300),
            stream("/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8", 1_000),
            "<< /Type /Annot /Subtype /Link /Rect [0 0 1 1] /P 3 0 R /Dest [4 0 R /Fit] /AP << /N 12 0 R >> >>",
            stream("/Type /XObject /Subtype /Form /BBox [0 0 1 1]", 7),
            "100"
        )
    )

    private fun stream(dict: String, length: Int, lengthEntry: String = "$length"): String =
        "<< $dict /Length $lengthEntry >>\nstream\n${" ".repeat(length)}\nendstream"

    private fun rawPdf(objects: List<String>): File {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.7\n")
        val offsets = objects.mapIndexed { index, body ->
            out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return temp.newFile("fixture.pdf").apply { writeBytes(out.toByteArray()) }
    }

    private fun assertCost(expected: Long, actual: Long) {
        assertEquals(expected, actual)
    }

    private fun assertEnd(expected: Int, actual: Int) {
        assertEquals(expected, actual)
    }
}
