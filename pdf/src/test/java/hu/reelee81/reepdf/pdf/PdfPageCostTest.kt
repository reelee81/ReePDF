package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfDocument
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
            assertCost(10L + 100 + 1_000 + 5_000 + 7, PdfPageCost(pdf).of(1))
        }
    }

    @Test
    fun inheritedResourcesAreCounted() {
        open { pdf ->
            assertCost(20L + 300 + 5_000, PdfPageCost(pdf).of(2))
        }
    }

    @Test
    fun aSharedObjectCountsOnceInABatch() {
        open { pdf ->
            val cost = PdfPageCost(pdf)
            cost.of(1)
            assertCost(20L + 300, cost.of(2))
        }
    }

    @Test
    fun theBudgetEndsTheBatch() {
        open { pdf ->
            assertEnd(1, PdfPageCost.batchEnd(pdf, 1, 2, 20, budget = 6_117))
            assertEnd(2, PdfPageCost.batchEnd(pdf, 1, 2, 20, budget = 6_118))
            assertEnd(1, PdfPageCost.batchEnd(pdf, 1, 2, 1, budget = Long.MAX_VALUE))
            assertEnd(2, PdfPageCost.batchEnd(pdf, 2, 2, 20, budget = 1))
        }
    }

    private fun open(block: (PdfDocument) -> Unit) {
        PdfDocument(PdfIo.reader(fixture().absolutePath)).use(block)
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
