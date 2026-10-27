package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageSizes
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.ProgressMath
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfNupBuilderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val a4 = PageSize.A4.width to PageSize.A4.height
    private val toA4Sideways = PageSize.A4.width / PageSize.A4.height

    private class Built(
        val sizes: List<Pair<Float, Float>>,
        val cells: List<List<DoubleArray>>,
        val source: PdfSourceParts,
        val pageSizes: PageSizes?,
        val units: Long
    )

    @Test
    fun aOneUpPortraitA4PageIsPlacedUnscaled() {
        val built = build(listOf(a4), config(rows = 1, cols = 1))

        assertEquals(listOf(a4), built.sizes)
        assertCell(built.cells[0][0], 0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }

    @Test
    fun quarterTurnsShrinkASidewaysPageIntoTheCentreAndAHalfTurnFlipsIt() {
        val k = toA4Sideways.toDouble()
        val sidewaysHeight = PageSize.A4.width * toA4Sideways
        val bottom = (PageSize.A4.height - sidewaysHeight) / 2.0
        val built = build(listOf(a4, a4, a4), config(rows = 1, cols = 1), rotation = intArrayOf(1, 2, 3))

        assertEquals(listOf(a4, a4, a4), built.sizes)
        assertCell(built.cells[0][0], 0, 0.0, k, -k, 0.0, PageSize.A4.width.toDouble(), bottom)
        assertCell(built.cells[1][0], 1, -1.0, 0.0, 0.0, -1.0, PageSize.A4.width.toDouble(), PageSize.A4.height.toDouble())
        assertCell(built.cells[2][0], 2, 0.0, -k, k, 0.0, 0.0, bottom + sidewaysHeight)
    }

    @Test
    fun aOneUpLandscapeLayoutShrinksAPortraitPageAndCentresIt() {
        val k = toA4Sideways.toDouble()
        val built = build(listOf(a4), config(rows = 1, cols = 1, landscape = true))

        assertEquals(listOf(PageSize.A4.height to PageSize.A4.width), built.sizes)
        assertCell(built.cells[0][0], 0, k, 0.0, 0.0, k, (PageSize.A4.height - PageSize.A4.width * toA4Sideways) / 2.0, 0.0)
    }

    @Test
    fun aCropMovesTheCroppedOriginToTheCentreOfTheCell() {
        val crop = mapOf(0 to PdfCropRect(0.5f, 0.5f, 1f, 1f))
        val built = build(listOf(a4), config(rows = 1, cols = 1), crop = crop)

        val halfWidth = PageSize.A4.width / 2f
        val halfHeight = PageSize.A4.height / 2f
        val left = (PageSize.A4.width - halfWidth) * 0.5f
        val bottom = (PageSize.A4.height - halfHeight) * 0.5f
        assertCell(built.cells[0][0], 0, 1.0, 0.0, 0.0, 1.0, (left - halfWidth).toDouble(), (bottom - halfHeight).toDouble())
    }

    @Test
    fun aTwoByTwoLayoutWithMarginScalesEachPageIntoItsCellAndHonoursPositions() {
        val pages = List(5) { 200f to 100f }
        val built = build(
            pages,
            config(rows = 2, cols = 2, marginMm = 10, drawFrame = true, scale = true),
            position = intArrayOf(1, 9, 5, 5, 5)
        )

        val margin = 10 * 72f / 25.4f
        val cellW = (PageSize.A4.width - margin * 3) / 2
        val cellH = (PageSize.A4.height - margin * 3) / 2
        val k = cellW / 200f
        val scaledH = 100f * k
        val topRowY = margin + cellH + margin

        assertEquals(listOf(a4, a4), built.sizes)
        assertEquals(listOf(4, 1), built.cells.map { it.size })
        assertCell(built.cells[0][0], 0, k.toDouble(), 0.0, 0.0, k.toDouble(), margin.toDouble(), (topRowY + cellH - scaledH).toDouble())
        assertCell(built.cells[0][1], 1, k.toDouble(), 0.0, 0.0, k.toDouble(), (margin + cellW + margin).toDouble(), topRowY.toDouble())
        assertCell(built.cells[1][0], 4, k.toDouble(), 0.0, 0.0, k.toDouble(), margin.toDouble(), (topRowY + (cellH - scaledH) * 0.5f).toDouble())
    }

    @Test
    fun theBatchSizeDoesNotChangeTheLayout() {
        val pages = List(7) { if (it % 2 == 0) a4 else 300f to 200f }
        val cfg = config(rows = 2, cols = 2, marginMm = 3)
        val rotation = intArrayOf(0, 1, 2, 3, 0, 1, 2)
        val single = build(pages, cfg, rotation = rotation, batchSize = 1)
        val large = build(pages, cfg, rotation = rotation, batchSize = 20)

        assertEquals(single.sizes, large.sizes)
        assertEquals(single.cells.map { it.size }, large.cells.map { it.size })
        single.cells.flatten().zip(large.cells.flatten()).forEach { (a, b) -> assertArrayEquals(a, b, 1e-9) }
    }

    @Test
    fun aSmallBudgetDoesNotChangeTheLayout() {
        val pages = List(7) { if (it % 3 == 0) a4 else 300f + it to 200f }
        for ((rows, cols) in listOf(1 to 1, 2 to 2)) {
            val cfg = config(rows = rows, cols = cols, marginMm = 3)
            val rotation = intArrayOf(0, 1, 2, 3, 0, 1, 2)
            val unlimited = build(pages, cfg, rotation = rotation, batchSize = 20, partPages = 3, budget = Long.MAX_VALUE)
            val small = build(pages, cfg, rotation = rotation, batchSize = 20, partPages = 3, budget = 0)

            assertEquals(unlimited.sizes, small.sizes)
            assertEquals(unlimited.cells.map { it.size }, small.cells.map { it.size })
            unlimited.cells.flatten().zip(small.cells.flatten()).forEach { (a, b) -> assertArrayEquals(a, b, 1e-9) }
            assertEquals(unlimited.pageSizes?.widths?.toList(), small.pageSizes?.widths?.toList())
            assertUnits(unlimited.units, small.units)
        }
    }

    @Test
    fun aSourceInSeveralPartsGivesTheSameLayout() {
        val pages = List(7) { if (it % 3 == 0) a4 else 300f + it to 200f }
        val cropBoxOf = mapOf(2 to Rectangle(10f, 20f, 150f, 120f))
        for ((rows, cols) in listOf(1 to 1, 2 to 2)) {
            val cfg = config(rows = rows, cols = cols, marginMm = 3)
            val rotation = intArrayOf(0, 1, 2, 3, 0, 1, 2)
            val whole = build(pages, cfg, rotation = rotation, cropBoxOf = cropBoxOf, batchSize = 3)
            val parted = build(pages, cfg, rotation = rotation, cropBoxOf = cropBoxOf, batchSize = 3, partPages = 2)

            assertEquals(4, parted.source.layout.parts)
            assertEquals(whole.sizes, parted.sizes)
            assertEquals(whole.cells.map { it.size }, parted.cells.map { it.size })
            whole.cells.flatten().zip(parted.cells.flatten()).forEach { (a, b) -> assertArrayEquals(a, b, 1e-9) }
            assertEquals(whole.pageSizes?.widths?.toList(), parted.pageSizes?.widths?.toList())
            assertEquals(whole.pageSizes?.heights?.toList(), parted.pageSizes?.heights?.toList())
        }
    }

    @Test
    fun theSingleUpPageSizesAreTheSameAsTheSeparateReading() {
        val pages = listOf(a4, 300f to 200f, 100f to 400f, a4, 250f to 250f)
        val cropBoxOf = mapOf(1 to Rectangle(10f, 20f, 150f, 120f), 4 to Rectangle(0f, 0f, 200f, 100f))
        for (landscape in listOf(false, true)) {
            for (batchSize in listOf(1, 2, 10)) {
                val cfg = config(rows = 1, cols = 1, landscape = landscape)
                val built = build(pages, cfg, cropBoxOf = cropBoxOf, batchSize = batchSize)
                val (fallbackW, fallbackH) = PdfPageSizes.fallback(cfg)
                val expected = PdfPageSizes.read(built.source, pages.size, batchSize, fallbackW, fallbackH, CountingProgress())

                val actual = built.pageSizes!!
                assertArrayEquals(expected.widths, actual.widths, 0f)
                assertArrayEquals(expected.heights, actual.heights, 0f)
            }
        }
    }

    @Test
    fun aMultiUpLayoutCollectsNoPageSizes() {
        val built = build(List(5) { a4 }, config(rows = 2, cols = 2))

        assertNull(built.pageSizes)
    }

    @Test
    fun theBuilderAdvancesExactlyTheReservedLayoutUnits() {
        val pages = List(5) { a4 }
        for ((rows, cols) in listOf(1 to 1, 1 to 2, 2 to 2)) {
            val built = build(pages, config(rows = rows, cols = cols))

            assertUnits(ProgressMath.layoutUnits(pages.size, rows * cols), built.units)
        }
    }

    private fun config(
        rows: Int,
        cols: Int,
        marginMm: Int = 0,
        landscape: Boolean = false,
        drawFrame: Boolean = false,
        scale: Boolean = false
    ) = NupConfig(rows, cols, marginMm, landscape, drawFrame, scale, fitIndividually = false)

    private fun build(
        pages: List<Pair<Float, Float>>,
        config: NupConfig,
        rotation: IntArray = IntArray(pages.size),
        position: IntArray = IntArray(pages.size) { 5 },
        crop: Map<Int, PdfCropRect> = emptyMap(),
        cropBoxOf: Map<Int, Rectangle> = emptyMap(),
        batchSize: Int = 20,
        partPages: Int = pages.size,
        budget: Long = Long.MAX_VALUE
    ): Built {
        val dir = temp.newFolder()
        val sourceDir = File(dir, "source_temp").apply { mkdirs() }
        for ((part, chunk) in pages.indices.chunked(partPages).withIndex()) {
            PdfDocument(PdfWriter(File(sourceDir, "${part + 1}".padStart(4, '0') + ".pdf"))).use { pdf ->
                for (index in chunk) {
                    val (width, height) = pages[index]
                    val page = pdf.addNewPage(PageSize(width, height))
                    cropBoxOf[index]?.let { page.setCropBox(it) }
                    PdfCanvas(page).rectangle(10.0, 10.0, (width - 20).toDouble(), (height - 20).toDouble()).fill().release()
                }
            }
        }
        val source = PdfSourceParts.open(sourceDir)
        val output = File(dir, "nup.pdf")
        val progress = CountingProgress()
        val pageSizes = PdfNupBuilder.build(
            source = source,
            totalSrcPages = pages.size,
            outFile = output,
            workDir = dir,
            config = config,
            rotation = rotation,
            position = position,
            crop = crop,
            batchSize = batchSize,
            progressStage = { progress },
            budget = budget
        )
        return PdfDocument(PdfReader(output)).use { pdf ->
            val sizes = (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.let { box -> box.width to box.height } }
            val cells = (1..pdf.numberOfPages).map { number ->
                val values = pdf.getPage(number).pdfObject
                    .getAsDictionary(PdfName.PieceInfo)
                    .getAsDictionary(PdfName("hureelee81reepdf"))
                    .getAsDictionary(PdfName.Private)
                    .getAsDictionary(PdfName("Base"))
                    .getAsArray(PdfName("Cells"))
                (0 until values.size() / 7).map { cell ->
                    DoubleArray(7) { values.getAsNumber(cell * 7 + it).doubleValue() }
                }
            }
            Built(sizes, cells, source, pageSizes, progress.units)
        }
    }

    private fun assertCell(actual: DoubleArray, sourceIndex: Int, vararg matrix: Double) {
        assertEquals(sourceIndex.toDouble(), actual[0], 0.0)
        assertArrayEquals(matrix, actual.copyOfRange(1, 7), 0.01)
    }

    private fun assertUnits(expected: Long, actual: Long) {
        assertEquals(expected, actual)
    }

    private class CountingProgress : PdfProgress {
        var units = 0L

        override fun advance(units: Int) {
            this.units += units
        }

        override fun idle() = Unit
    }
}
