package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfImportOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val crop = PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f)
    private val config = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val singleUp = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun aPdfIsAppendedAndTheLayoutIsRebuiltAfterTheBackups() {
        writeMarked(source(), 3, firstWidth = 200f)
        nup().writeText("old layout")
        val imported = writeMarked(File(dir, "1_in.pdf"), 2, firstWidth = 300f)
        val crops = mapOf(1 to crop)

        val result = runBlocking {
            operation(
                listOf(PdfImportOperation.Source(imported, null, null)),
                crops = crops,
                rotation = intArrayOf(1, 2, 3),
                position = intArrayOf(9)
            ).execute {}
        }

        assertEquals(listOf(200f, 201f, 202f, 300f, 301f), pageWidths(source()))
        assertArrayEquals(intArrayOf(1, 2, 3, 0, 0), result.rotation)
        assertArrayEquals(intArrayOf(9, 5, 5, 5, 5), result.position)
        assertEquals(crops, result.crops)
        assertEquals(config, result.config)
        assertEquals(3, pageCount(nup()))
        assertEquals(3, pageCount(File(dir, "source_temp_copy/0001.pdf")))
        assertEquals("old layout", File(dir, "nup_temp_copy.pdf").readText())
        assertFalse(imported.exists())
        assertFalse(File(dir, "nup_temp.pdf.part").exists())
        assertNull(result.pageSizes)
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun theSelectedSourcePagesAreRegroupedForTheNewLayout() {
        writeMarked(source(), 4, firstWidth = 200f)
        val imported = writeMarked(File(dir, "1_in.pdf"), 1, firstWidth = 300f)

        val result = runBlocking {
            operation(
                listOf(PdfImportOperation.Source(imported, null, null)),
                config = singleUp,
                oldPerGroup = 2,
                selected = listOf(1)
            ).execute {}
        }

        assertEquals(listOf(2, 3), result.selected.sorted())
        assertEquals(5, pageCount(nup()))
        val sizes = result.pageSizes!!
        assertArrayEquals(floatArrayOf(200f, 201f, 202f, 203f, 300f), sizes.widths, 0f)
    }

    @Test
    fun theFirstImportCreatesTheSourceWithoutSelection() {
        val imported = writeMarked(File(dir, "1_in.pdf"), 3, firstWidth = 300f)

        val result = runBlocking {
            operation(listOf(PdfImportOperation.Source(imported, null, null)), selected = listOf(0)).execute {}
        }

        assertEquals(listOf(300f, 301f, 302f), pageWidths(source()))
        assertArrayEquals(intArrayOf(0, 0, 0), result.rotation)
        assertArrayEquals(intArrayOf(5, 5, 5), result.position)
        assertEquals(emptyList<Int>(), result.selected)
        assertEquals(2, pageCount(nup()))
        assertFalse(File(dir, "source_temp_copy").exists())
    }

    @Test
    fun severalFilesAreAppendedInOrderForEveryBatchSize() {
        for (batchSize in listOf(1, 2, 10)) {
            writeMarked(source(), 1, firstWidth = 200f)
            val first = writeMarked(File(dir, "1_a.pdf"), 2, firstWidth = 300f)
            val second = writeMarked(File(dir, "2_b.pdf"), 3, firstWidth = 400f)

            runBlocking {
                operation(
                    listOf(PdfImportOperation.Source(first, null, null), PdfImportOperation.Source(second, null, null)),
                    batchSize = batchSize
                ).execute {}
            }

            assertEquals(listOf(200f, 300f, 301f, 400f, 401f, 402f), pageWidths(source()))
            assertFalse(first.exists())
            assertFalse(second.exists())
        }
    }

    @Test
    fun importsAfterAFullPartGoIntoANewPartAndLeaveTheFullOneUntouched() {
        writeMarked(source(), 1000, firstWidth = 200f)
        val full = source().readBytes()

        for ((round, entry) in listOf(300f to 2, 400f to 1).withIndex()) {
            val imported = writeMarked(File(dir, "${round}_in.pdf"), entry.second, firstWidth = entry.first)
            runBlocking {
                operation(listOf(PdfImportOperation.Source(imported, null, null)), config = singleUp, batchSize = 50).execute {}
            }
        }

        assertEquals(listOf("0001.pdf", "0002.pdf"), sourceDir().list()!!.sorted())
        assertArrayEquals(full, source().readBytes())
        assertEquals(listOf(300f, 301f, 400f), pageWidths(File(sourceDir(), "0002.pdf")))
        assertEquals(1003, pageCount(nup()))
    }

    @Test
    fun anImageIsConvertedBeforeItIsAppended() {
        writeMarked(source(), 1, firstWidth = 200f)
        val image = File(dir, "1_photo.png").apply { writeText("png") }
        val converted = mutableListOf<File>()

        runBlocking {
            operation(
                listOf(PdfImportOperation.Source(image, null, null, isImage = true)),
                imageToPdf = { file ->
                    converted += file
                    writeMarked(File(dir, "from_image.pdf"), 1, firstWidth = 500f)
                }
            ).execute {}
        }

        assertEquals(listOf(image), converted)
        assertEquals(listOf(200f, 500f), pageWidths(source()))
        assertFalse(image.exists())
        assertFalse(File(dir, "from_image.pdf").exists())
    }

    @Test
    fun aPasswordProtectedPdfIsAppendedWithItsPassword() {
        writeMarked(source(), 1, firstWidth = 200f)
        val locked = writeLocked(File(dir, "1_locked.pdf"))

        runBlocking {
            operation(listOf(PdfImportOperation.Source(locked, "secret".toByteArray(), null))).execute {}
        }

        assertEquals(listOf(200f, 300f), pageWidths(source()))
    }

    @Test
    fun aFileThatDidNotOpenGoesStraightToTheRepair() {
        writeMarked(source(), 1, firstWidth = 200f)
        val broken = writeMarked(File(dir, "1_broken.pdf"), 1, firstWidth = 300f)
        val repairedPart = writeMarked(File(dir, "part.pdf"), 2, firstWidth = 600f)
        val repaired = mutableListOf<File>()

        runBlocking {
            operation(
                listOf(PdfImportOperation.Source(broken, null, IOException("open failed"))),
                repair = { input, _, _, _, _, block ->
                    repaired += input
                    block(listOf(repairedPart))
                    true
                }
            ).execute {}
        }

        assertEquals(listOf(broken), repaired)
        assertEquals(listOf(200f, 600f, 601f), pageWidths(source()))
    }

    @Test
    fun anUnrepairableFileFailsWithTheOpenFailureAndRestoresTheWorkFiles() {
        writeMarked(source(), 2, firstWidth = 200f)
        nup().writeText("old layout")
        val good = writeMarked(File(dir, "1_good.pdf"), 1, firstWidth = 300f)
        val broken = File(dir, "2_broken.pdf").apply { writeText("not a pdf") }
        val operation = operation(
            listOf(PdfImportOperation.Source(good, null, null), PdfImportOperation.Source(broken, null, IOException("open failed"))),
            repair = { _, _, _, _, _, _ -> false }
        )

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("open failed", failure?.message)
        assertEquals(listOf(200f, 201f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(good.exists())
        assertFalse(broken.exists())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(listOf(200f, 201f), pageWidths(source()))
    }

    @Test
    fun theWorkFilesAreRestoredOnlyAfterTheyWereBackedUp() {
        writeMarked(source(), 2, firstWidth = 200f)
        nup().writeText("old layout")
        val imported = writeMarked(File(dir, "1_in.pdf"), 1, firstWidth = 300f)
        val operation = operation(listOf(PdfImportOperation.Source(imported, null, null)))

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(2, pageCount(source()))
        assertTrue(imported.exists())

        runBlocking { operation.execute {} }
        assertEquals(3, pageCount(source()))

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(listOf(200f, 201f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
        assertTrue(dir.listFiles().orEmpty().all { it == sourceDir() || it == nup() })
    }

    @Test
    fun theProbeTellsPlainLockedAndBrokenFilesApart() {
        val plain = writeMarked(File(dir, "plain.pdf"), 1, firstWidth = 300f)
        val locked = writeLocked(File(dir, "locked.pdf"))
        val broken = File(dir, "broken.pdf").apply { writeText("not a pdf") }
        val accepts = { _: File, _: ByteArray? -> false }

        val plainProbe = PdfImportOperation.probe(plain, null, accepts)
        val lockedProbe = PdfImportOperation.probe(locked, null, accepts)
        val wrongProbe = PdfImportOperation.probe(locked, "wrong".toByteArray(), accepts)
        val unlockedProbe = PdfImportOperation.probe(locked, "secret".toByteArray(), accepts)
        val rejectedProbe = PdfImportOperation.probe(locked, "secret".toByteArray()) { _, _ -> true }
        val brokenProbe = PdfImportOperation.probe(broken, null, accepts)

        assertTrue(plainProbe.canOpen)
        assertNull(plainProbe.failure)
        assertFalse(plainProbe.noPages)
        assertFalse(lockedProbe.canOpen)
        assertFalse(wrongProbe.canOpen)
        assertTrue(unlockedProbe.canOpen)
        assertFalse(rejectedProbe.canOpen)
        assertTrue(brokenProbe.canOpen)
        assertNotNull(brokenProbe.failure)
    }

    private fun sourceDir() = File(dir, "source_temp")

    private fun source() = File(sourceDir(), "0001.pdf")

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun writeMarked(file: File, pages: Int, firstWidth: Float): File {
        file.parentFile?.mkdirs()
        PdfDocument(PdfWriter(file)).use { pdf ->
            repeat(pages) { index ->
                val page = pdf.addNewPage(PageSize(firstWidth + index, 300f))
                PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
        return file
    }

    private fun writeLocked(file: File): File {
        val properties = WriterProperties().setStandardEncryption(
            "secret".toByteArray(),
            "owner".toByteArray(),
            EncryptionConstants.ALLOW_PRINTING,
            EncryptionConstants.STANDARD_ENCRYPTION_128
        )
        PdfDocument(PdfWriter(file.absolutePath, properties)).use { pdf ->
            val page = pdf.addNewPage(PageSize(300f, 300f))
            PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
        }
        return file
    }

    private fun pageCount(file: File): Int = PdfDocument(PdfReader(file)).use { it.numberOfPages }

    private fun pageWidths(file: File): List<Float> =
        PdfDocument(PdfReader(file)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.width } }

    private fun operation(
        sources: List<PdfImportOperation.Source>,
        config: NupConfig = this.config,
        oldPerGroup: Int = 2,
        crops: Map<Int, PdfCropRect> = emptyMap(),
        rotation: IntArray = IntArray(0),
        position: IntArray = IntArray(0),
        selected: List<Int> = emptyList(),
        batchSize: Int = 2,
        imageToPdf: (File) -> File = { error("no image expected") },
        repair: PdfImportOperation.Repair = PdfImportOperation.Repair { _, _, _, _, _, _ -> error("no repair expected") }
    ) = PdfImportOperation(
        files = PdfLayoutChangeOperation.Files(
            dir,
            sourceDir(),
            nup(),
            WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
        ),
        saveSelection = { events += "selection saved" },
        input = PdfImportOperation.Input(
            sources = sources,
            oldPerGroup = oldPerGroup,
            config = config,
            crops = crops,
            rotation = rotation,
            position = position,
            defaultPosition = 5,
            batchSize = batchSize,
            selected = selected
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
        imageToPdf = imageToPdf,
        repair = repair
    )
}
