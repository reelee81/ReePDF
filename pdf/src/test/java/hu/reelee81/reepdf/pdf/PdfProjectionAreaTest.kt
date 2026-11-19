package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import hu.reelee81.reepdf.core.NupArea
import hu.reelee81.reepdf.core.NupBox
import hu.reelee81.reepdf.core.NupCellArea
import hu.reelee81.reepdf.core.NupCellLayout
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry
import hu.reelee81.reepdf.core.PdfCropRect
import java.io.File
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfProjectionAreaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private var saves = 0
    private val scaled = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)
    private val fitted = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = true)
    private val middle = PdfCropRect(0.25f, 0.25f, 0.75f, 0.75f)
    private val middleBox = NupArea(74.5, 105.0, 223.5, 315.0)
    private val content = Rectangle(60f, 150f, 140f, 172f)
    private val clip = Regex("""q\n((?:[-0-9.]+ ){6})cm\n((?:[-0-9.]+ ){4})re W n""")

    @Before
    fun setUp() {
        dir = temp.newFolder()
        File(dir, "source_temp").mkdirs()
        PdfDocument(PdfWriter(File(dir, "source_temp/0001.pdf"))).use { pdf ->
            val font = PdfFontFactory.createFont()
            for (number in 1..4) {
                PdfCanvas(pdf.addNewPage(PageSize.A6))
                    .setFillColorGray(0.6f).rectangle(60.0, 150.0, 140.0, 120.0).fill()
                    .setFillColorGray(0f)
                    .beginText().setFontAndSize(font, 28f).moveText(60.0, 300.0).showText("L$number").endText()
                    .release()
            }
        }
    }

    @Test
    fun aMarkOnOneLabelReachesOnlyThatLabelsSource() {
        build(scaled)
        val area = areas()[1]

        assertEquals(1, project(save(pen("p", area.left + 10, mid(area), area.right - 10, mid(area)))))

        assertEquals(listOf("p"), layerIds(1))
        for (index in listOf(0, 2, 3)) {
            assertNull("source $index got a layer", layer(index))
            assertNull("source $index got a content form", contentForm(index))
        }
    }

    @Test
    fun aNeighborsMarkStaysAwayWhenACroppedLabelGetsItsWholePageBack() {
        build(scaled, mapOf(0 to middle))
        val area = areas()[1]

        project(save(pen("p", area.left + 2, mid(area), (area.left + area.right) / 2, mid(area))))
        build(scaled)

        assertNull(layer(0))
        assertEquals(listOf("p"), layerIds(1))
        assertEquals(listOf("p"), sheetMarkIds())
    }

    @Test
    fun aMarkLeavingItsCroppedLabelStaysCutAtTheOldBorderAfterTheCropIsReset() {
        build(scaled, mapOf(0 to middle))
        val area = areas()[0]

        project(save(pen("p", (area.left + area.right) / 2, mid(area), area.right + 40, mid(area))))
        build(scaled)

        assertEquals(listOf("p"), layerIds(0))
        assertArea(middleBox, sourceClips(0).single())
    }

    @Test
    fun aMarkOnAnEnlargedLabelIsDrawnExactlyWhereItWasDrawnAfterTheProjection() {
        build(scaled, mapOf(0 to middle))
        val area = areas()[0]
        val drawn = doubleArrayOf(area.left + 20, mid(area), area.right - 20, mid(area) + 30)

        project(save(pen("p", drawn[0], drawn[1], drawn[2], drawn[3])))
        build(scaled, mapOf(0 to middle))

        val stroked = strokedPaths()
        assertEquals(1, stroked.size)
        for (index in drawn.indices) assertEquals("coordinate $index", drawn[index], stroked.single()[index], 0.01)
    }

    @Test
    fun aMarkAcrossTwoLabelsGivesEachLabelItsOwnPiece() {
        build(scaled)
        val (left, right) = areas().let { it[0] to it[1] }
        val y = (max(left.bottom, right.bottom) + min(left.top, right.top)) / 2

        assertEquals(2, project(save(pen("p", left.left + 20, y, right.right - 20, y))))

        assertEquals(listOf("p"), layerIds(0))
        assertEquals(listOf("p"), layerIds(1))
        assertNull(layer(2))
        assertNull(layer(3))
        for (index in 0..1) assertArea(NupArea(0.0, 0.0, 298.0, 420.0), sourceClips(index).single())
    }

    @Test
    fun aMarkBetweenTheLabelsReachesNoSource() {
        build(scaled)
        val (left, right) = areas().let { it[0] to it[1] }
        val x = (left.right + right.left) / 2

        assertEquals(0, project(save(pen("p", x, mid(left) - 30, x, mid(left) + 30))))

        for (index in 0..3) assertNull(layer(index))
    }

    @Test
    fun everyQuarterTurnSendsAMarkOnlyToItsOwnTurnedLabel() {
        val rotation = intArrayOf(1, 2, 3, 0)
        build(scaled, rotation = rotation)
        val marks = areas().mapIndexed { index, area ->
            pen("m$index", area.left + 10, mid(area), (area.left + area.right) / 2, mid(area) + 15)
        }

        assertEquals(4, project(save(*marks.toTypedArray())))

        for (index in 0..3) {
            assertEquals(listOf("m$index"), layerIds(index))
            assertArea(NupArea(0.0, 0.0, 298.0, 420.0), sourceClips(index).single())
        }
        build(scaled, rotation = rotation)
        assertEquals(List(4) { "m$it" }.sorted(), sheetMarkIds().sorted())
    }

    @Test
    fun inFitModeAMarkCountsOnlyInsideTheScaledContentOfItsLabel() {
        fittedSheet(withAreas = true)
        val slots = slots(fitted)
        val inside = areas()[1]
        val reachingOver = NupCellArea.mapped(NupBox(0f, 0f, 298f, 420f), place(fitted, 0).matrix)!!
        val y = inside.bottom + 10
        val band = (slots[1].y + slots[1].height - 4).toDouble()

        assertTrue(reachingOver.right > inside.left + 40 && reachingOver.bottom < y && reachingOver.top > y)
        assertTrue(inside.top < band - 6)

        val changed = project(
            save(
                highlighter(inside.left + 5, y, inside.left + 35, y),
                pen("band", inside.left + 20, band, inside.left + 60, band)
            )
        )

        assertEquals(1, changed)
        assertEquals(listOf("h"), layerIds(1))
        for (index in listOf(0, 2, 3)) assertNull("source $index got a layer", layer(index))
        assertArea(NupArea(60.0, 150.0, 200.0, 322.0), sourceClips(1).single())
    }

    @Test
    fun contentThatIsNotAMarkReachesEveryLabelCutToEach() {
        build(scaled)
        val edited = File(dir, "edited_text.pdf")
        PdfDocument(PdfReader(nup()), PdfWriter(edited)).use { pdf ->
            val page = pdf.getPage(1)
            val form = PdfFormXObject(Rectangle(page.mediaBox))
            PdfCanvas(form, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 10f).moveText(100.0, 600.0).showText("NOTE").endText()
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf).saveState().addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f).restoreState()
        }

        assertEquals(4, project(edited))

        for (index in 0..3) assertArea(NupArea(0.0, 0.0, 298.0, 420.0), sourceClips(index).single())
    }

    @Test
    fun aLayoutRecordWithoutAreasIsProjectedToEveryCellAsBefore() {
        fittedSheet(withAreas = false)
        val slot = slots(fitted)[0]

        assertEquals(4, project(save(pen("p", slot.x + 20.0, slot.y + slot.height / 2.0, slot.x + 60.0, slot.y + slot.height / 2.0))))

        for (index in 0..3) {
            assertEquals(listOf("p"), layerIds(index))
            assertTrue(sourceClips(index).isEmpty())
        }
    }

    @Test
    fun repeatedEditsOnALabelSheetKeepItsDepthAndEveryMarkOnItsOwnLabel() {
        build(scaled)
        var depthAfterFirst = -1
        for (round in 0 until 8) {
            val area = areas()[round % 4]
            project(save(pen("r$round", area.left + 10, mid(area) + round, area.right - 10, mid(area) + round)))
            build(scaled)
            val depth = maxDepth(nup())
            if (round == 0) depthAfterFirst = depth else assertEquals("round $round", depthAfterFirst, depth)
        }

        for (index in 0..3) assertEquals(listOf("r$index", "r${index + 4}"), layerIds(index))
    }

    @Test
    fun erasingEveryMarkOfAProjectedPieceRemovesTheLayer() {
        build(scaled)
        val area = areas()[2]
        project(save(pen("p", area.left + 10, mid(area), area.right - 10, mid(area))))
        build(scaled)
        val layers = PdfDocument(PdfReader(nup())).use { pdf ->
            PdfEditProjection.editStreams(pdf.getPage(1)).mapNotNull { stream ->
                stream.indirectReference?.objNumber?.takeIf { PdfMarkContent.read(stream.bytes).isNotEmpty() }
            }
        }

        project(saveStreams(layers.associateWith { PdfStreamEdit(false, setOf("p")) }))

        assertEquals(1, layers.size)
        assertNull(layer(2))
    }

    private fun build(config: NupConfig, crop: Map<Int, PdfCropRect> = emptyMap(), rotation: IntArray = IntArray(4)) {
        PdfNupBuilder.build(
            source = PdfSourceParts.open(File(dir, "source_temp")),
            totalSrcPages = 4,
            outFile = nup(),
            workDir = dir,
            config = config,
            rotation = rotation,
            position = IntArray(4) { NupGeometry.DEFAULT_POSITION },
            crop = crop,
            batchSize = 2,
            progressStage = { NoProgress }
        )
    }

    private fun fittedSheet(withAreas: Boolean) {
        val generation = PdfEditProjection.newGeneration()
        val source = PdfSourceParts.open(File(dir, "source_temp"))
        source.batch { src ->
            PdfDocument(PdfWriter(nup().absolutePath, PdfIo.writerProperties())).use { pdf ->
                val sheet = pdf.addNewPage(PageSize.A4)
                val canvas = PdfCanvas(sheet)
                val cells = ArrayList<PdfEditProjection.Cell>()
                for (index in 0..3) {
                    val page = src.page(index)
                    val box = PdfCopySupport.visibleBox(page)
                    val cell = place(fitted, index)
                    val m = cell.matrix
                    canvas.saveState()
                    PdfCopySupport.concatPlacementMatrix(canvas, m[0], m[1], m[2], m[3], m[4], m[5])
                    cells += if (withAreas) {
                        PdfEditProjection.Cell(index, m, content.nup(), NupBox(cell.x, cell.y, cell.width, cell.height))
                    } else {
                        PdfEditProjection.Cell(index, m)
                    }
                    val form = page.copyAsFormXObject(pdf)
                    form.bBox = PdfArray(box)
                    PdfEditProjection.tagLayerSource(form, page, index, generation)
                    canvas.addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                    canvas.restoreState()
                }
                PdfEditProjection.markNupPage(sheet, cells, generation)
            }
        }
    }

    private fun place(config: NupConfig, slot: Int) = NupCellLayout.place(
        config, PageSize.A4.width, PageSize.A4.height, slot,
        NupBox(0f, 0f, 298f, 420f), NupBox(0f, 0f, 298f, 420f), content.nup(), 0, NupGeometry.DEFAULT_POSITION
    )

    private fun slots(config: NupConfig) = (0..3).map { place(config, it).let { cell -> NupBox(cell.x, cell.y, cell.width, cell.height) } }

    private fun Rectangle.nup() = NupBox(x, y, width, height)

    private fun areas(): List<NupArea> = PdfDocument(PdfReader(nup())).use { pdf ->
        val base = PdfEditProjection.privateData(pdf.getPage(1).pdfObject)!!.getAsDictionary(PdfName("Base"))
        PdfEditProjection.cells(base).map { cell -> NupCellArea.of(cell.box!!, cell.matrix, cell.slot!!)!! }
    }

    private fun mid(area: NupArea) = (area.bottom + area.top) / 2

    private fun pen(id: String, x1: Double, y1: Double, x2: Double, y2: Double) = PdfMark(
        id, PdfMarkKind.PEN, 0xDD0000, 3f,
        PdfMarkPath.Builder().moveTo(x1.toFloat(), y1.toFloat()).lineTo(x2.toFloat(), y2.toFloat()).build()
    )

    private fun highlighter(x1: Double, y1: Double, x2: Double, y2: Double) = PdfMark(
        "h", PdfMarkKind.HIGHLIGHTER, 0xFFFF00, 14f,
        PdfMarkPath.Builder().moveTo(x1.toFloat(), y1.toFloat()).lineTo(x2.toFloat(), y2.toFloat()).build()
    )

    private fun save(vararg marks: PdfMark): File = write(PdfMarkSave.Plan(added = mapOf(0 to marks.toList())))

    private fun saveStreams(streams: Map<Int, PdfStreamEdit>): File = write(PdfMarkSave.Plan(streams = streams))

    private fun write(plan: PdfMarkSave.Plan): File {
        val output = File(dir, "edited_${++saves}.pdf")
        assertTrue(PdfMarkSave.write(nup(), output, plan, 2, ProgressTracker { 0L }.let { it::stage }) {})
        return output
    }

    private fun project(edited: File): Int =
        PdfEditProjection.project(PdfSourceParts.open(File(dir, "source_temp")), edited, 2, writeStage = { NoProgress }) {}.changed

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun sourcePage(index: Int): PdfDictionary? = PdfDocument(PdfReader(File(dir, "source_temp/0001.pdf"))).use { pdf ->
        PdfEditProjection.privateData(pdf.getPage(index + 1).pdfObject)
    }

    private fun layer(index: Int): ByteArray? = PdfDocument(PdfReader(File(dir, "source_temp/0001.pdf"))).use { pdf ->
        PdfEditProjection.privateData(pdf.getPage(index + 1).pdfObject)?.getAsStream(PdfName("LayerForm"))?.bytes
    }

    private fun contentForm(index: Int): PdfStream? = sourcePage(index)?.getAsStream(PdfName("ContentForm"))

    private fun layerIds(index: Int): List<String>? = layer(index)?.let { content -> PdfMarkContent.read(content).map { it.mark.id } }

    private fun sourceClips(index: Int): List<NupArea> {
        val text = String(layer(index) ?: return emptyList(), Charsets.ISO_8859_1)
        return clip.findAll(text).map { match ->
            val m = match.groupValues[1].trim().split(' ').map { it.toDouble() }
            val (x, y, w, h) = match.groupValues[2].trim().split(' ').map { it.toDouble() }
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (px in doubleArrayOf(x, x + w)) {
                for (py in doubleArrayOf(y, y + h)) {
                    xs += m[0] * px + m[2] * py + m[4]
                    ys += m[1] * px + m[3] * py + m[5]
                }
            }
            NupArea(xs.min(), ys.min(), xs.max(), ys.max())
        }.toList()
    }

    private fun strokedPaths(): List<DoubleArray> = PdfDocument(PdfReader(nup())).use { pdf ->
        val paths = ArrayList<DoubleArray>()
        val listener = object : IEventListener {
            override fun eventOccurred(data: IEventData?, type: EventType?) {
                if (data !is PathRenderInfo || data.operation and PathRenderInfo.STROKE == 0) return
                val ctm = data.ctm
                paths += data.path.subpaths.flatMap { subpath ->
                    listOf(subpath.startPoint) + subpath.segments.flatMap { it.basePoints.drop(1) }
                }.flatMap { point ->
                    val vector = Vector(point.x.toFloat(), point.y.toFloat(), 1f).cross(ctm)
                    listOf(vector.get(0).toDouble(), vector.get(1).toDouble())
                }.toDoubleArray()
            }

            override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_PATH)
        }
        PdfCanvasProcessor(listener).processPageContent(pdf.getPage(1))
        paths
    }

    private fun sheetMarkIds(): List<String> = PdfDocument(PdfReader(nup())).use { pdf ->
        PdfEditProjection.editStreams(pdf.getPage(1)).flatMap { stream -> PdfMarkContent.read(stream.bytes).map { it.mark.id } }
    }

    private fun assertArea(expected: NupArea, actual: NupArea) {
        assertEquals("left", expected.left, actual.left, 0.01)
        assertEquals("bottom", expected.bottom, actual.bottom, 0.01)
        assertEquals("right", expected.right, actual.right, 0.01)
        assertEquals("top", expected.top, actual.top, 0.01)
    }

    private fun maxDepth(file: File): Int = PdfDocument(PdfReader(file)).use { pdf ->
        (1..pdf.numberOfPages).maxOf { depth(pdf.getPage(it).resources.pdfObject, 0, HashSet()) }
    }

    private fun depth(resources: PdfDictionary?, level: Int, seen: MutableSet<Int>): Int {
        val xObjects = resources?.getAsDictionary(PdfName.XObject) ?: return level
        var deepest = level
        for (name in xObjects.keySet()) {
            val stream = xObjects.getAsStream(name) ?: continue
            if (stream.getAsName(PdfName.Subtype) != PdfName.Form) continue
            val id = System.identityHashCode(stream)
            if (!seen.add(id)) continue
            deepest = max(deepest, depth(stream.getAsDictionary(PdfName.Resources), level + 1, seen))
            seen.remove(id)
        }
        return deepest
    }

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }
}
