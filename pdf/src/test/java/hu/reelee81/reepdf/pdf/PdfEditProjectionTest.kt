package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfEditProjectionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val rotatedHalf = doubleArrayOf(0.0, 0.5, -0.5, 0.0, 450.0, 100.0)

    @Test
    fun inverseUndoesThePlacement() {
        val inverse = PdfEditProjection.inverse(rotatedHalf)!!
        val x = 120.0
        val y = 300.0
        val px = rotatedHalf[0] * x + rotatedHalf[2] * y + rotatedHalf[4]
        val py = rotatedHalf[1] * x + rotatedHalf[3] * y + rotatedHalf[5]
        assertEquals(x, inverse[0] * px + inverse[2] * py + inverse[4], 1e-9)
        assertEquals(y, inverse[1] * px + inverse[3] * py + inverse[5], 1e-9)
        assertNull(PdfEditProjection.inverse(doubleArrayOf(1.0, 2.0, 2.0, 4.0, 0.0, 0.0)))
    }

    @Test
    fun tenEditsStayOnTheSourcePageAtTheirPlaceWithoutDeeperNesting() {
        var source = makeSource(2)
        var depthAfterFirst = -1
        for (round in 1..10) {
            val nup = buildNup(source)
            val edited = edit(nup, "EDIT $round", 150f, 120f + round * 20f)
            val projected = temporary.newFile("source_$round.pdf")
            val changed = projectFile(source, edited, projected)
            assertEquals(1, changed)
            source = projected
            val depth = maxDepth(buildNup(source))
            if (round == 1) depthAfterFirst = depth else assertEquals(depthAfterFirst, depth)
        }

        val finalNup = buildNup(source)
        val positions = textPositions(finalNup, 1)
        for (round in 1..10) {
            val position = positions["EDIT $round"]
            assertNotNull("EDIT $round is missing", position)
            assertEquals(150f, position!![0], 0.05f)
            assertEquals(120f + round * 20f, position[1], 0.05f)
        }
        assertTrue(textPositions(finalNup, 2).keys.none { it.startsWith("EDIT") })

        PdfDocument(PdfReader(source)).use { pdf ->
            val first = pdf.getPage(1).pdfObject
            val editForm = layerForm(pdf.getPage(1))
            assertNotNull(editForm)
            val names = editForm!!.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).keySet()
            assertEquals(10, names.size)
            assertEquals(1, first.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).keySet().size)
            assertEquals(2, pdf.getPage(1).contentStreamCount)
            assertNull(layerForm(pdf.getPage(2)))
        }
    }

    @Test
    fun clearingTheVisiblePageRemovesOnlyItsEditsFromTheSource() {
        var source = makeSource(2)
        source = projectEdit(source, "FIRST PAGE EDIT", 1)
        source = projectEdit(source, "SECOND PAGE EDIT", 2)

        val nup = buildNup(source)
        assertTrue(hasLayers(nup, setOf(1)))
        val cleared = temporary.newFile()
        assertEquals(1, PdfEditProjection.clearLayers(nup, cleared, setOf(1), batchSize = 1) {})
        assertFalse(hasLayers(cleared, setOf(1)))
        assertTrue(hasLayers(cleared, setOf(2)))
        assertTrue(hasLayers(cleared, null))
        assertFalse(textPositions(cleared, 1).containsKey("FIRST PAGE EDIT"))
        assertTrue(textPositions(cleared, 2).containsKey("SECOND PAGE EDIT"))

        val projected = temporary.newFile()
        assertEquals(1, projectFile(source, cleared, projected))
        PdfDocument(PdfReader(projected)).use { pdf ->
            val first = pdf.getPage(1)
            assertNull(layerForm(first))
            assertEquals(1, first.contentStreamCount)
            assertEquals(0, first.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).size())
            assertNotNull(layerForm(pdf.getPage(2)))
        }
        val finalNup = buildNup(projected)
        assertFalse(textPositions(finalNup, 1).containsKey("FIRST PAGE EDIT"))
        assertTrue(textPositions(finalNup, 1).containsKey("SOURCE 1"))
        assertTrue(textPositions(finalNup, 2).containsKey("SECOND PAGE EDIT"))
    }

    @Test
    fun clearingAllAndDrawingAgainKeepsOnlyTheNewEdit() {
        var source = makeSource(2)
        source = projectEdit(source, "OLD ONE", 1)
        source = projectEdit(source, "OLD TWO", 2)

        val cleared = temporary.newFile()
        assertEquals(2, PdfEditProjection.clearLayers(buildNup(source), cleared, null, batchSize = 1) {})
        assertFalse(hasLayers(cleared, null))
        val edited = edit(cleared, "NEW ONE", 150f, 200f)
        val projected = temporary.newFile()
        assertEquals(2, projectFile(source, edited, projected))

        val finalNup = buildNup(projected)
        val first = textPositions(finalNup, 1)
        assertTrue(first.containsKey("NEW ONE"))
        assertFalse(first.containsKey("OLD ONE"))
        assertFalse(textPositions(finalNup, 2).containsKey("OLD TWO"))
        PdfDocument(PdfReader(projected)).use { pdf ->
            val layer = layerForm(pdf.getPage(1))!!
            assertEquals(1, layer.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).size())
            assertNull(layerForm(pdf.getPage(2)))
        }
    }

    @Test
    fun clearingPagesWithoutEditsChangesNothing() {
        val source = makeSource(2)
        assertFalse(hasLayers(buildNup(source), null))
        val cleared = temporary.newFile()
        assertEquals(0, PdfEditProjection.clearLayers(buildNup(source), cleared, null, batchSize = 1) {})
        assertEquals(0, projectFile(source, cleared, temporary.newFile()))
    }

    @Test
    fun clearingRemovesEditsNotYetProjectedToTheSource() {
        val source = makeSource(2)
        val edited = edit(edit(buildNup(source), "FIRST PAGE EDIT", 150f, 200f, 1), "SECOND PAGE EDIT", 150f, 200f, 2)
        assertTrue(hasLayers(edited, setOf(1)))

        val cleared = temporary.newFile()
        assertEquals(1, PdfEditProjection.clearLayers(edited, cleared, setOf(1), batchSize = 1) {})
        assertFalse(hasLayers(cleared, setOf(1)))
        assertTrue(hasLayers(cleared, setOf(2)))
        assertFalse(textPositions(cleared, 1).containsKey("FIRST PAGE EDIT"))
        assertTrue(textPositions(cleared, 1).containsKey("SOURCE 1"))
        assertTrue(textPositions(cleared, 2).containsKey("SECOND PAGE EDIT"))
        PdfDocument(PdfReader(cleared)).use { pdf ->
            assertEquals(1, pdf.getPage(1).contentStreamCount)
            assertNotNull(base(pdf.getPage(1)))
        }

        val projected = temporary.newFile()
        assertEquals(1, projectFile(source, cleared, projected))
        PdfDocument(PdfReader(projected)).use { pdf ->
            assertNull(layerForm(pdf.getPage(1)))
            assertNotNull(layerForm(pdf.getPage(2)))
        }
    }

    @Test
    fun clearingKeepsAppendedContentWhenTheOriginalWasModified() {
        val modified = temporary.newFile()
        PdfDocument(PdfReader(edit(buildNup(makeSource(1)), "EDIT", 150f, 200f)), PdfWriter(modified)).use { pdf ->
            val stream = pdf.getPage(1).getContentStream(0)
            stream.setData(stream.bytes + "\n0 0 m 10 10 l S\n".toByteArray())
        }
        assertFalse(hasLayers(modified, null))
        val cleared = temporary.newFile()
        assertEquals(0, PdfEditProjection.clearLayers(modified, cleared, null, batchSize = 1) {})
        assertTrue(textPositions(cleared, 1).containsKey("EDIT"))
    }

    @Test
    fun anEditInOnePartRewritesOnlyThatPart() {
        val whole = makeSource(4)
        val parts = temporary.newFolder()
        PdfDocument(PdfReader(whole)).use { src ->
            for ((index, range) in listOf(1..2, 3..4).withIndex()) {
                PdfDocument(PdfWriter(File(parts, "000${index + 1}.pdf"))).use { out -> src.copyPagesTo(range.first, range.last, out) }
            }
        }
        val untouched = File(parts, "0001.pdf").readBytes()
        val edited = edit(buildNup(whole), "THIRD PAGE EDIT", 150f, 200f, 3)

        val result = PdfEditProjection.project(PdfSourceParts.open(parts), edited, batchSize = 1, writeStage = { NoProgress }) {}

        assertEquals(1, result.changed)
        assertEquals(listOf(2, 2), result.source.layout.pageCounts)
        assertArrayEquals(untouched, File(parts, "0001.pdf").readBytes())
        PdfDocument(PdfReader(File(parts, "0002.pdf"))).use { pdf ->
            assertNotNull(layerForm(pdf.getPage(1)))
            assertNull(layerForm(pdf.getPage(2)))
        }
    }

    private fun projectFile(source: File, edited: File, output: File): Int {
        val parts = temporary.newFolder()
        source.copyTo(File(parts, "0001.pdf"))
        val result = PdfEditProjection.project(PdfSourceParts.open(parts), edited, batchSize = 1, writeStage = { NoProgress }) {}
        if (result.changed > 0) result.source.file(0).copyTo(output, overwrite = true)
        return result.changed
    }

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }

    private fun hasLayers(file: File, pages: Set<Int>?): Boolean =
        PdfEditProjection.hasLayers({ PdfReader(file) }, pages, batchSize = 1) {}

    private fun projectEdit(source: File, text: String, pageNumber: Int): File {
        val edited = edit(buildNup(source), text, 150f, 200f, pageNumber)
        val projected = temporary.newFile()
        projectFile(source, edited, projected)
        return projected
    }

    @Test
    fun aPageWithoutEditsIsCopiedUnchanged() {
        val source = makeSource(1)
        val nup = buildNup(source)
        val output = temporary.newFile("unchanged.pdf")
        assertEquals(0, projectFile(source, nup, output))
    }

    @Test
    fun modifiedOriginalContentOrMissingLayoutRecordIsNotProjected() {
        val source = makeSource(1)
        val nup = buildNup(source)
        val modified = temporary.newFile("modified.pdf")
        PdfDocument(PdfReader(nup), PdfWriter(modified)).use { pdf ->
            val page = pdf.getPage(1)
            val stream = page.getContentStream(0)
            stream.setData(stream.bytes + "\n0 0 m 10 10 l S\n".toByteArray())
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf).moveTo(0.0, 0.0).lineTo(5.0, 5.0).stroke()
        }
        assertNotProjectable(source, modified)

        val unmarked = temporary.newFile("unmarked.pdf")
        PdfDocument(PdfReader(edit(nup, "EDIT", 150f, 200f)), PdfWriter(unmarked)).use { pdf ->
            privateData(pdf.getPage(1).pdfObject)!!.remove(PdfName("Base"))
        }
        assertNotProjectable(source, unmarked)
    }

    @Test
    fun recordedCellsKeepTheExactPlacement() {
        val nup = buildNup(makeSource(1))
        PdfDocument(PdfReader(nup)).use { pdf ->
            val cells = base(pdf.getPage(1))!!.getAsArray(PdfName("Cells"))
            assertEquals(0, cells.getAsNumber(0).intValue())
            assertArrayEquals(rotatedHalf, DoubleArray(6) { cells.getAsNumber(it + 1).doubleValue() }, 1e-9)
        }
    }

    private fun assertNotProjectable(source: File, nup: File) {
        try {
            projectFile(source, nup, temporary.newFile())
            fail("expected NotProjectableException")
        } catch (_: PdfEditProjection.NotProjectableException) {
        }
    }

    private fun makeSource(pages: Int): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val font = PdfFontFactory.createFont()
            repeat(pages) { index ->
                PdfCanvas(pdf.addNewPage(PageSize.A4)).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 780.0).showText("SOURCE ${index + 1}").endText()
            }
        }
        return file
    }

    @Test
    fun editsInsideAnOutputLoadedAsANewSourceAreNotEditableLayers() {
        val edited = projectEdit(makeSource(2), "SAVED EDIT", 1)
        val exported = buildNup(edited)
        assertTrue(hasLayers(exported, null))

        val reloaded = temporary.newFile()
        PdfDocument(PdfReader(exported)).use { src ->
            PdfDocument(PdfWriter(reloaded)).use { out -> src.copyPagesTo(1, src.numberOfPages, out) }
        }
        val nup = buildNup(reloaded)
        assertTrue(textPositions(nup, 1).containsKey("SAVED EDIT"))
        assertFalse(hasLayers(nup, null))
        assertEquals(0, PdfEditProjection.clearLayers(nup, temporary.newFile(), null, batchSize = 1) {})
        assertEquals(0, projectFile(reloaded, nup, temporary.newFile()))

        val newEdit = projectEdit(reloaded, "NEW EDIT", 1)
        val cleared = temporary.newFile()
        assertEquals(1, PdfEditProjection.clearLayers(buildNup(newEdit), cleared, null, batchSize = 1) {})
        assertTrue(textPositions(cleared, 1).containsKey("SAVED EDIT"))
        assertFalse(textPositions(cleared, 1).containsKey("NEW EDIT"))
    }

    @Test
    fun reloadedEditedOutputDoesNotGetDeeperWhenSavedOrEditedAgain() {
        var exported = buildNup(projectEdit(makeSource(1), "EDIT 0", 1), flatten = true)
        val depth = maxDepth(exported)

        for (round in 1..5) {
            val resaved = buildNup(reload(exported), flatten = true)
            assertEquals("resave in round $round", depth, maxDepth(resaved))

            exported = buildNup(projectEdit(reload(resaved), "EDIT $round", 1), flatten = true)
            assertEquals("new edit in round $round", depth, maxDepth(exported))

            val texts = textPositions(exported, 1)
            assertTrue(texts.containsKey("SOURCE 1"))
            for (previous in 0..round) assertTrue("EDIT $previous is missing", texts.containsKey("EDIT $previous"))

            val cleared = temporary.newFile()
            assertEquals(1, PdfEditProjection.clearLayers(exported, cleared, null, batchSize = 1) {})
            val left = textPositions(cleared, 1)
            assertFalse(left.containsKey("EDIT $round"))
            for (previous in 0 until round) assertTrue("EDIT $previous was cleared", left.containsKey("EDIT $previous"))
        }
    }

    @Test
    fun reloadedFramedOutputDoesNotGetDeeperWhenSavedOrEditedAgain() {
        var plain = buildNup(makeSource(1), flatten = true, frame = true)
        val plainDepth = maxDepth(plain)
        var exported = buildNup(projectEdit(makeSource(1), "EDIT 0", 1), flatten = true, frame = true)
        val depth = maxDepth(exported)

        for (round in 1..5) {
            plain = buildNup(reload(plain), flatten = true, frame = true)
            assertEquals("frame only in round $round", plainDepth, maxDepth(plain))
            assertEquals(round + 1, strokeCount(plain))

            val resaved = buildNup(reload(exported), flatten = true, frame = true)
            assertEquals("resave in round $round", depth, maxDepth(resaved))

            exported = buildNup(projectEdit(reload(resaved), "EDIT $round", 1), flatten = true, frame = true)
            assertEquals("new edit in round $round", depth, maxDepth(exported))

            val texts = textPositions(exported, 1)
            assertTrue(texts.containsKey("SOURCE 1"))
            for (previous in 0..round) assertTrue("EDIT $previous is missing", texts.containsKey("EDIT $previous"))
        }
    }

    private fun strokeCount(file: File): Int {
        var count = 0
        PdfDocument(PdfReader(file)).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is PathRenderInfo && data.operation and PathRenderInfo.STROKE != 0) count++
                }

                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_PATH)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(1))
        }
        return count
    }

    private fun reload(file: File): File {
        val reloaded = temporary.newFile()
        PdfDocument(PdfReader(file)).use { src ->
            PdfDocument(PdfWriter(reloaded)).use { out -> src.copyPagesTo(1, src.numberOfPages, out) }
        }
        return reloaded
    }

    private fun privateData(dictionary: PdfDictionary): PdfDictionary? =
        dictionary.getAsDictionary(PdfName.PieceInfo)
            ?.getAsDictionary(PdfName("hureelee81reepdf"))
            ?.getAsDictionary(PdfName.Private)

    private fun layerForm(page: PdfPage): PdfStream? = privateData(page.pdfObject)?.getAsStream(PdfName("LayerForm"))

    private fun base(page: PdfPage): PdfDictionary? = privateData(page.pdfObject)?.getAsDictionary(PdfName("Base"))

    private fun buildNup(source: File, flatten: Boolean = false, frame: Boolean = false): File {
        val file = temporary.newFile()
        val generation = PdfEditProjection.newGeneration()
        PdfDocument(PdfWriter(file)).use { out ->
            PdfDocument(PdfReader(source)).use { src ->
                for (number in 1..src.numberOfPages) {
                    val sourcePage = src.getPage(number)
                    val page = out.addNewPage(PageSize.A4)
                    val canvas = PdfCanvas(page)
                    val box = PdfCopySupport.visibleBox(sourcePage)
                    val form = (if (flatten) PdfCopySupport.flattenedFormXObject(sourcePage, out, box) else null)
                        ?: sourcePage.copyAsFormXObject(out)
                    form.bBox = PdfArray(box)
                    canvas.saveState()
                    PdfCopySupport.concatPlacementMatrix(
                        canvas, rotatedHalf[0], rotatedHalf[1], rotatedHalf[2],
                        rotatedHalf[3], rotatedHalf[4], rotatedHalf[5]
                    )
                    PdfEditProjection.tagLayerSource(form, sourcePage, number - 1, generation)
                    canvas.addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                    canvas.restoreState()
                    if (frame) {
                        canvas.setStrokeColor(ColorConstants.BLACK)
                            .setLineWidth(1f)
                            .rectangle(20.0, 20.0, 555.0, 802.0)
                            .stroke()
                    }
                    PdfEditProjection.markNupPage(page, listOf(PdfEditProjection.Cell(number - 1, rotatedHalf)), generation)
                }
            }
        }
        return file
    }

    private fun edit(nup: File, text: String, x: Float, y: Float, pageNumber: Int = 1): File {
        val file = temporary.newFile()
        PdfDocument(PdfReader(nup), PdfWriter(file)).use { pdf ->
            val page = pdf.getPage(pageNumber)
            val form = PdfFormXObject(Rectangle(page.mediaBox))
            PdfCanvas(form, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 10f)
                .moveText(x.toDouble(), y.toDouble()).showText(text).endText()
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf)
                .saveState()
                .addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                .restoreState()
        }
        return file
    }

    private fun textPositions(file: File, pageNumber: Int): Map<String, FloatArray> {
        val result = HashMap<String, FloatArray>()
        PdfDocument(PdfReader(file)).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo) {
                        val start = data.baseline.startPoint
                        result[data.text] = floatArrayOf(start.get(0), start.get(1))
                    }
                }

                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(pageNumber))
        }
        return result
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
            deepest = maxOf(deepest, depth(stream.getAsDictionary(PdfName.Resources), level + 1, seen))
            seen.remove(id)
        }
        return deepest
    }
}
