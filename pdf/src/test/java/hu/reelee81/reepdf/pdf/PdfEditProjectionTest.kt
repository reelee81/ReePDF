package hu.reelee81.reepdf.pdf

import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfResources
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
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

    private companion object {
        val BROKEN = "\nq0.313 cm.176 ".repeat(120) + "q\n"
    }

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
            assertEquals(2, first.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).keySet().size)
            assertNotNull(contentForm(pdf.getPage(1)))
            assertEquals(2, pdf.getPage(1).contentStreamCount)
            assertNull(layerForm(pdf.getPage(2)))
        }
    }

    @Test
    fun anEditStaysInPlaceWhenTheSourcePageLeavesItsMatrixChanged() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            PdfCanvas(pdf.addNewPage(PageSize.A4)).concatMatrix(0.24, 0.0, 0.0, -0.24, 0.0, 841.92)
                .beginText().setFontAndSize(PdfFontFactory.createFont(), 50f)
                .moveText(160.0, 400.0).showText("SOURCE 1").endText()
        }
        val projected = projectEdit(source, "EDIT", 1)
        val position = textPositions(buildNup(projected), 1)["EDIT"]
        assertNotNull(position)
        assertEquals(150f, position!![0], 0.05f)
        assertEquals(200f, position[1], 0.05f)

        val cleared = erase(buildNup(projected), null)!!
        val restored = temporary.newFile()
        assertEquals(1, projectFile(projected, cleared, restored))
        PdfDocument(PdfReader(restored)).use { pdf ->
            assertNull(layerForm(pdf.getPage(1)))
            assertEquals(1, pdf.getPage(1).contentStreamCount)
        }
        assertTrue(textPositions(buildNup(restored), 1).containsKey("SOURCE 1"))
    }

    @Test
    fun anEditStaysInPlaceWhenTheSourcePageLeavesSavesOpen() {
        val source = rawSource("q\nq\n0.24 0 0 -0.24 0 841.92 cm\n0 0 m 100 100 l S\n")
        val projected = projectEdit(source, "EDIT", 1)
        val position = textPositions(buildNup(projected), 1)["EDIT"]
        assertNotNull(position)
        assertEquals(150f, position!![0], 0.05f)
        assertEquals(200f, position[1], 0.05f)
        PdfDocument(PdfReader(projected)).use { pdf ->
            val page = pdf.getPage(1)
            assertNull(privateData(page.pdfObject)!!.get(PdfName("Wrap")))
            assertEquals(2, page.contentStreamCount)
            assertTrue(String(contentForm(page)!!.bytes, Charsets.ISO_8859_1).endsWith("Q\nQ\n"))
        }
    }

    @Test
    fun aSourcePageWithMoreRestoresThanSavesKeepsItsOriginalContentInItsOwnForm() {
        val original = "0.5 0 0 0.5 0 0 cm\nq\nQ\nQ\n0 0 m 100 100 l S\n"
        val projected = projectEdit(rawSource(original), "EDIT", 1)
        PdfDocument(PdfReader(projected)).use { pdf ->
            val page = pdf.getPage(1)
            assertNotNull(layerForm(page))
            assertNull(privateData(page.pdfObject)!!.get(PdfName("Wrap")))
            assertEquals(2, page.contentStreamCount)
            assertEquals("$original\n", String(contentForm(page)!!.bytes, Charsets.ISO_8859_1))
            assertFalse(String(page.getContentStream(1).bytes, Charsets.ISO_8859_1).startsWith("Q"))
        }
    }

    @Test
    fun aBrokenSourcePageGetsItsContentInItsOwnFormBeneathTheEdit() {
        val projected = projectEdit(brokenSource(), "EDIT", 1)
        PdfDocument(PdfReader(projected)).use { pdf ->
            val page = pdf.getPage(1)
            val data = privateData(page.pdfObject)!!
            val original = data.getAsStream(PdfName("ContentForm"))
            assertNotNull(original)
            assertNull(data.get(PdfName("Wrap")))
            assertTrue(String(original!!.bytes, Charsets.ISO_8859_1).contains(BROKEN))
            assertEquals(2, page.contentStreamCount)
            val xObjects = page.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject)
            val originalName = xObjects.keySet().single { sameStream(xObjects.getAsStream(it), original) }
            val layerName = xObjects.keySet().single { sameStream(xObjects.getAsStream(it), layerForm(page)!!) }
            assertTrue(String(page.getContentStream(0).bytes, Charsets.ISO_8859_1).contains("/${originalName.value} Do"))
            assertTrue(String(page.getContentStream(1).bytes, Charsets.ISO_8859_1).contains("/${layerName.value} Do"))
        }
        val positions = textPositions(buildNup(projected), 1)
        assertTrue(positions.containsKey("SOURCE 1"))
        assertEquals(150f, positions.getValue("EDIT")[0], 0.05f)
        assertEquals(200f, positions.getValue("EDIT")[1], 0.05f)
    }

    @Test
    fun removingTheEditFromABrokenSourcePageRestoresItsContent() {
        val source = brokenSource()
        val content = PdfDocument(PdfReader(source)).use { pdf -> pdf.getPage(1).getContentStream(0).bytes + '\n'.code.toByte() }
        val projected = projectEdit(source, "EDIT", 1)
        val cleared = erase(buildNup(projected), null)!!
        val restored = temporary.newFile()
        assertEquals(1, projectFile(projected, cleared, restored))
        PdfDocument(PdfReader(restored)).use { pdf ->
            val page = pdf.getPage(1)
            assertNull(layerForm(page))
            assertNull(privateData(page.pdfObject)?.get(PdfName("ContentForm")))
            assertEquals(1, page.contentStreamCount)
            assertArrayEquals(content, page.getContentStream(0).bytes)
            assertNotNull(page.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.Font))
            assertNull(page.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject))
        }
        assertTrue(textPositions(buildNup(restored), 1).containsKey("SOURCE 1"))
    }

    @Test
    fun aBrokenSourcePageGetsDeeperOnlyOnceAcrossEditsSavesAndReloads() {
        val plain = buildNup(projectEdit(makeSource(1), "EDIT 0", 1), flatten = true)
        var exported = buildNup(projectEdit(brokenSource(), "EDIT 0", 1), flatten = true)
        val depth = maxDepth(exported)
        assertTrue(depth <= maxDepth(plain) + 1)

        for (round in 1..5) {
            val resaved = buildNup(reload(exported), flatten = true)
            assertEquals("resave in round $round", depth, maxDepth(resaved))

            exported = buildNup(projectEdit(reload(resaved), "EDIT $round", 1), flatten = true)
            assertEquals("new edit in round $round", depth, maxDepth(exported))

            val texts = textPositions(exported, 1)
            assertTrue(texts.containsKey("SOURCE 1"))
            for (previous in 0..round) assertTrue("EDIT $previous is missing", texts.containsKey("EDIT $previous"))
        }
    }

    @Test
    fun anInlineImageDoesNotMakeASourcePageBroken() {
        val projected = projectEdit(rawSource("q\nBI /W 2 /H 1 /BPC 8 /CS /G ID QQ EI\n0 0 m 100 100 l S\n"), "EDIT", 1)
        PdfDocument(PdfReader(projected)).use { pdf ->
            val page = pdf.getPage(1)
            val data = privateData(page.pdfObject)!!
            assertNull(data.get(PdfName("Wrap")))
            assertEquals(2, page.contentStreamCount)
            assertTrue(String(contentForm(page)!!.bytes, Charsets.ISO_8859_1).endsWith("l S\n\nQ\n"))
        }
    }

    private fun brokenSource(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A4)
            PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(40.0, 780.0).showText("SOURCE 1").endText()
            page.getContentStream(0).outputStream.writeString(BROKEN)
        }
        return file
    }

    @Test
    fun aProjectedPageKeepsOnlyItsStandardResourcesInTheirOwnObject() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A4)
            PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(), 20f)
                .moveText(100.0, 700.0).showText("SOURCE 1").endText()
            page.resources.pdfObject.put(PdfName("Pi"), PdfNumber(3.14159))
        }
        val projected = projectEdit(source, "EDIT", 1)
        PdfDocument(PdfReader(projected)).use { pdf ->
            val page = pdf.getPage(1)
            assertTrue(page.pdfObject.get(PdfName.Resources, false) is PdfIndirectReference)
            assertFalse(page.pdfObject.getAsDictionary(PdfName.Resources).containsKey(PdfName("Pi")))
            val original = contentForm(page)!!
            assertTrue(original.get(PdfName.Resources, false) is PdfIndirectReference)
            val resources = original.getAsDictionary(PdfName.Resources)
            assertFalse(resources.containsKey(PdfName("Pi")))
            assertNotNull(resources.getAsDictionary(PdfName.Font))
            assertNotNull(layerForm(page))
        }
        val positions = textPositions(buildNup(projected), 1)
        assertTrue(positions.containsKey("SOURCE 1"))
        assertTrue(positions.containsKey("EDIT"))
    }

    private fun rawSource(content: String): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            pdf.addNewPage(PageSize.A4).getContentStream(0).setData(content.toByteArray(Charsets.ISO_8859_1))
        }
        return file
    }

    @Test
    fun clearingTheVisiblePageRemovesOnlyItsEditsFromTheSource() {
        var source = makeSource(2)
        source = projectEdit(source, "FIRST PAGE EDIT", 1)
        source = projectEdit(source, "SECOND PAGE EDIT", 2)

        val nup = buildNup(source)
        assertTrue(hasLayers(nup, setOf(1)))
        val cleared = erase(nup, setOf(1))!!
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
            assertNull(contentForm(first))
            assertEquals(1, first.contentStreamCount)
            assertEquals(0, first.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject)?.size() ?: 0)
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

        val cleared = erase(buildNup(source), null)!!
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
        val nup = buildNup(source)
        assertFalse(hasLayers(nup, null))
        assertNull(erase(nup, null))
        assertEquals(0, projectFile(source, nup, temporary.newFile()))
    }

    @Test
    fun clearingRemovesEditsNotYetProjectedToTheSource() {
        val source = makeSource(2)
        val edited = edit(edit(buildNup(source), "FIRST PAGE EDIT", 150f, 200f, 1), "SECOND PAGE EDIT", 150f, 200f, 2)
        assertTrue(hasLayers(edited, setOf(1)))

        val cleared = erase(edited, setOf(1))!!
        assertFalse(hasLayers(cleared, setOf(1)))
        assertTrue(hasLayers(cleared, setOf(2)))
        assertFalse(textPositions(cleared, 1).containsKey("FIRST PAGE EDIT"))
        assertTrue(textPositions(cleared, 1).containsKey("SOURCE 1"))
        assertTrue(textPositions(cleared, 2).containsKey("SECOND PAGE EDIT"))
        PdfDocument(PdfReader(cleared)).use { pdf ->
            val page = pdf.getPage(1)
            assertNotNull(base(page))
            assertTrue(PdfEditProjection.editStreams(page).all { PdfMarkContent.isBlank(it.bytes) })
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
        assertNull(erase(modified, null))
        assertTrue(textPositions(modified, 1).containsKey("EDIT"))
    }

    @Test
    fun anEmptiedLayerCopyRemovesTheSourceLayer() {
        val projected = projectEdit(makeSource(1), "EDIT", 1)
        val emptied = temporary.newFile()
        PdfDocument(PdfReader(buildNup(projected)), PdfWriter(emptied)).use { pdf ->
            val layer = PdfEditProjection.editStreams(pdf.getPage(1)).single()
            layer.remove(PdfName.Filter)
            layer.remove(PdfName.DecodeParms)
            layer.setData(ByteArray(0))
        }
        val restored = temporary.newFile()
        assertEquals(1, projectFile(projected, emptied, restored))
        PdfDocument(PdfReader(restored)).use { pdf -> assertNull(layerForm(pdf.getPage(1))) }
        assertFalse(textPositions(buildNup(restored), 1).containsKey("EDIT"))
        assertTrue(textPositions(buildNup(restored), 1).containsKey("SOURCE 1"))
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

    @Test
    fun editsOnPagesSharingTheirPieceInfoStayOnTheirOwnPageAcrossTwoSaves() {
        val source = makeSharedPieceInfoSource()
        assertTrue(sharesPieceInfoWithFirstPage(source, 2))

        val withFirst = projectTogether(source, edit(buildNup(source), "FIRST PAGE EDIT", 150f, 200f, 1))
        val withBoth = projectTogether(withFirst, edit(buildNup(withFirst), "SECOND PAGE EDIT", 150f, 200f, 2))

        assertOwnLayerRecords(withBoth)
        val nup = buildNup(withBoth)
        assertEquals(setOf("FIRST PAGE EDIT"), edits(nup, 1))
        assertEquals(setOf("SECOND PAGE EDIT"), edits(nup, 2))
    }

    @Test
    fun editsOnPagesSharingTheirPieceInfoInOneSaveStayOnTheirOwnPage() {
        val source = makeSharedPieceInfoSource()
        val edited = edit(edit(buildNup(source), "FIRST PAGE EDIT", 150f, 200f, 1), "SECOND PAGE EDIT", 150f, 200f, 2)

        val projected = projectTogether(source, edited)

        assertOwnLayerRecords(projected)
        val nup = buildNup(projected)
        assertEquals(setOf("FIRST PAGE EDIT"), edits(nup, 1))
        assertEquals(setOf("SECOND PAGE EDIT"), edits(nup, 2))
    }

    @Test
    fun clearingOnePageSharingItsPieceInfoKeepsTheOtherPagesEdit() {
        val source = makeSharedPieceInfoSource()
        val projected = projectTogether(
            source,
            edit(edit(buildNup(source), "FIRST PAGE EDIT", 150f, 200f, 1), "SECOND PAGE EDIT", 150f, 200f, 2)
        )

        val withoutFirst = buildNup(projectTogether(projected, cleared(buildNup(projected), 1)))
        assertEquals(emptySet<String>(), edits(withoutFirst, 1))
        assertEquals(setOf("SECOND PAGE EDIT"), edits(withoutFirst, 2))
        assertTrue(textPositions(withoutFirst, 1).containsKey("SOURCE 1"))

        val withoutSecond = buildNup(projectTogether(projected, cleared(buildNup(projected), 2)))
        assertEquals(setOf("FIRST PAGE EDIT"), edits(withoutSecond, 1))
        assertEquals(emptySet<String>(), edits(withoutSecond, 2))
        assertTrue(textPositions(withoutSecond, 2).containsKey("SOURCE 2"))
    }

    @Test
    fun pagesThatShareTheRecordOfAnEditedPageGetTheirOwnLayer() {
        val plain = makeSourceEndingWithAnExtraRestore()
        val withFirst = projectTogether(plain, edit(buildNup(plain), "FIRST PAGE EDIT", 150f, 200f, 1))
        val shared = temporary.newFile()
        PdfDocument(PdfReader(withFirst), PdfWriter(shared)).use { pdf ->
            val pieceInfo = pdf.getPage(1).pdfObject.getAsDictionary(PdfName.PieceInfo).makeIndirect(pdf)
            for (number in 1..3) pdf.getPage(number).pdfObject.put(PdfName.PieceInfo, pieceInfo)
        }
        assertTrue(sharesPieceInfoWithFirstPage(shared, 2))
        assertTrue(sharesPieceInfoWithFirstPage(shared, 3))
        val firstLayer = firstLayerBytes(shared)

        val edited = edit(edit(buildNup(shared), "SECOND PAGE EDIT", 150f, 200f, 2), "THIRD PAGE EDIT", 150f, 200f, 3)
        val projected = projectTogether(shared, edited)

        assertOwnLayerRecords(projected)
        assertArrayEquals(firstLayer, firstLayerBytes(projected))
        PdfDocument(PdfReader(projected)).use { pdf -> assertNotNull(layerForm(pdf.getPage(3))) }
        val nup = buildNup(projected)
        assertEquals(setOf("FIRST PAGE EDIT"), edits(nup, 1))
        assertEquals(setOf("SECOND PAGE EDIT"), edits(nup, 2))

        val withoutFirst = projectTogether(projected, cleared(nup, 1))
        assertOwnLayerRecords(withoutFirst)
        val after = buildNup(withoutFirst)
        assertEquals(emptySet<String>(), edits(after, 1))
        assertEquals(setOf("SECOND PAGE EDIT"), edits(after, 2))
        PdfDocument(PdfReader(withoutFirst)).use { pdf -> assertNotNull(layerForm(pdf.getPage(3))) }
    }

    private fun makeSharedPieceInfoSource(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val font = PdfFontFactory.createFont()
            val pieceInfo = PdfDictionary().apply {
                put(PdfName("Illustrator"), PdfDictionary().apply {
                    put(PdfName.Private, PdfDictionary().apply { put(PdfName("Version"), PdfNumber(1)) })
                })
                makeIndirect(pdf)
            }
            repeat(2) { index ->
                val page = pdf.addNewPage(PageSize.A4)
                PdfCanvas(page).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 780.0).showText("SOURCE ${index + 1}").endText()
                page.pdfObject.put(PdfName.PieceInfo, pieceInfo)
            }
        }
        return file
    }

    private fun makeSourceEndingWithAnExtraRestore(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val font = PdfFontFactory.createFont()
            for (number in 1..2) {
                PdfCanvas(pdf.addNewPage(PageSize.A4)).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 780.0).showText("SOURCE $number").endText()
            }
            pdf.addNewPage(PageSize.A4).getContentStream(0).setData("0 0 m 100 100 l S\nQ\n".toByteArray(Charsets.ISO_8859_1))
        }
        return file
    }

    private fun sharesPieceInfoWithFirstPage(file: File, pageNumber: Int): Boolean = PdfDocument(PdfReader(file)).use { pdf ->
        val first = pdf.getPage(1).pdfObject.get(PdfName.PieceInfo, false)
        val other = pdf.getPage(pageNumber).pdfObject.get(PdfName.PieceInfo, false)
        first is PdfIndirectReference && other is PdfIndirectReference && first.objNumber == other.objNumber
    }

    private fun projectTogether(source: File, edited: File): File {
        val parts = temporary.newFolder()
        source.copyTo(File(parts, "0001.pdf"))
        val result = PdfEditProjection.project(PdfSourceParts.open(parts), edited, batchSize = 10, writeStage = { NoProgress }) {}
        val output = temporary.newFile()
        result.source.file(0).copyTo(output, overwrite = true)
        return output
    }

    private fun cleared(nup: File, pageNumber: Int): File = erase(nup, setOf(pageNumber))!!

    private fun erase(nup: File, pages: Set<Int>?): File? {
        val output = temporary.newFile()
        val plan = if (pages == null) PdfMarkSave.Plan(blankAll = true) else PdfMarkSave.Plan(blankPages = pages.map { it - 1 }.toSet())
        return output.takeIf { PdfMarkSave.write(nup, output, plan, 1, ProgressTracker { 0L }.let { it::stage }) {} }
    }

    private fun edits(file: File, pageNumber: Int): Set<String> =
        textPositions(file, pageNumber).keys.filter { it.endsWith("EDIT") }.toSet()

    private fun firstLayerBytes(file: File): ByteArray =
        PdfDocument(PdfReader(file)).use { pdf -> layerForm(pdf.getPage(1))!!.bytes }

    private fun assertOwnLayerRecords(file: File) {
        PdfDocument(PdfReader(file)).use { pdf ->
            for (number in 1..pdf.numberOfPages) {
                val page = pdf.getPage(number)
                val data = privateData(page.pdfObject) ?: continue
                data.getAsStream(PdfName("LayerForm"))?.let { layer ->
                    val xObjects = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.XObject)
                    val own = xObjects != null && xObjects.keySet().any { name -> sameStream(xObjects.getAsStream(name), layer) }
                    assertTrue("page $number has the layer of another page", own)
                }
                data.getAsStream(PdfName("Wrap"))?.let { wrap ->
                    val contents = page.pdfObject.getAsArray(PdfName.Contents)
                    val own = contents != null && (0 until contents.size()).any { sameStream(contents.getAsStream(it), wrap) }
                    assertTrue("page $number has the wrap of another page", own)
                }
            }
        }
    }

    private fun sameStream(stream: PdfStream?, other: PdfStream): Boolean =
        stream === other || (stream?.indirectReference != null && stream.indirectReference == other.indirectReference)

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

    private fun hasLayers(file: File, pages: Set<Int>?): Boolean = PdfDocument(PdfReader(file)).use { pdf ->
        (pages ?: (1..pdf.numberOfPages)).filter { it in 1..pdf.numberOfPages }.any { number ->
            PdfEditProjection.editStreams(pdf.getPage(number)).any { !PdfMarkContent.isBlank(it.bytes) }
        }
    }

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
        assertNull(erase(nup, null))
        assertEquals(0, projectFile(reloaded, nup, temporary.newFile()))

        val newEdit = projectEdit(reloaded, "NEW EDIT", 1)
        val cleared = erase(buildNup(newEdit), null)!!
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

            val cleared = erase(exported, null)!!
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

    @Test
    fun anEditedPageWithAPageGroupStaysOneLevelDeepAcrossSavesReloadsAndEdits() {
        var source = groupSource()
        assertEquals(0, maxDepth(source))
        assertEquals(1, maxDepth(buildNup(source, flatten = true)))

        for (round in 1..5) {
            val projected = projectStroke(source, round)
            PdfDocument(PdfReader(projected)).use { pdf ->
                val page = pdf.getPage(1)
                if (round == 1) {
                    assertNull(page.pdfObject.get(PdfName.Group))
                    assertNotNull(contentForm(page)!!.getAsDictionary(PdfName.Group))
                }
            }
            val exported = buildNup(projected, flatten = true)
            assertEquals("edit in round $round", 1, maxDepth(exported))
            assertEquals("strokes in round $round", round, strokeCount(exported))
            assertTrue(textPositions(exported, 1).containsKey("SOURCE 1"))

            val resaved = buildNup(reload(exported), flatten = true)
            assertEquals("resave in round $round", 1, maxDepth(resaved))
            source = reload(resaved)
        }
    }

    @Test
    fun clearingTheEditGivesThePageItsGroupBack() {
        val projected = projectStroke(groupSource(), 1)
        val cleared = erase(buildNup(projected, flatten = true), null)!!
        val restored = temporary.newFile()
        assertEquals(1, projectFile(projected, cleared, restored))
        PdfDocument(PdfReader(restored)).use { pdf ->
            val page = pdf.getPage(1)
            assertNull(layerForm(page))
            assertNull(contentForm(page))
            assertEquals(1, page.contentStreamCount)
            assertEquals(PdfName.Transparency, page.pdfObject.getAsDictionary(PdfName.Group)!!.getAsName(PdfName.S))
        }
        assertEquals(0, strokeCount(buildNup(restored, flatten = true)))
    }

    @Test
    fun anEditAddsNoLevelToAPageThatAlreadyPlacesForms() {
        var source = nestedFormSource()
        assertEquals(2, maxDepth(source))
        assertEquals(2, maxDepth(buildNup(source, flatten = true)))

        for (round in 1..3) {
            val projected = projectStroke(source, round)
            PdfDocument(PdfReader(projected)).use { pdf -> assertNull(contentForm(pdf.getPage(1))) }
            val exported = buildNup(projected, flatten = true)
            assertEquals("edit in round $round", 2, maxDepth(exported))
            assertEquals("strokes in round $round", round, strokeCount(exported))
            val texts = textPositions(exported, 1)
            assertTrue(texts.containsKey("OUTER"))
            assertTrue(texts.containsKey("INNER"))
            source = reload(exported)
        }
    }

    @Test
    fun wrapperFormsLeftByEarlierSavesAreMergedIntoTheSheet() {
        var exported = buildNup(groupSource(), flatten = true)
        repeat(3) {
            val wrapped = temporary.newFile()
            PdfDocument(PdfReader(exported)).use { src ->
                PdfDocument(PdfWriter(wrapped)).use { out ->
                    val form = src.getPage(1).copyAsFormXObject(out)
                    PdfCanvas(out.addNewPage(PageSize.A4)).addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                }
            }
            exported = wrapped
        }
        assertEquals(4, maxDepth(exported))
        val rebuilt = buildNup(exported, flatten = true)
        assertEquals(1, maxDepth(rebuilt))
        assertTrue(textPositions(rebuilt, 1).containsKey("SOURCE 1"))
    }

    @Test
    fun layersFromEarlierSavesAreMergedIntoOneFormNextToTheContent() {
        var source = groupSource()
        for (round in 1..6) {
            val exported = buildNup(projectStroke(source, round), flatten = true)
            assertEquals("depth in round $round", 1, maxDepth(exported))
            assertEquals("strokes in round $round", round, strokeCount(exported))
            assertTrue(textPositions(exported, 1).containsKey("SOURCE 1"))
            PdfDocument(PdfReader(exported)).use { pdf ->
                val page = pdf.getPage(1)
                val forms = page.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject)
                val merged = forms.keySet().count { privateData(forms.getAsStream(it))?.getAsBool(PdfName("Merged")) == true }
                assertEquals("forms in round $round", minOf(round + 1, 3), forms.size())
                assertEquals("merged forms in round $round", if (round >= 3) 1 else 0, merged)
                assertEquals("saves in round $round", if (round == 1) 1 else 2, saveDepth(page))
            }
            source = reload(exported)
        }
    }

    @Test
    fun aFlatOutputDrawsEveryPathAndTextWhereTheNestedOneDoes() {
        var source = groupSource()
        for (round in 1..5) {
            val projected = projectStroke(source, round)
            val flat = buildNup(projected, flatten = true, frame = true)
            val nested = buildNup(projected, flatten = false, frame = true)
            val expected = paths(nested)
            val actual = paths(flat)
            assertEquals("paths in round $round", expected.size, actual.size)
            for (index in expected.indices) {
                assertEquals("operation of path $index in round $round", expected[index].first, actual[index].first)
                assertArrayEquals("path $index in round $round", expected[index].second, actual[index].second, 0.01)
            }
            val expectedTexts = textPositions(nested, 1)
            val actualTexts = textPositions(flat, 1)
            assertEquals(expectedTexts.keys, actualTexts.keys)
            for ((text, position) in expectedTexts) assertArrayEquals(text, position, actualTexts.getValue(text), 0.01f)
            source = reload(flat)
        }
    }

    @Test
    fun theEraserRemovesOnlyTheCurrentEditNextToMergedLayers() {
        var source = groupSource()
        repeat(3) { source = reload(buildNup(projectStroke(source, it + 1), flatten = true)) }
        val projected = projectStroke(source, 4)
        val exported = buildNup(projected, flatten = true)
        assertEquals(4, strokeCount(exported))
        val cleared = erase(exported, null)!!
        assertEquals(3, strokeCount(cleared))
        val restored = temporary.newFile()
        assertEquals(1, projectFile(projected, cleared, restored))
        assertEquals(3, strokeCount(buildNup(restored, flatten = true)))
        assertEquals(1, maxDepth(buildNup(restored, flatten = true)))
    }

    @Test
    fun anEditStaysInPlaceWhenAPagePlacingOnlyFormsLeavesItsMatrixChanged() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            val form = PdfFormXObject(Rectangle(PageSize.A4))
            PdfCanvas(form, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(40.0, 780.0).showText("SOURCE 1").endText()
            val page = pdf.addNewPage(PageSize.A4)
            val name = page.resources.addForm(form)
            page.getContentStream(0).setData("0.5 0 0 0.5 0 0 cm\n/${name.value} Do\n".toByteArray(Charsets.ISO_8859_1))
        }
        var current = source
        for (round in 1..3) {
            val projected = projectEdit(current, "EDIT$round", 1)
            val plain = textPositions(buildNup(projected), 1)
            val flat = textPositions(buildNup(projected, flatten = true), 1)
            for (texts in listOf(plain, flat)) {
                val edit = texts["EDIT$round"]
                assertNotNull("round $round", edit)
                assertEquals(150f, edit!![0], 0.05f)
                assertEquals(200f, edit[1], 0.05f)
            }
            assertEquals(plain.keys, flat.keys)
            for ((text, position) in plain) assertArrayEquals(text, position, flat.getValue(text), 0.05f)
            current = reload(buildNup(projected, flatten = true))
        }
    }

    @Test
    fun aDissolvedWrapperKeepsItsBoundingBoxClipInValidContent() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            val inner = PdfFormXObject(Rectangle(PageSize.A4))
            PdfCanvas(inner, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(40.0, 780.0).showText("SOURCE 1").endText()
            val wrapper = PdfFormXObject(Rectangle(20f, 30f, 400f, 500f))
            wrapper.pdfObject.put(PdfName.Matrix, PdfArray(doubleArrayOf(0.9, 0.0, 0.0, 0.9, 5.0, 7.0)))
            PdfCanvas(wrapper, pdf).addXObjectWithTransformationMatrix(inner, 1f, 0f, 0f, 1f, 0f, 0f)
            PdfCanvas(pdf.addNewPage(PageSize.A4)).addXObjectWithTransformationMatrix(wrapper, 1f, 0f, 0f, 1f, 0f, 0f)
        }
        val flat = buildNup(source, flatten = true)
        PdfDocument(PdfReader(flat)).use { pdf ->
            val page = pdf.getPage(1)
            assertFalse(PdfCopySupport.scanContent(page.contentBytes, page.resources.pdfObject).broken)
            assertEquals(1, page.pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).size())
        }
        val nested = textPositions(buildNup(source), 1)
        val flatTexts = textPositions(flat, 1)
        assertEquals(nested.keys, flatTexts.keys)
        for ((text, position) in nested) assertArrayEquals(text, position, flatTexts.getValue(text), 0.01f)
    }

    @Test
    fun aClipAndMatrixSharedByPlacedItemsAreWrittenOnceOnTheSheet() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            val font = PdfFontFactory.createFont()
            val page = pdf.addNewPage(PageSize.A4)
            val names = (1..3).map { number ->
                val form = PdfFormXObject(Rectangle(PageSize.A4))
                PdfCanvas(form, pdf).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 700.0 - number * 50).showText("FORM $number").endText()
                page.resources.addForm(form).value
            }
            val content = "q\n0.8 0 0 0.8 30 40 cm\n20 20 400 600 re W n\n" +
                names.joinToString("") { "/$it Do\n10 10 m 300 300 l S\n" } + "Q\n"
            page.getContentStream(0).setData(content.toByteArray(Charsets.ISO_8859_1))
        }
        val flat = buildNup(source, flatten = true)
        PdfDocument(PdfReader(flat)).use { pdf ->
            val text = String(pdf.getPage(1).contentBytes, Charsets.ISO_8859_1)
            assertEquals(2, Regex("""W n""").findAll(text).count())
            assertEquals(1, Regex("""0\.8 0 0 0\.8 30 40 cm""").findAll(text).count())
        }
        val nested = buildNup(source)
        val expected = paths(nested)
        val actual = paths(flat)
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) assertArrayEquals(expected[index].second, actual[index].second, 0.01)
        val nestedTexts = textPositions(nested, 1)
        val flatTexts = textPositions(flat, 1)
        assertEquals(nestedTexts.keys, flatTexts.keys)
        for ((text, position) in nestedTexts) assertArrayEquals(text, position, flatTexts.getValue(text), 0.01f)
    }

    @Test(timeout = 20_000)
    fun aPageWhoseFormsBranchIntoEachOtherFallsBackInsteadOfExploding() {
        val source = temporary.newFile()
        PdfDocument(PdfWriter(source)).use { pdf ->
            var form = PdfFormXObject(Rectangle(PageSize.A4))
            PdfCanvas(form, pdf).rectangle(10.0, 10.0, 20.0, 20.0).fill()
            repeat(40) {
                val outer = PdfFormXObject(Rectangle(PageSize.A4))
                val name = outer.resources.addForm(form)
                outer.pdfObject.setData("q\n/${name.value} Do\nQ\nq\n/${name.value} Do\nQ\n".toByteArray(Charsets.ISO_8859_1))
                form = outer
            }
            PdfCanvas(pdf.addNewPage(PageSize.A4)).addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
        }
        val built = buildNup(source, flatten = true)
        PdfDocument(PdfReader(built)).use { pdf ->
            val forms = pdf.getPage(1).pdfObject.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject)
            assertEquals(1, forms.size())
            val placed = forms.getAsStream(forms.keySet().single())
            assertNotNull(placed.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject))
        }
    }

    private fun groupSource(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A4)
            PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(40.0, 780.0).showText("SOURCE 1").endText()
            page.pdfObject.put(PdfName.Group, PdfDictionary().apply {
                put(PdfName.Type, PdfName.Group)
                put(PdfName.S, PdfName.Transparency)
                put(PdfName.CS, PdfName.DeviceRGB)
            })
        }
        return file
    }

    private fun nestedFormSource(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val font = PdfFontFactory.createFont()
            val inner = PdfFormXObject(Rectangle(PageSize.A4))
            PdfCanvas(inner, pdf).beginText().setFontAndSize(font, 12f).moveText(40.0, 700.0).showText("INNER").endText()
            val outer = PdfFormXObject(Rectangle(PageSize.A4))
            PdfCanvas(outer, pdf).beginText().setFontAndSize(font, 12f).moveText(40.0, 780.0).showText("OUTER").endText()
                .addXObjectWithTransformationMatrix(inner, 1f, 0f, 0f, 1f, 0f, 0f)
            PdfCanvas(pdf.addNewPage(PageSize.A4)).addXObjectWithTransformationMatrix(outer, 1f, 0f, 0f, 1f, 0f, 0f)
        }
        return file
    }

    private fun projectStroke(source: File, round: Int): File {
        val edited = temporary.newFile()
        PdfDocument(PdfReader(buildNup(source, flatten = true)), PdfWriter(edited)).use { pdf ->
            val page = pdf.getPage(1)
            PdfCanvas(page.newContentStreamAfter(), page.resources, pdf)
                .moveTo(100.0, 100.0 + round * 20.0).lineTo(300.0, 120.0 + round * 20.0).stroke()
        }
        val projected = temporary.newFile()
        assertEquals(1, projectFile(source, edited, projected))
        return projected
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

    private fun paths(file: File): List<Pair<Int, DoubleArray>> {
        val paths = ArrayList<Pair<Int, DoubleArray>>()
        PdfDocument(PdfReader(file)).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data !is PathRenderInfo || data.operation == PathRenderInfo.NO_OP) return
                    val ctm = data.ctm
                    val points = data.path.subpaths.flatMap { subpath ->
                        listOf(subpath.startPoint) + subpath.segments.flatMap { it.basePoints }
                    }.flatMap { point ->
                        val vector = Vector(point.x.toFloat(), point.y.toFloat(), 1f).cross(ctm)
                        listOf(vector.get(0).toDouble(), vector.get(1).toDouble())
                    }
                    paths += data.operation to points.toDoubleArray()
                }

                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_PATH)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(1))
        }
        return paths
    }

    private fun saveDepth(page: PdfPage): Int {
        var depth = 0
        var deepest = 0
        for (index in 1..page.contentStreamCount) {
            val tokenizer = PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(page.getContentStream(index - 1).bytes)))
            tokenizer.use {
                val parser = PdfCanvasParser(tokenizer, PdfResources(PdfDictionary()))
                val operands = ArrayList<PdfObject>()
                while (parser.parse(operands).isNotEmpty()) {
                    when (operands.last().toString()) {
                        "q" -> deepest = maxOf(deepest, ++depth)
                        "Q" -> depth--
                    }
                }
            }
        }
        return deepest
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

    private fun contentForm(page: PdfPage): PdfStream? = privateData(page.pdfObject)?.getAsStream(PdfName("ContentForm"))

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
                    canvas.saveState()
                    PdfCopySupport.concatPlacementMatrix(
                        canvas, rotatedHalf[0], rotatedHalf[1], rotatedHalf[2],
                        rotatedHalf[3], rotatedHalf[4], rotatedHalf[5]
                    )
                    val placed = flatten && PdfFlatPlacement.place(sourcePage, page, canvas, box, wholeSheet = true) { original, copy ->
                        PdfEditProjection.tagLayerCopy(original, copy, sourcePage, number - 1, generation)
                    }
                    if (!placed) {
                        val form = (if (flatten) PdfCopySupport.flattenedFormXObject(sourcePage, out, box) else null)
                            ?: sourcePage.copyAsFormXObject(out)
                        form.bBox = PdfArray(box)
                        PdfEditProjection.tagLayerSource(form, sourcePage, number - 1, generation)
                        canvas.addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                    }
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
