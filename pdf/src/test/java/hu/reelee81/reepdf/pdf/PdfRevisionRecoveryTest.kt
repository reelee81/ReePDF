package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfRevisionRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun aLeftoverTailAfterAValidFileIsCutOff() {
        val valid = makePdf("VALID REVISION")
        val input = temporary.newFile("overwritten.pdf").apply {
            writeBytes(valid + "\n9 0 obj\n<</Garbage true>>\nendobj\ntrailer\n<</Size 10/Root 9 0 R>>\nstartxref\n0\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        }
        var text = ""
        var recoveredBytes = ByteArray(0)
        val recovered = PdfRevisionRecovery.withEarlierRevision(input, temporary.root, {}, ::hasPages) { revision ->
            recoveredBytes = revision.readBytes()
            text = PdfDocument(PdfReader(revision)).use { PdfTextExtractor.getTextFromPage(it.getPage(1)) }
        }
        assertTrue(recovered)
        assertTrue(text.contains("VALID REVISION"))
        assertArrayEquals(valid.copyOf(recoveredBytes.size), recoveredBytes)
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("revision_") })
    }

    @Test
    fun aFileWithASingleRevisionIsNotTouched() {
        val input = temporary.newFile("single.pdf").apply { writeBytes(makePdf("ONLY")) }
        var called = false
        val recovered = PdfRevisionRecovery.withEarlierRevision(input, temporary.root, {}, ::hasPages) { called = true }
        assertFalse(recovered)
        assertFalse(called)
    }

    @Test
    fun theSuppliedOverwrittenFileOpensFromItsValidRevision() {
        val input = TestPdfs.file("issue9418.pdf")
        var pages = 0
        val recovered = PdfRevisionRecovery.withEarlierRevision(input, temporary.root, {}, ::hasPages) { revision ->
            pages = PdfDocument(PdfReader(revision)).use { it.numberOfPages }
        }
        assertTrue(recovered)
        assertEquals(1, pages)
    }

    private fun hasPages(file: File): Boolean = try {
        PdfDocument(PdfReader(file).setMemorySavingMode(true)).use { it.numberOfPages > 0 }
    } catch (_: Exception) {
        false
    }

    private fun makePdf(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        PdfDocument(PdfWriter(bytes)).use { pdf ->
            PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(20.0, 700.0).showText(text).endText()
        }
        return bytes.toByteArray()
    }
}
