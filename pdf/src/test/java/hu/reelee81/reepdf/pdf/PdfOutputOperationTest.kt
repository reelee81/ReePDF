package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOutputOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val password = "titok".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun theLayoutIsCopiedUnderTheCleanedNameAndOlderExportsGo() {
        writeLayout(3)
        File(dir, "older.pdf").writeText("old export")
        File(dir, "nup_temp_copy.pdf").writeText("copy")
        File(dir, "source_temp").mkdirs()
        File(dir, "notes.txt").writeText("text")

        val result = runBlocking { operation(baseName = "Profile_2: 1").execute {} }

        assertEquals(File(dir, "Profile_2_ 1.pdf"), result.file)
        assertArrayEquals(nup().readBytes(), result.file.readBytes())
        assertEquals(
            setOf("nup_temp.pdf", "nup_temp_copy.pdf", "source_temp", "notes.txt", "Profile_2_ 1.pdf"),
            dir.list()!!.toSet()
        )
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun aMissingLayoutFailsWithTheGivenMessage() {
        val failure = runCatching { runBlocking { operation().execute {} } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("layout missing", failure!!.message)
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun aPasswordEncryptsTheCopyAndKeepsEveryPage() {
        writeLayout(5)

        val result = runBlocking { operation(password = password).execute {} }

        assertTrue(runCatching { PdfDocument(PdfReader(result.file)).use { } }.exceptionOrNull() is BadPasswordException)
        assertEquals(pageContents(nup(), null), pageContents(result.file, password))
        assertEquals(pageWidths(nup(), null), pageWidths(result.file, password))
        assertFalse(File(dir, "output_encrypt.work").exists())
        assertEquals(5, PdfDocument(PdfReader(nup())).use { it.numberOfPages })
    }

    @Test
    fun theBatchesDoNotChangeTheEncryptedPages() {
        writeLayout(5)
        val expected = pageContents(nup(), null)

        for (batchSize in listOf(1, 2, 10)) {
            for (budget in listOf(0L, Long.MAX_VALUE)) {
                val result = runBlocking { operation(password = password, batchSize = batchSize, budget = budget).execute {} }
                assertEquals(expected, pageContents(result.file, password))
            }
        }
    }

    @Test
    fun aDiscardRemovesTheExport() {
        writeLayout(2)
        val operation = operation(password = password)
        val result = runBlocking { operation.execute {} }
        File(dir, "output_encrypt.work").writeText("left over")

        runBlocking { operation.discard() }

        assertFalse(result.file.exists())
        assertFalse(File(dir, "output_encrypt.work").exists())
        assertTrue(nup().exists())
    }

    @Test
    fun whenTheCopyCannotBeWrittenTheOperationFailsAndTheLayoutIsLeftAlone() {
        writeLayout(2)
        val before = nup().readBytes()
        File(dir, "Profile_2.pdf").mkdirs()
        val operation = operation(password = password)

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()
        runBlocking { operation.discard() }

        assertTrue(failure != null)
        assertArrayEquals(before, nup().readBytes())
        assertTrue(File(dir, "Profile_2.pdf").isDirectory)
        assertFalse(File(dir, "output_encrypt.work").exists())
    }

    @Test
    fun aNameOfAWorkFileFailsAndChangesNothing() {
        writeLayout(2)
        val before = nup().readBytes()
        File(dir, "older.pdf").writeText("old export")

        for (name in listOf("nup_temp", "nup_temp_copy")) {
            val failure = runCatching { runBlocking { operation(baseName = name, password = password).execute {} } }.exceptionOrNull()

            assertEquals("name reserved", failure?.message)
        }
        assertArrayEquals(before, nup().readBytes())
        assertEquals(setOf("nup_temp.pdf", "older.pdf"), dir.list()!!.toSet())
    }

    @Test
    fun theLongestAllowedNamesAreExportedWithAndWithoutPassword() {
        writeLayout(2)

        for (base in listOf("a".repeat(300), "ő".repeat(200))) {
            for (pwd in listOf(null, password)) {
                val result = runBlocking { operation(baseName = base, password = pwd).execute {} }

                assertTrue(result.file.name.toByteArray(Charsets.UTF_8).size <= 255)
                assertEquals(pageContents(nup(), null), pageContents(result.file, pwd))
                assertFalse(File(dir, "output_encrypt.work").exists())
            }
        }
    }

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun writeLayout(pages: Int) {
        PdfDocument(PdfWriter(nup())).use { pdf ->
            repeat(pages) { index ->
                val page = pdf.addNewPage(PageSize(200f + index, 300f))
                PdfCanvas(page).rectangle(10.0 + index, 10.0, 100.0, 100.0).fill().release()
            }
        }
    }

    private fun reader(file: File, password: ByteArray?): PdfReader =
        PdfReader(file.absolutePath, ReaderProperties().apply { if (password != null) setPassword(password) })

    private fun pageContents(file: File, password: ByteArray?): List<String> =
        PdfDocument(reader(file, password)).use { pdf ->
            (1..pdf.numberOfPages).map { String(pdf.getPage(it).contentBytes, Charsets.ISO_8859_1) }
        }

    private fun pageWidths(file: File, password: ByteArray?): List<Float> =
        PdfDocument(reader(file, password)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.width } }

    private fun operation(
        baseName: String = "Profile_2",
        password: ByteArray? = null,
        batchSize: Int = 2,
        budget: Long = Long.MAX_VALUE
    ) = PdfOutputOperation(
        files = PdfOutputOperation.Files(dir, nup(), setOf("nup_temp.pdf", "source_temp", "source_temp_copy", "nup_temp_copy.pdf")),
        saveSelection = { events += "selection saved" },
        input = PdfOutputOperation.Input(
            baseName = baseName,
            password = password,
            batchSize = batchSize,
            messages = PdfOutputOperation.Messages("layout missing", "name reserved", "replace failed", "rename failed"),
            budget = budget
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L)
    )
}
