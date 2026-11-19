package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
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

class PdfMarkSaveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private var saves = 0
    private val config = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
        File(dir, "source_temp").mkdirs()
        PdfDocument(PdfWriter(File(dir, "source_temp/0001.pdf"))).use { pdf ->
            repeat(3) { index ->
                PdfCanvas(pdf.addNewPage(PageSize(200f + index, 300f))).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
        PdfNupBuilder.build(
            source = PdfSourceParts.open(File(dir, "source_temp")),
            totalSrcPages = 3,
            outFile = nup(),
            workDir = dir,
            config = config,
            rotation = IntArray(3),
            position = IntArray(3) { 5 },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = ProgressTracker { 0L }.let { it::stage }
        )
    }

    @Test
    fun aSaveAppendsTheMarksBehindTheUntouchedFileAsAWorkFile() {
        val edited = save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("p", 100f, 300f), highlighter("h", 500f)))))

        assertArrayEquals(nup().readBytes(), edited.readBytes().copyOf(nup().length().toInt()))
        assertEquals(listOf("p", "h"), editData(edited, 0).read().marks.map { it.mark.id })
        assertTrue(editData(edited, 1).read().marks.isEmpty())
        PdfDocument(PdfReader(edited)).use { pdf ->
            val states = pdf.getPage(1).resources.pdfObject.getAsDictionary(PdfName.ExtGState)
            val alphas = states.keySet().map { states.getAsDictionary(it).getAsNumber(PdfName.CA)?.floatValue() }
            assertTrue(alphas.toString(), PdfMark.ALPHA in alphas)
        }
    }

    @Test
    fun savedMarksReachOnlyTheSourceTheyAreDrawnOnAndReadBackInPlace() {
        val drawn = pen("p", 150f, 300f)

        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(drawn)))))

        assertEquals(listOf("p"), sourceLayerIds(0))
        assertNull(sourceLayerIds(1))
        assertNull(sourceLayerIds(2))
        val marks = editData(nup(), 0).read().marks
        assertEquals(listOf("p"), marks.map { it.mark.id })
        for (mark in marks) {
            assertEquals(PdfMarkKind.PEN, mark.mark.kind)
            assertEquals(0xDD0000, mark.mark.color)
            assertEquals(3f, mark.mark.width, 0.01f)
            assertArrayEquals(drawn.path.verbs, mark.mark.path.verbs)
            assertArrayEquals(drawn.path.points, mark.mark.path.points, 0.01f)
        }
    }

    @Test
    fun aMarkAcrossTwoLabelsReachesBothSourcesEachCutToItsOwnLabel() {
        val drawn = across("x", 300f)

        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(drawn)))))

        assertEquals(listOf("x"), sourceLayerIds(0))
        assertEquals(listOf("x"), sourceLayerIds(1))
        assertNull(sourceLayerIds(2))
        for (index in 0..1) {
            val clip = Regex("([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+) re W n").find(String(sourceLayer(index)!!, Charsets.ISO_8859_1))
            assertNotNull("source $index has no clip", clip)
            val left = clip!!.groupValues[1].toFloat()
            val width = clip.groupValues[3].toFloat()
            if (index == 0) assertTrue("left label clip $left + $width", left + width < 421f) else assertTrue("right label clip $left", left > 421f)
        }
        val marks = editData(nup(), 0).read().marks
        assertEquals(listOf("x", "x"), marks.map { it.mark.id })
        for (mark in marks) assertArrayEquals(drawn.path.points, mark.mark.path.points, 0.01f)
        val clips = marks.map { it.clip!! }.sortedBy { it[0] }
        assertTrue("left piece ${clips[0].toList()}", clips[0][0] > 139f && clips[0][2] < 282f && clips[0][1] > 191f && clips[0][3] < 404f)
        assertTrue("right piece ${clips[1].toList()}", clips[1][0] > 560f && clips[1][2] < 703f)
    }

    @Test
    fun aMarkBesideItsLabelReachesNoSource() {
        val edited = save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("p", 20f, 500f)))))

        project(edited)

        assertNull(sourceLayerIds(0))
        assertNull(sourceLayerIds(1))
        assertTrue(editData(nup(), 0).read().marks.isEmpty())
    }

    @Test
    fun erasingAProjectedMarkWritesTheChangedLayerBack() {
        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("a", 150f, 300f), across("b", 250f))))))
        val layers = editData(nup(), 0).layers.map { it.number }

        val edited = save(nup(), PdfMarkSave.Plan(streams = layers.associateWith { PdfStreamEdit(false, setOf("a")) }))

        assertEquals(2, layers.size)
        PdfDocument(PdfReader(edited)).use { pdf ->
            for (number in layers) {
                val layer = pdf.getPdfObject(number) as PdfStream
                assertEquals(true, PdfEditProjection.privateData(layer)?.getAsBool(PdfEditProjection.EDITED))
            }
        }
        project(edited)
        assertEquals(listOf("b"), sourceLayerIds(0))
        assertEquals(listOf("b"), sourceLayerIds(1))
        assertEquals(listOf("b", "b"), editData(nup(), 0).read().marks.map { it.mark.id })
    }

    @Test
    fun erasingEveryMarkOfALayerRemovesTheLayer() {
        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("a", 150f, 300f), across("b", 250f))))))
        val layers = editData(nup(), 0).layers.map { it.number }

        project(save(nup(), PdfMarkSave.Plan(streams = layers.associateWith { PdfStreamEdit(false, setOf("a", "b")) })))

        assertEquals(2, layers.size)

        assertFalse(hasLayer(0))
        assertFalse(hasLayer(1))
        assertTrue(editData(nup(), 0).read().marks.isEmpty())
    }

    @Test
    fun anEditedLayerCopyWithNothingDrawnRemovesTheLayer() {
        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(across("a", 300f))))))
        val layer = editData(nup(), 0).layers.first { it.sourceIndex == 0 }.number
        val edited = File(dir, "edited_blank.pdf")
        PdfDocument(PdfReader(nup()), PdfIo.workFileWriter(edited.path), StampingProperties().useAppendMode()).use { pdf ->
            val stream = pdf.getPdfObject(layer) as PdfStream
            stream.remove(PdfName.Filter)
            stream.setData("q 1 0 0 1 0 0 cm\nQ\n".toByteArray())
            PdfEditProjection.writablePrivateData(stream).put(PdfEditProjection.EDITED, PdfBoolean.TRUE)
            stream.setModified()
        }

        project(edited)

        assertFalse(hasLayer(0))
        assertEquals(listOf("a"), sourceLayerIds(1))
    }

    @Test
    fun erasingAMarkBeforeItsProjectionEmptiesItsStreamAndNothingReachesTheSource() {
        val first = save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("a", 150f, 300f)))))
        val appended = editData(first, 0).appended.map { it.number }

        val second = save(first, PdfMarkSave.Plan(streams = appended.associateWith { PdfStreamEdit(false, setOf("a")) }))

        assertEquals(1, appended.size)
        assertTrue(editData(second, 0).read().marks.isEmpty())
        assertEquals(0, editData(second, 0).appended.single().content.size)
        project(second)
        assertFalse(hasLayer(0))
        assertFalse(hasLayer(1))
    }

    @Test
    fun blankingPagesEmptiesOnlyTheirEditsAndNewMarksStillArrive() {
        project(save(nup(), PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("a", 150f, 300f)), 1 to listOf(pen("c", 160f, 300f))))))

        project(save(nup(), PdfMarkSave.Plan(blankPages = setOf(0), added = mapOf(0 to listOf(highlighter("d", 120f, 530f))))))

        assertEquals(listOf("d"), sourceLayerIds(0))
        assertEquals(listOf("d"), sourceLayerIds(1))
        assertEquals(listOf("c"), sourceLayerIds(2))
        assertEquals(PdfMarkKind.HIGHLIGHTER, editData(nup(), 0).read().marks.first().mark.kind)

        project(save(nup(), PdfMarkSave.Plan(blankAll = true)))

        assertFalse(hasLayer(0))
        assertFalse(hasLayer(1))
        assertFalse(hasLayer(2))
    }

    @Test
    fun blankingAFileWithoutEditsWritesNothing() {
        val output = File(dir, "edited_nothing.pdf")

        val written = PdfMarkSave.write(nup(), output, PdfMarkSave.Plan(blankAll = true), 2, ProgressTracker { 0L }.let { it::stage }) {}

        assertFalse(written)
        assertFalse(output.exists())
    }

    @Test
    fun aPlanWithoutChangesIsEmpty() {
        assertTrue(PdfMarkSave.Plan().isEmpty)
        assertTrue(PdfMarkSave.Plan(added = mapOf(0 to emptyList()), streams = mapOf(5 to PdfStreamEdit.ORIGINAL)).isEmpty)
        assertFalse(PdfMarkSave.Plan(blankPages = setOf(0)).isEmpty)
        assertFalse(PdfMarkSave.Plan(streams = mapOf(5 to PdfStreamEdit.BLANK)).isEmpty)
    }

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun pen(id: String, x: Float, y: Float) = PdfMark(
        id, PdfMarkKind.PEN, 0xDD0000, 3f,
        PdfMarkPath.Builder().moveTo(x, y).curveTo(x + 10f, y + 10f, x + 20f, y - 10f, x + 30f, y).build()
    )

    private fun across(id: String, y: Float) = PdfMark(
        id, PdfMarkKind.PEN, 0xDD0000, 3f,
        PdfMarkPath.Builder().moveTo(200f, y).lineTo(650f, y).build()
    )

    private fun highlighter(id: String, x: Float, length: Float = 40f) = PdfMark(
        id, PdfMarkKind.HIGHLIGHTER, 0xFFED45, 20f,
        PdfMarkPath.Builder().moveTo(x, 200f).lineTo(x + length, 200f).build()
    )

    private fun save(input: File, plan: PdfMarkSave.Plan): File {
        val output = File(dir, "edited_${++saves}.pdf")
        assertTrue(PdfMarkSave.write(input, output, plan, 2, ProgressTracker { 0L }.let { it::stage }) {})
        TestPdfs.assertWorkFile(output)
        return output
    }

    private fun project(edited: File) {
        runBlocking {
            PdfViewerEditOperation(
                files = PdfLayoutChangeOperation.Files(
                    dir, File(dir, "source_temp"), nup(),
                    WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
                ),
                saveSelection = {},
                input = PdfViewerEditOperation.Input(
                    config = config, builtConfig = config, crops = emptyMap(),
                    rotation = IntArray(3), position = IntArray(3) { 5 }, defaultPosition = 5,
                    batchSize = 2, selected = emptyList()
                ),
                progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
                copyEdited = { target -> edited.copyTo(target, overwrite = true) }
            ).execute {}
        }
    }

    private fun sourceLayer(sourceIndex: Int): ByteArray? =
        PdfDocument(PdfReader(File(dir, "source_temp/0001.pdf"))).use { pdf ->
            PdfEditProjection.privateData(pdf.getPage(sourceIndex + 1).pdfObject)?.getAsStream(PdfName("LayerForm"))?.bytes
        }

    private fun hasLayer(sourceIndex: Int) = sourceLayer(sourceIndex) != null

    private fun sourceLayerIds(sourceIndex: Int): List<String>? =
        sourceLayer(sourceIndex)?.let { content -> PdfMarkContent.read(content).map { it.mark.id } }

    private fun editData(file: File, pageIndex: Int): PdfPageEditData = PdfDocument(PdfReader(file)).use { pdf ->
        val page = pdf.getPage(pageIndex + 1)
        val base = PdfEditProjection.privateData(page.pdfObject)!!.getAsDictionary(PdfName("Base"))
        val streams = base.getAsNumber(PdfName("Streams")).intValue()
        val generation = base.getAsString(PdfName("Gen")).value
        val cellValues = base.getAsArray(PdfName("Cells"))
        val appended = (streams until page.contentStreamCount).map { index ->
            val stream = page.getContentStream(index)
            PdfPageEditData.Stream(stream.indirectReference.objNumber, stream.bytes)
        }
        val layers = ArrayList<PdfPageEditData.Stream>()
        val seen = HashSet<Int>()
        fun visit(resources: PdfDictionary?, depth: Int) {
            if (depth >= 8) return
            val xObjects = resources?.getAsDictionary(PdfName.XObject) ?: return
            for (name in xObjects.keySet()) {
                val stream = xObjects.getAsStream(name) ?: continue
                val number = stream.indirectReference?.objNumber ?: continue
                if (!seen.add(number)) continue
                val data = PdfEditProjection.privateData(stream)
                if (data?.getAsBool(PdfName("Layer")) == true) {
                    if (data.getAsString(PdfName("Gen"))?.value == generation) {
                        layers += PdfPageEditData.Stream(number, stream.bytes, data.getAsNumber(PdfName("Source")).intValue())
                    }
                } else if (stream.getAsName(PdfName.Subtype) == PdfName.Form) {
                    visit(stream.getAsDictionary(PdfName.Resources), depth + 1)
                }
            }
        }
        visit(page.resources.pdfObject, 0)
        PdfPageEditData(
            PdfMatrix.IDENTITY,
            PdfPageEditData.cells(
                FloatArray(cellValues.size()) { cellValues.getAsNumber(it).floatValue() },
                base.getAsArray(PdfName("Areas"))?.let { areas -> FloatArray(areas.size()) { areas.getAsNumber(it).floatValue() } }
            ),
            appended,
            layers
        )
    }
}
