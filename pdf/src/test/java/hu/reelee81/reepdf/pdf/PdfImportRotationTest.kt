package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.Matrix
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.annot.PdfAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageSizes
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.SourcePageArrays
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfImportRotationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val box = Rectangle(0f, 0f, 400f, 200f)
    private val mark = Rectangle(20f, 20f, 100f, 40f)
    private val singleUp = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val oneByTwo = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val twoByTwo = NupConfig(2, 2, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val twoByTwoScaled = NupConfig(2, 2, 0, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun theOwnRotateOfImportedPagesBecomesTheirRotationAndTheMarkStaysWhereItIsShown() {
        val rotates = listOf(null, 90, 180, 270)
        for (config in listOf(singleUp, twoByTwo)) {
            val work = temp.newFolder()
            val input = sample(File(work, "1_in.pdf"), rotates)
            val contents = PdfDocument(PdfReader(input)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).contentBytes } }

            val result = runBlocking { operation(work, listOf(PdfImportOperation.Source(input, null, null)), config).execute {} }

            assertArrayEquals(intArrayOf(0, 3, 2, 1), result.rotation)
            PdfDocument(PdfReader(source(work))).use { pdf ->
                assertEquals(4, pdf.numberOfPages)
                for (number in 1..pdf.numberOfPages) {
                    val page = pdf.getPage(number)
                    assertNull(page.pdfObject.get(PdfName.Rotate))
                    assertArrayEquals(contents[number - 1], page.contentBytes)
                }
            }
            val placed = layout(nup(work))
            assertEquals(listOf(0, 1, 2, 3), placed.map { it.sourceIndex })
            for (cell in placed) {
                val rotate = rotates[cell.sourceIndex] ?: 0
                assertArrayEquals("/Rotate $rotate", shown(rotate), relative(cell.mark, cell.page), 1e-3f)
            }
        }
    }

    @Test
    fun anImportedRotatedPageIsLaidOutLikeAnUnrotatedPageTurnedWithTheButton() {
        val rotatedWork = temp.newFolder()
        val plainWork = temp.newFolder()
        val rotatedInput = sample(File(rotatedWork, "1_in.pdf"), listOf(90, 180, 270, null))
        val plainInput = sample(File(plainWork, "1_in.pdf"), listOf(null, null, null, null))
        val rotated = runBlocking { operation(rotatedWork, listOf(PdfImportOperation.Source(rotatedInput, null, null))).execute {} }
        val plain = runBlocking { operation(plainWork, listOf(PdfImportOperation.Source(plainInput, null, null))).execute {} }

        var pressed = plain.rotation
        for ((page, presses) in listOf(3, 2, 1, 0).withIndex()) {
            repeat(presses) { pressed = SourcePageArrays.withRotatedGroups(pressed, listOf(page), per = 1, total = 4) }
        }
        assertArrayEquals(pressed, rotated.rotation)

        val crops = mapOf(0 to PdfCropRect(0.1f, 0.2f, 0.7f, 0.9f), 2 to PdfCropRect(0.3f, 0.1f, 0.95f, 0.6f))
        val positions = intArrayOf(1, 9, 3, 5)
        for (config in listOf(singleUp, twoByTwo, twoByTwoScaled)) {
            assertEquals(
                built(rotatedWork, config, rotated.rotation, positions, crops),
                built(plainWork, config, pressed, positions, crops)
            )
        }
    }

    @Test
    fun anInheritedOwnRotateIsTakenOverToo() {
        val plain = sample(File(temp.newFolder(), "plain.pdf"), listOf(null, null))
        val inherited = File(dir, "1_in.pdf")
        PdfDocument(PdfReader(plain), PdfWriter(inherited)).use { pdf ->
            pdf.catalog.pdfObject.getAsDictionary(PdfName.Pages).put(PdfName.Rotate, PdfNumber(270))
        }
        PdfDocument(PdfReader(inherited)).use { pdf ->
            assertNull(pdf.getPage(1).pdfObject.get(PdfName.Rotate))
            assertEquals(270, pdf.getPage(1).rotation)
        }

        val result = runBlocking { operation(dir, listOf(PdfImportOperation.Source(inherited, null, null))).execute {} }

        assertArrayEquals(intArrayOf(1, 1), result.rotation)
        PdfDocument(PdfReader(source(dir))).use { pdf ->
            assertEquals(listOf(0, 0), (1..2).map { pdf.getPage(it).rotation })
            assertEquals(listOf(270, 270), (1..2).map { ownRotate(pdf.getPage(it)) })
        }
        assertEquals(listOf(200f to 400f, 200f to 400f), sizes(result.pageSizes!!))
    }

    @Test
    fun onlyTheNewPagesOfSeveralFilesGetTheirOwnRotationAndTheExistingSourceIsLeftAlone() {
        sample(source(dir), listOf(90, null))
        val first = sample(File(dir, "1_a.pdf"), listOf(270, null))
        val second = sample(File(dir, "2_b.pdf"), listOf(180))
        val rotation = intArrayOf(1, 2)
        val position = intArrayOf(9, 9)

        val result = runBlocking {
            operation(
                dir,
                listOf(PdfImportOperation.Source(first, null, null), PdfImportOperation.Source(second, null, null)),
                config = oneByTwo,
                rotation = rotation,
                position = position,
                selected = listOf(0)
            ).execute {}
        }

        assertArrayEquals(intArrayOf(1, 2, 1, 0, 2), result.rotation)
        assertArrayEquals(intArrayOf(9, 9, 5, 5, 5), result.position)
        assertEquals(listOf(0), result.selected)
        assertArrayEquals(intArrayOf(1, 2), rotation)
        assertArrayEquals(intArrayOf(9, 9), position)
        PdfDocument(PdfReader(source(dir))).use { pdf ->
            assertEquals(listOf(90, 0, 0, 0, 0), (1..5).map { pdf.getPage(it).rotation })
            assertEquals(listOf(null, null, 270, null, 180), (1..5).map { ownRotate(pdf.getPage(it)) })
        }
    }

    @Test
    fun aRotateThatIsNotAQuarterTurnStaysAsTodayAndOtherQuarterTurnValuesAreNormalised() {
        val input = sample(File(dir, "1_in.pdf"), listOf(45, -90, 450))

        val result = runBlocking { operation(dir, listOf(PdfImportOperation.Source(input, null, null))).execute {} }

        assertArrayEquals(intArrayOf(0, 1, 3), result.rotation)
        PdfDocument(PdfReader(source(dir))).use { pdf ->
            assertEquals(45, pdf.getPage(1).pdfObject.getAsNumber(PdfName.Rotate).intValue())
            assertNull(ownRotate(pdf.getPage(1)))
            assertNull(pdf.getPage(2).pdfObject.get(PdfName.Rotate))
            assertEquals(270, ownRotate(pdf.getPage(2)))
            assertNull(pdf.getPage(3).pdfObject.get(PdfName.Rotate))
            assertEquals(90, ownRotate(pdf.getPage(3)))
        }
        assertEquals(listOf(400f to 200f, 200f to 400f, 200f to 400f), sizes(result.pageSizes!!))
    }

    @Test
    fun repairedPartsGiveTheirOwnRotationWithTheRightPageNumbers() {
        sample(source(dir), listOf(null))
        val broken = File(dir, "1_broken.pdf").apply { writeText("not a pdf") }
        val parts = temp.newFolder()
        val firstPart = sample(File(parts, "part_0.pdf"), listOf(90, null))
        val secondPart = sample(File(parts, "part_1.pdf"), listOf(180))

        val result = runBlocking {
            operation(
                dir,
                listOf(PdfImportOperation.Source(broken, null, IOException("open failed"))),
                rotation = intArrayOf(2),
                repair = { _, _, _, _, _, block ->
                    block(listOf(firstPart, secondPart))
                    PdfMuPdfRepair.Repaired(skippedPages = 0, annotationsLost = false)
                }
            ).execute {}
        }

        assertArrayEquals(intArrayOf(2, 3, 0, 2), result.rotation)
        PdfDocument(PdfReader(source(dir))).use { pdf ->
            assertEquals(listOf(0, 0, 0, 0), (1..4).map { pdf.getPage(it).rotation })
            assertEquals(listOf(null, 90, null, 180), (1..4).map { ownRotate(pdf.getPage(it)) })
        }
    }

    @Test
    fun aFailedImportRestoresTheSourceAndLeavesTheScreenArraysUnchanged() {
        sample(source(dir), listOf(null, null))
        nup(dir).writeText("old layout")
        val rotated = sample(File(dir, "1_rotated.pdf"), listOf(90))
        val broken = File(dir, "2_broken.pdf").apply { writeText("not a pdf") }
        val rotation = intArrayOf(1, 2)
        val position = intArrayOf(9, 9)
        val operation = operation(
            dir,
            listOf(PdfImportOperation.Source(rotated, null, null), PdfImportOperation.Source(broken, null, IOException("open failed"))),
            rotation = rotation,
            position = position,
            repair = { _, _, _, _, _, _ -> null }
        )

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertArrayEquals(intArrayOf(1, 2), rotation)
        assertArrayEquals(intArrayOf(9, 9), position)
        assertUnchangedSource()
    }

    @Test
    fun aCancelledImportIsRestoredAndLeavesTheScreenArraysUnchanged() {
        sample(source(dir), listOf(null, null))
        nup(dir).writeText("old layout")
        val rotated = sample(File(dir, "1_rotated.pdf"), listOf(90))
        val image = File(dir, "2_photo.png").apply { writeText("png") }
        val rotation = intArrayOf(1, 2)
        val position = intArrayOf(9, 9)
        val operation = operation(
            dir,
            listOf(PdfImportOperation.Source(rotated, null, null), PdfImportOperation.Source(image, null, null, isImage = true)),
            rotation = rotation,
            position = position,
            imageToPdf = { throw CancellationException("cancelled") }
        )

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()
        runBlocking { operation.restoreIfBackedUp() }

        assertTrue(failure is CancellationException)
        assertArrayEquals(intArrayOf(1, 2), rotation)
        assertArrayEquals(intArrayOf(9, 9), position)
        assertUnchangedSource()
    }

    @Test
    fun theAppearanceHelperPageStillGetsTheOriginalRotate() {
        val input = File(dir, "annotated.pdf")
        PdfDocument(PdfWriter(input)).use { pdf ->
            val page = pdf.addNewPage(PageSize(box))
            PdfCanvas(page).rectangle(mark).fill().release()
            page.setRotation(90)
            val highlight = PdfDictionary().apply {
                put(PdfName.Type, PdfName.Annot)
                put(PdfName.Subtype, PdfName.Highlight)
                put(PdfName.Rect, PdfArray(mark))
                put(PdfName.F, PdfNumber(4))
                put(PdfName.QuadPoints, PdfArray(floatArrayOf(20f, 60f, 120f, 60f, 20f, 20f, 120f, 20f)))
            }
            page.addAnnotation(PdfAnnotation.makeAnnotation(highlight))
        }
        val helperRotations = ArrayList<Int>()
        val turns = ArrayList<Pair<Int, Int>>()
        val empty = PdfSourceParts.empty(sourceDir(dir))

        val parts = empty.write(
            SourcePartPlan.append(empty.layout, listOf(SourcePartPlan.Piece.Incoming(0, 0, 0))),
            2,
            NoProgress,
            PdfSourceParts.Inputs(
                incoming = listOf(
                    PdfSourceParts.Incoming(input, null, onOwnRotation = { page, quarterTurns -> turns += page to quarterTurns })
                ),
                copierDir = temp.newFolder(),
                generateAppearances = { helper, output ->
                    helperRotations += PdfDocument(PdfReader(helper)).use { it.getPage(1).rotation }
                    helper.copyTo(output, overwrite = true)
                }
            )
        )

        assertEquals(listOf(90), helperRotations)
        assertEquals(listOf(0 to 3), turns)
        PdfDocument(PdfReader(parts.file(0))).use { pdf ->
            assertEquals(0, pdf.getPage(1).rotation)
            assertEquals(90, ownRotate(pdf.getPage(1)))
        }
    }

    @Test
    fun theSizeLabelShowsTheOwnRotationButNotTheButtonRotation() {
        val input = sample(File(dir, "1_in.pdf"), listOf(null, 90, 180, 270))
        val shown = listOf(400f to 200f, 200f to 400f, 400f to 200f, 200f to 400f)

        val result = runBlocking { operation(dir, listOf(PdfImportOperation.Source(input, null, null))).execute {} }

        assertEquals(shown, sizes(result.pageSizes!!))
        val parts = PdfSourceParts.open(sourceDir(dir))
        assertEquals(shown, readSizes(parts))
        for (presses in 1..3) {
            var turned = result.rotation
            repeat(presses) { turned = SourcePageArrays.withRotatedGroups(turned, listOf(0, 1, 2, 3), per = 1, total = 4) }
            assertEquals(shown, sizes(buildSingleUp(parts, turned)))
        }
    }

    @Test
    fun theOwnRotationMarkSurvivesReorderingDeletionAndEditProjection() {
        val input = sample(File(dir, "1_in.pdf"), listOf(null, 90, 180, 270))
        val shown = listOf(400f to 200f, 200f to 400f, 400f to 200f, 200f to 400f)
        runBlocking { operation(dir, listOf(PdfImportOperation.Source(input, null, null))).execute {} }

        val start = PdfSourceParts.open(sourceDir(dir))
        val reordered = start.write(SourcePartPlan.reorder(start.layout, intArrayOf(1, 0, 3, 2)), 2, NoProgress)
        assertEquals(listOf(shown[1], shown[0], shown[3], shown[2]), readSizes(reordered))

        val removed = reordered.write(SourcePartPlan.removePages(reordered.layout, setOf(1)), 2, NoProgress)
        val left = listOf(shown[1], shown[3], shown[2])
        assertEquals(left, readSizes(removed))

        val rotation = intArrayOf(3, 1, 2)
        val edited = File(dir, "edited.pdf")
        PdfDocument(PdfReader(layoutFile(removed, rotation)), PdfWriter(edited)).use { pdf ->
            val page = pdf.getPage(1)
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf).moveTo(10.0, 10.0).lineTo(100.0, 100.0).stroke()
        }
        val projected = PdfEditProjection.project(removed, edited, batchSize = 2, writeStage = { NoProgress }) {}
        assertEquals(1, projected.changed)
        PdfDocument(PdfReader(projected.source.file(0))).use { pdf ->
            assertNotNull(layerForm(pdf.getPage(1)))
            assertEquals(90, ownRotate(pdf.getPage(1)))
        }
        assertEquals(left, readSizes(projected.source))

        val cleared = File(dir, "cleared.pdf")
        assertEquals(1, PdfEditProjection.clearLayers(layoutFile(projected.source, rotation), cleared, null, batchSize = 2) {})
        val erased = PdfEditProjection.project(projected.source, cleared, batchSize = 2, writeStage = { NoProgress }) {}
        assertEquals(1, erased.changed)
        PdfDocument(PdfReader(erased.source.file(0))).use { pdf ->
            assertNull(layerForm(pdf.getPage(1)))
            assertEquals(90, ownRotate(pdf.getPage(1)))
        }
        assertEquals(left, readSizes(erased.source))
    }

    private class Placed(val sourceIndex: Int, val page: Rectangle, val mark: Rectangle)

    private fun layout(file: File): List<Placed> = PdfDocument(PdfReader(file)).use { pdf ->
        (1..pdf.numberOfPages).flatMap { number ->
            val page = pdf.getPage(number)
            val marks = filledBoxes(page)
            cells(page).map { (sourceIndex, matrix) ->
                val placedPage = transformed(box, matrix)
                Placed(sourceIndex, placedPage, marks.single { contains(placedPage, it) })
            }
        }
    }

    private fun cells(page: PdfPage): List<Pair<Int, DoubleArray>> {
        val cells = PdfEditProjection.privateData(page.pdfObject)!!.getAsDictionary(PdfName("Base")).getAsArray(PdfName("Cells"))
        return (0 until cells.size() / 7).map { cell ->
            cells.getAsNumber(cell * 7).intValue() to DoubleArray(6) { cells.getAsNumber(cell * 7 + 1 + it).doubleValue() }
        }
    }

    private fun filledBoxes(page: PdfPage): List<Rectangle> {
        val boxes = ArrayList<Rectangle>()
        val listener = object : IEventListener {
            override fun eventOccurred(data: IEventData?, type: EventType?) {
                if (data !is PathRenderInfo || data.operation and PathRenderInfo.FILL == 0) return
                val ctm = data.ctm
                val points = data.path.subpaths.flatMap { subpath -> subpath.segments.flatMap { it.basePoints } }
                boxes += bounds(points.map { point ->
                    (ctm.get(Matrix.I11) * point.x + ctm.get(Matrix.I21) * point.y + ctm.get(Matrix.I31)) to
                        (ctm.get(Matrix.I12) * point.x + ctm.get(Matrix.I22) * point.y + ctm.get(Matrix.I32))
                })
            }

            override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_PATH)
        }
        PdfCanvasProcessor(listener).processPageContent(page)
        return boxes
    }

    private fun transformed(rect: Rectangle, m: DoubleArray): Rectangle = bounds(
        listOf(rect.left to rect.bottom, rect.right to rect.bottom, rect.left to rect.top, rect.right to rect.top).map { (x, y) ->
            (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
        }
    )

    private fun bounds(points: List<Pair<Double, Double>>): Rectangle {
        val left = points.minOf { it.first }
        val bottom = points.minOf { it.second }
        return Rectangle(
            left.toFloat(), bottom.toFloat(),
            (points.maxOf { it.first } - left).toFloat(), (points.maxOf { it.second } - bottom).toFloat()
        )
    }

    private fun contains(outer: Rectangle, inner: Rectangle): Boolean =
        inner.left >= outer.left - TOLERANCE && inner.right <= outer.right + TOLERANCE &&
            inner.bottom >= outer.bottom - TOLERANCE && inner.top <= outer.top + TOLERANCE

    private fun relative(inner: Rectangle, outer: Rectangle): FloatArray = floatArrayOf(
        (inner.left - outer.left) / outer.width,
        (inner.bottom - outer.bottom) / outer.height,
        (inner.right - outer.left) / outer.width,
        (inner.top - outer.bottom) / outer.height
    )

    private fun shown(rotate: Int): FloatArray {
        var width = box.width
        var height = box.height
        var corners = listOf(mark.left to mark.bottom, mark.right to mark.top)
        repeat(rotate / 90) {
            val widthBefore = width
            corners = corners.map { (x, y) -> y to widthBefore - x }
            width = height
            height = widthBefore
        }
        return floatArrayOf(
            corners.minOf { it.first } / width, corners.minOf { it.second } / height,
            corners.maxOf { it.first } / width, corners.maxOf { it.second } / height
        )
    }

    private fun built(work: File, config: NupConfig, rotation: IntArray, positions: IntArray, crops: Map<Int, PdfCropRect>): List<String> {
        val out = File(work, "built.pdf")
        val source = PdfSourceParts.open(sourceDir(work))
        PdfNupBuilder.build(
            source = source,
            totalSrcPages = source.total,
            outFile = out,
            workDir = work,
            config = config,
            rotation = rotation,
            position = positions,
            crop = crops,
            batchSize = 2,
            progressStage = { NoProgress }
        )
        return PdfDocument(PdfReader(out)).use { pdf ->
            (1..pdf.numberOfPages).map { number ->
                val page = pdf.getPage(number)
                val xObjects = page.resources.pdfObject.getAsDictionary(PdfName.XObject)
                val forms = xObjects.keySet().sortedBy { it.value }.joinToString("\n") { name ->
                    val form: PdfStream = xObjects.getAsStream(name)
                    "$name ${form.getAsArray(PdfName.BBox)} ${form.getAsArray(PdfName.Matrix)} ${String(form.bytes, Charsets.ISO_8859_1)}"
                }
                cells(page).joinToString { (index, matrix) -> "$index ${matrix.toList()}" } + "\n" +
                    String(page.contentBytes, Charsets.ISO_8859_1) + "\n" + forms
            }
        }
    }

    private fun layoutFile(parts: PdfSourceParts, rotation: IntArray): File {
        val out = File(dir, "layout_${System.nanoTime()}.pdf")
        PdfNupBuilder.build(
            source = parts,
            totalSrcPages = parts.total,
            outFile = out,
            workDir = dir,
            config = singleUp,
            rotation = rotation,
            position = IntArray(parts.total) { 5 },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = { NoProgress }
        )
        return out
    }

    private fun buildSingleUp(parts: PdfSourceParts, rotation: IntArray): PageSizes {
        val out = File(dir, "layout_${System.nanoTime()}.pdf")
        return PdfNupBuilder.build(
            source = parts,
            totalSrcPages = parts.total,
            outFile = out,
            workDir = dir,
            config = singleUp,
            rotation = rotation,
            position = IntArray(parts.total) { 5 },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = { NoProgress }
        )!!
    }

    private fun readSizes(parts: PdfSourceParts): List<Pair<Float, Float>> =
        sizes(PdfPageSizes.read(parts, parts.total, 2, 1f, 1f, NoProgress))

    private fun sizes(sizes: PageSizes): List<Pair<Float, Float>> = sizes.widths.indices.map { sizes.widths[it] to sizes.heights[it] }

    private fun ownRotate(page: PdfPage): Int? =
        PdfEditProjection.privateData(page.pdfObject)?.getAsNumber(PdfName("OwnRotate"))?.intValue()

    private fun layerForm(page: PdfPage): PdfStream? =
        PdfEditProjection.privateData(page.pdfObject)?.getAsStream(PdfName("LayerForm"))

    private fun assertUnchangedSource() {
        PdfDocument(PdfReader(source(dir))).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(listOf(null, null), (1..2).map { ownRotate(pdf.getPage(it)) })
        }
        assertEquals("old layout", nup(dir).readText())
    }

    private fun sample(file: File, rotates: List<Int?>): File {
        file.parentFile?.mkdirs()
        PdfDocument(PdfWriter(file)).use { pdf ->
            for (rotate in rotates) {
                val page = pdf.addNewPage(PageSize(box))
                PdfCanvas(page).rectangle(mark).fill().release()
                if (rotate != null) page.pdfObject.put(PdfName.Rotate, PdfNumber(rotate))
            }
        }
        return file
    }

    private fun sourceDir(work: File) = File(work, "source_temp")

    private fun source(work: File) = File(sourceDir(work), "0001.pdf")

    private fun nup(work: File) = File(work, "nup_temp.pdf")

    private fun operation(
        work: File,
        sources: List<PdfImportOperation.Source>,
        config: NupConfig = singleUp,
        rotation: IntArray = IntArray(0),
        position: IntArray = IntArray(0),
        selected: List<Int> = emptyList(),
        imageToPdf: (File) -> File = { error("no image expected") },
        repair: PdfImportOperation.Repair = PdfImportOperation.Repair { _, _, _, _, _, _ -> error("no repair expected") }
    ) = PdfImportOperation(
        files = PdfLayoutChangeOperation.Files(
            work,
            sourceDir(work),
            nup(work),
            WorkFileBackup(work, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
        ),
        saveSelection = {},
        input = PdfImportOperation.Input(
            sources = sources,
            oldPerGroup = 2,
            config = config,
            crops = emptyMap(),
            rotation = rotation,
            position = position,
            defaultPosition = 5,
            batchSize = 2,
            selected = selected
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
        imageToPdf = imageToPdf,
        repair = repair
    )

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }

    private companion object {
        const val TOLERANCE = 0.05f
    }
}
