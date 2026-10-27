package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
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

class PdfViewerEditOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val crop = PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f)
    private val config = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val built = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun anEditOnTheLayoutIsProjectedToTheSourceAndTheLayoutIsRebuilt() {
        writeMarked(source(), 3)
        buildLayout(nup(), built)
        val edited = File(dir, "edited_layout.pdf")
        nup().copyTo(edited)
        drawOnFirstPage(edited)
        val crops = mapOf(1 to crop)

        val result = runBlocking {
            operation(copyEdited = { work -> edited.renameTo(work) }, crops = crops, rotation = intArrayOf(1), position = intArrayOf(9)).execute {}
        }

        assertEquals(listOf(200f, 201f, 202f), pageWidths(source()))
        assertEquals(2, pageCount(nup()))
        assertEquals(config, result.config)
        assertArrayEquals(intArrayOf(0, 0, 0), result.rotation)
        assertArrayEquals(intArrayOf(9, 5, 5), result.position)
        assertEquals(crops, result.crops)
        assertEquals(listOf(1), result.selected)
        assertEquals(3, pageCount(File(dir, "source_temp_copy/0001.pdf")))
        assertEquals(3, pageCount(File(dir, "nup_temp_copy.pdf")))
        assertFalse(File(dir, "nup_temp.pdf.edited").exists())
        assertFalse(edited.exists())
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun anUnmarkedLayoutReplacesTheLayoutAndKeepsTheBuiltConfig() {
        writeMarked(source(), 3)
        nup().writeText("old layout")
        val flattened = File(dir, "flattened_1.pdf").apply { writeText("x") }
        val rotation = intArrayOf(1, 2, 3)

        val result = runBlocking {
            operation(copyEdited = { work -> writeMarked(work, 2) }, rotation = rotation).execute {}
        }

        assertEquals(2, pageCount(nup()))
        assertEquals(listOf(200f, 201f, 202f), pageWidths(source()))
        assertEquals(built, result.config)
        assertTrue(rotation === result.rotation)
        val sizes = result.pageSizes!!
        assertArrayEquals(floatArrayOf(200f, 201f, 202f), sizes.widths, 0f)
        assertFalse(flattened.exists())
    }

    @Test
    fun withoutABuiltConfigTheResultKeepsItUnset() {
        writeMarked(source(), 2)
        nup().writeText("old layout")

        val result = runBlocking {
            operation(copyEdited = { work -> writeMarked(work, 1) }, builtConfig = null).execute {}
        }

        assertNull(result.config)
        assertNull(result.pageSizes)
    }

    @Test
    fun anUneditedLayoutLeavesBothFilesAlone() {
        writeMarked(source(), 3)
        buildLayout(nup(), built)
        val before = nup().readBytes()

        runBlocking {
            operation(copyEdited = { work -> nup().copyTo(work) }).execute {}
        }

        assertArrayEquals(before, nup().readBytes())
        assertEquals(3, pageCount(source()))
    }

    @Test
    fun aFailedCopyRestoresTheWorkFiles() {
        writeMarked(source(), 3)
        nup().writeText("old layout")
        val operation = operation(copyEdited = { throw IOException("no input") })

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("no input", failure?.message)
        assertEquals(3, pageCount(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals("old layout", nup().readText())
    }

    @Test
    fun theWorkFilesCanBeRestoredAfterASuccessfulEdit() {
        writeMarked(source(), 3)
        nup().writeText("old layout")
        val operation = operation(copyEdited = { work -> writeMarked(work, 1) })

        runBlocking { operation.restoreIfBackedUp() }
        runBlocking { operation.execute {} }
        assertEquals(1, pageCount(nup()))

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals("old layout", nup().readText())
        assertEquals(3, pageCount(source()))
    }

    private fun sourceDir() = File(dir, "source_temp")

    private fun source() = File(sourceDir(), "0001.pdf")

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun writeMarked(file: File, pages: Int) {
        file.parentFile?.mkdirs()
        PdfDocument(PdfWriter(file)).use { pdf ->
            repeat(pages) { index ->
                val page = pdf.addNewPage(PageSize(200f + index, 300f))
                PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
    }

    private fun buildLayout(out: File, layoutConfig: NupConfig) {
        val total = pageCount(source())
        PdfNupBuilder.build(
            source = PdfSourceParts.open(sourceDir()),
            totalSrcPages = total,
            outFile = out,
            workDir = dir,
            config = layoutConfig,
            rotation = IntArray(total),
            position = IntArray(total) { 5 },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = ProgressTracker { 0L }.let { it::stage }
        )
    }

    private fun drawOnFirstPage(file: File) {
        val out = File(dir, "drawn.pdf")
        PdfDocument(PdfReader(file), PdfWriter(out)).use { pdf ->
            val page = pdf.firstPage
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf)
                .rectangle(20.0, 20.0, 30.0, 30.0).fill().release()
        }
        file.delete()
        out.renameTo(file)
    }

    private fun pageCount(file: File): Int = PdfDocument(PdfReader(file)).use { it.numberOfPages }

    private fun pageWidths(file: File): List<Float> =
        PdfDocument(PdfReader(file)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.width } }

    private fun operation(
        copyEdited: (File) -> Unit,
        builtConfig: NupConfig? = built,
        crops: Map<Int, PdfCropRect> = emptyMap(),
        rotation: IntArray = IntArray(0),
        position: IntArray = IntArray(0)
    ) = PdfViewerEditOperation(
        files = PdfLayoutChangeOperation.Files(
            dir,
            sourceDir(),
            nup(),
            WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
        ),
        saveSelection = { events += "selection saved" },
        input = PdfViewerEditOperation.Input(
            config = config,
            builtConfig = builtConfig,
            crops = crops,
            rotation = rotation,
            position = position,
            defaultPosition = 5,
            batchSize = 2,
            selected = listOf(1)
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
        copyEdited = copyEdited
    )
}
