package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

internal object TestPdfs {
    private val input: File? = projectFolder("work/00_test_PDFs")
    private val output: File = projectFolder("work/00_test_PDFs_work") ?: File("../work/00_test_PDFs_work")

    fun file(name: String): File {
        val found = input?.walkTopDown()?.firstOrNull { it.isFile && it.name == name }
        assumeTrue("$name is not present in work/00_test_PDFs", found != null)
        return requireNotNull(found)
    }

    fun folder(name: String): File {
        val found = input?.walkTopDown()?.firstOrNull { it.isDirectory && it.name == name }
        assumeTrue("The $name folder is not present in work/00_test_PDFs", found != null)
        return requireNotNull(found)
    }

    fun outputFolder(name: String): File = File(output, name).apply { mkdirs() }

    fun assertWorkFile(file: File) {
        val (crossReferenceStream, packedObjects) = layoutOf(file)
        assertTrue("${file.name} has no cross-reference stream", crossReferenceStream)
        assertEquals("objects of ${file.name} in object streams", 0, packedObjects)
    }

    fun assertPacked(file: File) {
        val (crossReferenceStream, packedObjects) = layoutOf(file)
        assertTrue("${file.name} has no cross-reference stream", crossReferenceStream)
        assertTrue("${file.name} has no object in an object stream", packedObjects > 0)
    }

    private fun layoutOf(file: File): Pair<Boolean, Int> {
        val reader = PdfReader(file)
        return PdfDocument(reader).use { pdf ->
            val packed = (1 until pdf.numberOfPdfObjects).count { number ->
                (pdf.getPdfObject(number)?.indirectReference?.objStreamNumber ?: 0) != 0
            }
            reader.hasXrefStm() to packed
        }
    }

    private fun projectFolder(name: String): File? =
        listOf(File("../$name"), File(name)).firstOrNull { it.isDirectory }
}
