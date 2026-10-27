package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfSignOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val crop = PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f)
    private val config = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun theSignedGroupsBecomeOneA4PageAndTheArraysFollowTheMerge() {
        writeMarkedSource(5)
        val renderer = FakeRenderer(pageCount = 3)

        val result = runBlocking {
            operation(
                renderer,
                nupIndices = listOf(0, 2),
                crops = mapOf(0 to crop, 1 to crop, 3 to crop),
                rotation = intArrayOf(1, 2, 3, 0, 1),
                position = intArrayOf(9, 1, 2, 3, 4)
            ).execute {}
        }

        assertEquals(listOf(PageSize.A4.height, 202f, 203f, PageSize.A4.height), pageWidths(source()))
        assertArrayEquals(intArrayOf(0, 3, 0, 0), result.rotation)
        assertArrayEquals(intArrayOf(DEFAULT_POSITION, 2, 3, DEFAULT_POSITION), result.position)
        assertEquals(mapOf(2 to crop), result.crops)
        assertEquals(emptyList<Int>(), result.selected)
        assertEquals(config, result.config)
        assertEquals(2, pageCount(nup()))
        assertEquals(listOf(0, 2), renderer.rendered)
        assertTrue(renderer.closed)
        assertEquals(listOf("selection saved"), events)
        assertEquals(5, pageCount(File(dir, "source_temp_copy/0001.pdf")))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("signed_") })
    }

    @Test
    fun aPortraitSignatureMakesAPortraitA4Page() {
        writeMarkedSource(2)

        runBlocking { operation(FakeRenderer(pageCount = 1), nupIndices = listOf(0), isLandscape = false).execute {} }

        assertEquals(listOf(PageSize.A4.width), pageWidths(source()))
        assertEquals(listOf(PageSize.A4.height), pageHeights(source()))
    }

    @Test
    fun anUndecodableSignatureAndAMissingPageLeaveTheSourceAlone() {
        writeMarkedSource(5)
        val renderer = FakeRenderer(pageCount = 3, undecodable = setOf(1))

        val result = runBlocking {
            operation(renderer, nupIndices = listOf(1, 7), rotation = intArrayOf(1, 2, 3, 0, 1)).execute {}
        }

        assertEquals(listOf(200f, 201f, 202f, 203f, 204f), pageWidths(source()))
        assertArrayEquals(intArrayOf(1, 2, 3, 0, 1), result.rotation)
        assertEquals(listOf(1), renderer.rendered)
        assertEquals(3, pageCount(nup()))
    }

    @Test
    fun theRendererGetsTheSignatureUnchanged() {
        writeMarkedSource(2)
        val renderer = FakeRenderer(pageCount = 1)

        runBlocking { operation(renderer, nupIndices = listOf(0)).execute {} }

        val signature = renderer.signatures.single()
        assertEquals(File(dir, "sign_test.png"), signature.file)
        assertEquals(300, signature.width)
        assertEquals(100, signature.height)
        assertEquals(2480f, signature.refPx, 0f)
        assertEquals(10f, signature.xMm, 0f)
        assertEquals(20f, signature.yMm, 0f)
        assertTrue(signature.scanEffect)
        assertFalse(signature.grayscale)
    }

    @Test
    fun theWorkFilesAreRestoredAfterASuccessfulRunWhenCancelledLate() {
        writeMarkedSource(5)
        nup().writeText("old layout")
        val operation = operation(FakeRenderer(pageCount = 3), nupIndices = listOf(0))

        runBlocking { operation.execute {} }
        assertEquals(4, pageCount(source()))

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(listOf(200f, 201f, 202f, 203f, 204f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
    }

    @Test
    fun aFailedRebuildRestoresTheWorkFilesAndDeletesTheSignedPages() {
        writeMarkedSource(5)
        nup().writeText("old layout")
        File(dir, "nup_temp.pdf.part").apply { mkdirs() }.resolve("blocker").writeText("x")
        val operation = operation(FakeRenderer(pageCount = 3), nupIndices = listOf(0))

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(listOf(200f, 201f, 202f, 203f, 204f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("signed_") })

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(listOf(200f, 201f, 202f, 203f, 204f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
    }

    @Test
    fun aSignedGroupInsideOnePartRewritesOnlyThatPart() {
        writeMarkedParts(2, 2, 1)
        val untouched = listOf("0001.pdf", "0003.pdf").associateWith { File(sourceDir(), it).readBytes() }

        val result = runBlocking { operation(FakeRenderer(pageCount = 3), nupIndices = listOf(1), rotation = intArrayOf(1, 2, 3, 0, 1)).execute {} }

        assertEquals(listOf(listOf(200f, 201f), listOf(PageSize.A4.height), listOf(204f)), partWidths())
        for ((name, bytes) in untouched) assertArrayEquals(name, bytes, File(sourceDir(), name).readBytes())
        assertArrayEquals(intArrayOf(1, 2, 0, 1), result.rotation)
        assertEquals(2, pageCount(nup()))
    }

    @Test
    fun aSignedGroupAcrossAPartBoundaryLeavesItsOtherPageOutOfTheNextPart() {
        writeMarkedParts(3, 2)

        runBlocking { operation(FakeRenderer(pageCount = 3), nupIndices = listOf(1)).execute {} }

        assertEquals(listOf(listOf(200f, 201f, PageSize.A4.height), listOf(204f)), partWidths())
    }

    private fun writeMarkedParts(vararg sizes: Int) {
        sourceDir().mkdirs()
        var index = 0
        for ((part, size) in sizes.withIndex()) {
            PdfDocument(PdfWriter(File(sourceDir(), "000${part + 1}.pdf"))).use { pdf ->
                repeat(size) {
                    val page = pdf.addNewPage(PageSize(200f + index++, 300f))
                    PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
                }
            }
        }
    }

    private fun partWidths(): List<List<Float>> =
        sourceDir().listFiles().orEmpty().sortedBy { it.name }.map { pageWidths(it) }

    private class FakeRenderer(
        private val pageCount: Int,
        private val undecodable: Set<Int> = emptySet()
    ) : SignedPageRenderer {
        val rendered = mutableListOf<Int>()
        val signatures = mutableListOf<SignedPageRenderer.Signature>()
        var closed = false

        override fun open(layout: File): SignedPageRenderer.Pages = object : SignedPageRenderer.Pages {
            override val pageCount: Int
                get() = this@FakeRenderer.pageCount

            override fun render(pageIndex: Int, signature: SignedPageRenderer.Signature): SignedPageRenderer.RgbImage? {
                rendered += pageIndex
                signatures += signature
                if (pageIndex in undecodable) return null
                val width = if (signature.isLandscape) 42 else 30
                val height = if (signature.isLandscape) 30 else 42
                return SignedPageRenderer.RgbImage(width, height, ByteArray(width * height * 3) { -1 })
            }

            override fun close() {
                closed = true
            }
        }
    }

    private fun sourceDir() = File(dir, "source_temp")

    private fun source() = File(sourceDir(), "0001.pdf")

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun writeMarkedSource(pages: Int) {
        sourceDir().mkdirs()
        PdfDocument(PdfWriter(source())).use { pdf ->
            repeat(pages) { index ->
                val page = pdf.addNewPage(PageSize(200f + index, 300f))
                PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
    }

    private fun pageCount(file: File): Int = PdfDocument(PdfReader(file)).use { it.numberOfPages }

    private fun pageWidths(file: File): List<Float> =
        PdfDocument(PdfReader(file)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.width } }

    private fun pageHeights(file: File): List<Float> =
        PdfDocument(PdfReader(file)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.height } }

    private fun operation(
        renderer: SignedPageRenderer,
        nupIndices: List<Int>,
        crops: Map<Int, PdfCropRect> = emptyMap(),
        rotation: IntArray = IntArray(0),
        position: IntArray = IntArray(0),
        isLandscape: Boolean = true
    ) = PdfSignOperation(
        files = PdfLayoutChangeOperation.Files(
            dir,
            sourceDir(),
            nup(),
            WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
        ),
        saveSelection = { events += "selection saved" },
        input = PdfSignOperation.Input(
            signature = SignedPageRenderer.Signature(
                file = File(dir, "sign_test.png"),
                width = 300,
                height = 100,
                refPx = 2480f,
                xMm = 10f,
                yMm = 20f,
                isLandscape = isLandscape,
                scanEffect = true,
                grayscale = false
            ),
            highDpi = true,
            nupIndices = nupIndices,
            perGroup = 2,
            config = config,
            crops = crops,
            rotation = rotation,
            position = position,
            defaultPosition = 5,
            batchSize = 2
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
        renderer = renderer
    )
}
