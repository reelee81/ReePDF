package hu.reelee81.reepdf.pdf

import com.itextpdf.io.font.FontProgramFactory
import com.itextpdf.io.font.TrueTypeFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
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
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import hu.reelee81.reepdf.core.NupArea
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfTypedMarkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private var saves = 0
    private val scaled = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)
    private val fontFiles = mapOf(
        100 to "thin",
        200 to "extralight",
        300 to "light",
        400 to "regular",
        500 to "medium",
        600 to "semibold",
        700 to "bold",
        800 to "extrabold",
        900 to "black"
    )
    private val fonts = PdfTextFonts(::fontBytes)

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
        build()
    }

    @Test
    fun aStrippedFontKeepsEveryAdvanceAndLosesOnlyItsHinting() {
        for (weight in PdfMarkText.WEIGHTS) {
            val original = fontBytes(weight)
            val stripped = PdfFontHinting.strip(original)

            assertTrue("weight $weight", stripped.size < original.size - 20_000)
            assertEquals(tables(original) - setOf("fpgm", "prep", "cvt "), tables(stripped))
            assertSame(stripped, PdfFontHinting.strip(stripped))
            val before = FontProgramFactory.createFont(original, false) as TrueTypeFont
            val after = FontProgramFactory.createFont(stripped, false) as TrueTypeFont
            assertEquals(before.fontMetrics.numberOfGlyphs, after.fontMetrics.numberOfGlyphs)
            for (codePoint in before.activeCmap.keys) {
                assertEquals("U+%04X".format(codePoint), before.getGlyph(codePoint).width, after.getGlyph(codePoint).width)
                assertEquals(before.getGlyph(codePoint).code, after.getGlyph(codePoint).code)
            }
        }
        val broken = byteArrayOf(0, 1, 0, 0, 0, 9)
        assertSame(broken, PdfFontHinting.strip(broken))
    }

    @Test
    fun aTypedMarkIsSavedAsRealEmbeddedTextAndReadsBackUnchanged() {
        val text = "Árvíztűrő tükörfúrógép\nÁRVÍZTŰRŐ TÜKÖRFÚRÓGÉP\n\nend"
        val edited = save(mapOf(0 to listOf(typed("t", text, 700, 14f, 60.0, 700.0, 0x202FB0))))

        PdfDocument(PdfReader(edited)).use { pdf ->
            val page = pdf.getPage(1)
            val stored = PdfMarkContent.read(page.getContentStream(page.contentStreamCount - 1).bytes).single().mark
            assertEquals("t", stored.id)
            assertEquals(PdfMarkKind.TYPED, stored.kind)
            assertEquals(0x202FB0, stored.color)
            val typed = stored.text!!
            assertEquals(text, typed.text)
            assertEquals(700, typed.weight)
            assertEquals(14f, typed.size, 0.001f)
            assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 60.0, 700.0), typed.matrix, 0.001)
            assertEquals(3 * 5, stored.path.verbs.size)

            val extracted = PdfTextExtractor.getTextFromPage(page, LocationTextExtractionStrategy())
            assertTrue(extracted, extracted.contains("Árvíztűrő tükörfúrógép"))
            assertTrue(extracted, extracted.contains("ÁRVÍZTŰRŐ TÜKÖRFÚRÓGÉP"))
            assertTrue(extracted, extracted.contains("end"))

            val own = ownFonts(page)
            assertEquals(1, own.size)
            val font = own.values.single()
            assertEquals(PdfName.Type0, font.getAsName(PdfName.Subtype))
            assertTrue(font.getAsName(PdfName.BaseFont).value, font.getAsName(PdfName.BaseFont).value.endsWith("+Roboto-Bold"))
            assertNotNull(font.getAsStream(PdfName.ToUnicode))
            val program = fontFile(font)
            assertFalse(tables(program).contains("fpgm"))
            assertTrue(program.size < 12_000)
        }
        TestPdfs.assertWorkFile(edited)
    }

    @Test
    fun theLettersInThePdfStandWhereTheStoredLineWidthsSay() {
        val edited = save(mapOf(0 to listOf(typed("t", "John Smith\n12 Main Street\nLondon", 400, 11f, 50.0, 600.0))))

        PdfDocument(PdfReader(edited)).use { pdf ->
            val page = pdf.getPage(1)
            val mark = PdfMarkContent.read(page.getContentStream(page.contentStreamCount - 1).bytes).single().mark
            val boxes = rectangles(mark.path)
            val runs = ArrayList<DoubleArray>()
            PdfCanvasProcessor(object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    val info = data as? TextRenderInfo ?: return
                    val start = info.baseline.startPoint
                    val end = info.baseline.endPoint
                    runs += doubleArrayOf(start.get(0).toDouble(), start.get(1).toDouble(), end.get(0).toDouble())
                }

                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            }).processPageContent(page)
            val sheetRuns = runs.filter { it[0] in 49.0..51.0 }
            assertEquals(3, sheetRuns.size)
            for ((index, run) in sheetRuns.withIndex()) {
                val baseline = 600.0 - (PdfMarkText.ASCENT + index * PdfMarkText.LINE_HEIGHT) * 11.0
                assertEquals("line $index start", 50.0, run[0], 0.001)
                assertEquals("line $index baseline", baseline, run[1], 0.001)
                assertEquals("line $index end", boxes[index][2], run[2], 0.01)
                assertEquals("line $index top", 600.0 - index * PdfMarkText.LINE_HEIGHT * 11.0, boxes[index][3], 0.01)
            }
        }
    }

    @Test
    fun lettersTheFontLacksAreLeftOutAndSplitAccentsAreJoined() {
        val edited = save(
            mapOf(
                0 to listOf(
                    typed("a", "ő 中😀\tA\r\nB", 400, 12f, 60.0, 700.0),
                    typed("b", "中文😀", 400, 12f, 60.0, 600.0)
                )
            )
        )

        PdfDocument(PdfReader(edited)).use { pdf ->
            val page = pdf.getPage(1)
            val marks = PdfMarkContent.read(page.getContentStream(page.contentStreamCount - 1).bytes).map { it.mark }
            assertEquals(listOf("a"), marks.map { it.id })
            assertEquals("ő A\nB", marks.single().text!!.text)
        }
    }

    @Test
    fun oneSubsetPerWeightServesEveryPageOfASave() {
        val plain = File(dir, "plain.pdf")
        PdfDocument(PdfIo.workFileWriter(plain.absolutePath)).use { pdf -> repeat(2) { pdf.addNewPage(PageSize.A5) } }
        val output = File(dir, "plain_typed.pdf")
        val plan = PdfMarkSave.Plan(
            added = mapOf(
                0 to listOf(typed("a", "first", 400, 12f, 30.0, 500.0), typed("b", "bold", 700, 12f, 30.0, 400.0)),
                1 to listOf(typed("c", "second", 400, 12f, 30.0, 500.0))
            )
        )
        assertTrue(PdfMarkSave.write(plain, output, plan, 2, { NoProgress }, fonts) {})

        PdfDocument(PdfReader(output)).use { pdf ->
            val first = ownFonts(pdf.getPage(1))
            val second = ownFonts(pdf.getPage(2))
            assertEquals(2, first.size)
            assertEquals(1, second.size)
            assertTrue(first.keys.intersect(second.keys).isEmpty())
            val regular = first.values.first { it.getAsName(PdfName.BaseFont).value.endsWith("Roboto-Regular") }
            assertEquals(regular.indirectReference.objNumber, second.values.single().indirectReference.objNumber)
            val extracted = PdfTextExtractor.getTextFromPage(pdf.getPage(2))
            assertTrue(extracted, extracted.contains("second"))
        }
    }

    @Test
    fun aPageWithInheritedResourcesKeepsThemNextToItsTypedFont() {
        val built = File(dir, "inherit_built.pdf")
        PdfDocument(PdfWriter(built)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A5)
            val form = com.itextpdf.kernel.pdf.xobject.PdfFormXObject(com.itextpdf.kernel.geom.Rectangle(0f, 0f, 50f, 50f))
            PdfCanvas(form, pdf).rectangle(0.0, 0.0, 50.0, 50.0).fill().release()
            PdfCanvas(page).addXObjectAt(form, 20f, 20f).release()
        }
        val inherited = File(dir, "inherit.pdf")
        PdfDocument(PdfReader(built), PdfIo.workFileWriter(inherited.absolutePath)).use { pdf ->
            val page = pdf.getPage(1).pdfObject
            val resources = page.get(PdfName.Resources)
            page.remove(PdfName.Resources)
            pdf.catalog.pdfObject.getAsDictionary(PdfName.Pages).put(PdfName.Resources, resources)
        }
        val output = File(dir, "inherit_typed.pdf")
        val plan = PdfMarkSave.Plan(added = mapOf(0 to listOf(typed("i", "inherited", 400, 12f, 30.0, 500.0))))

        assertTrue(PdfMarkSave.write(inherited, output, plan, 2, { NoProgress }, fonts) {})

        PdfDocument(PdfReader(output)).use { pdf ->
            val page = pdf.getPage(1)
            val own = page.pdfObject.getAsDictionary(PdfName.Resources)
            assertNotNull(own)
            assertEquals(1, own.getAsDictionary(PdfName.XObject).size())
            assertEquals(1, ownFonts(page).size)
            val extracted = PdfTextExtractor.getTextFromPage(page)
            assertTrue(extracted, extracted.contains("inherited"))
        }
    }

    @Test
    fun aTypedMarkAcrossTwoLabelsReachesBothSourcesCutToEachLabelWithItsFont() {
        val (left, right) = sideBySide()
        val top = (left.top + left.bottom) / 2 + 20
        val mark = typed("x", "A text reaching over both labels", 400, 24f, left.left + 20, top)

        assertEquals(2, project(save(mapOf(0 to listOf(mark)))))

        for (source in listOf(left, right).map { areas().indexOf(it) }) {
            val content = layer(source)!!
            val read = PdfMarkContent.read(content).map { it.mark }
            assertEquals(listOf("x"), read.map { it.id })
            assertEquals("A text reaching over both labels", read.single().text!!.text)
            val text = String(content, Charsets.ISO_8859_1)
            assertTrue(text.contains("re W n"))
            assertTrue(text.contains(" Tf"))
            assertEquals(PdfMarkContent.fontNames(content), layerFonts(source).keys.map { it.value }.toSet())
        }
        for (other in areas().indices - listOf(left, right).map { areas().indexOf(it) }.toSet()) assertNull(layer(other))
    }

    @Test
    fun erasingAProjectedTypedMarkDropsItsFontFromTheLayer() {
        val area = areas()[0]
        val middle = (area.top + area.bottom) / 2
        project(
            save(
                mapOf(
                    0 to listOf(
                        typed("a", "erase me", 600, 12f, area.left + 10, middle + 30),
                        pen("b", area.left + 10, middle - 20, area.right - 10, middle - 20)
                    )
                )
            )
        )
        build()
        assertEquals(1, layerFonts(0).size)

        project(saveStreams(layerCopies().associateWith { PdfStreamEdit(false, setOf("a")) }))

        assertEquals(listOf("b"), PdfMarkContent.read(layer(0)!!).map { it.mark.id })
        assertTrue(layerFonts(0).isEmpty())
    }

    @Test
    fun aTypedMarkRewrittenAgainAndAgainKeepsTheSheetDepthAndTheSourceSize() {
        val area = areas()[1]
        var depthAfterFirst = -1
        val sizes = ArrayList<Long>()
        for (round in 0 until 6) {
            val erase = if (round == 0) emptyMap() else layerCopies().associateWith { PdfStreamEdit(false, setOf("r${round - 1}")) }
            val mark = typed("r$round", "Label $round\nkeep it", 500, 13f, area.left + 12, area.top - 20)
            val marks = if (round == 0) listOf(pen("keep", area.left + 10, area.bottom + 20, area.right - 10, area.bottom + 20), mark) else listOf(mark)
            project(write(PdfMarkSave.Plan(added = mapOf(0 to marks), streams = erase)))
            build()
            val depth = maxDepth(nup())
            if (round == 0) depthAfterFirst = depth else assertEquals("round $round", depthAfterFirst, depth)
            assertEquals(listOf("keep", "r$round"), PdfMarkContent.read(layer(1)!!).map { it.mark.id })
            assertEquals(1, layerFonts(1).size)
            sizes += File(dir, "source_temp/0001.pdf").length()
        }
        assertTrue("source sizes $sizes", sizes.last() - sizes[1] < 1_000)
    }

    @Test
    fun aTypedMarkErasedBeforeItsProjectionTakesItsFontOffThePage() {
        val area = areas()[2]
        val first = save(mapOf(0 to listOf(typed("t", "only on the sheet", 400, 12f, area.left + 10, area.top - 20))))
        val stream = PdfDocument(PdfReader(first)).use { pdf ->
            val page = pdf.getPage(1)
            assertEquals(1, ownFonts(page).size)
            page.getContentStream(page.contentStreamCount - 1).indirectReference.objNumber
        }

        val erased = File(dir, "erased.pdf")
        val plan = PdfMarkSave.Plan(streams = mapOf(stream to PdfStreamEdit(false, setOf("t"))), editedPages = setOf(0))
        assertTrue(PdfMarkSave.write(first, erased, plan, 2, { NoProgress }, fonts) {})
        val blanked = File(dir, "blanked.pdf")
        assertTrue(PdfMarkSave.write(first, blanked, PdfMarkSave.Plan(blankAll = true), 2, { NoProgress }, fonts) {})

        for (file in listOf(erased, blanked)) {
            PdfDocument(PdfReader(file)).use { pdf -> assertTrue(file.name, ownFonts(pdf.getPage(1)).isEmpty()) }
            TestPdfs.assertWorkFile(file)
        }
    }

    @Test
    fun aTypedMarkReadsBackThroughAProjectionMatrixWithItsSizeAndTurn() {
        val turned = PdfMarkText("Top\nEnd", 900, 10f, doubleArrayOf(0.0, 1.0, -1.0, 0.0, 100.0, 50.0))
        val content = PdfMarkContent.write(listOf(PdfMark("q1", PdfMarkKind.TYPED, 0xDD0000, 0f, PdfMarkPath(ByteArray(0), FloatArray(0)), turned)), null, FakeFonts)
        val wrapped = "q\n0.5 0 0 0.5 20 30 cm\n".toByteArray(Charsets.ISO_8859_1) + content + "Q\n".toByteArray(Charsets.ISO_8859_1)

        val mark = PdfMarkContent.read(wrapped).single().mark

        val text = mark.text!!
        assertEquals("Top\nEnd", text.text)
        assertEquals(900, text.weight)
        assertEquals(0xDD0000, mark.color)
        assertEquals(5f, text.size, 0.001f)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, -1.0, 0.0, 70.0, 55.0), text.matrix, 0.001)
        val first = rectangles(mark.path)[0]
        assertArrayEquals(doubleArrayOf(70.0, 55.0, 70.0 + PdfMarkText.LINE_HEIGHT * 5, 55.0 + 1.5 * 5), first, 0.001)
        assertEquals(setOf("F900"), PdfMarkContent.fontNames(wrapped))
    }

    @Test
    fun aTypedMarkWithoutItsTextIsNoMark() {
        val content = "/ReePDF <</Id(z)/K/Typed/Wt 400/L [1]>> BDC\nq\nBT\n/F1 12 Tf\n1 0 0 1 10 10 Tm\n<0001> Tj\nET\nQ\nEMC\n"

        assertTrue(PdfMarkContent.read(content.toByteArray(Charsets.ISO_8859_1)).isEmpty())
        assertEquals(setOf("F1", "F2"), PdfMarkContent.fontNames("BT /F1 12 Tf (a) Tj /F2 9 Tf ET /X0 Do".toByteArray(Charsets.ISO_8859_1)))
    }

    private object FakeFonts : PdfMarkContent.Fonts {
        override fun name(weight: Int): String = "F$weight"

        override fun encode(weight: Int, line: String): ByteArray = ByteArray(line.length * 2) { if (it % 2 == 0) 0 else line[it / 2].code.toByte() }

        override fun width(weight: Int, line: String): Double = line.length * 0.5
    }

    private fun fontBytes(weight: Int): ByteArray {
        val name = "roboto_${fontFiles.getValue(weight)}.ttf"
        return listOf(File("../app/src/main/res/font/$name"), File("app/src/main/res/font/$name")).first { it.isFile }.readBytes()
    }

    private fun typed(id: String, text: String, weight: Int, size: Float, left: Double, top: Double, color: Int = 0) =
        PdfMark(id, PdfMarkKind.TYPED, color, 0f, PdfMarkPath(ByteArray(0), FloatArray(0)), PdfMarkText(text, weight, size, doubleArrayOf(1.0, 0.0, 0.0, 1.0, left, top)))

    private fun pen(id: String, x1: Double, y1: Double, x2: Double, y2: Double) = PdfMark(
        id, PdfMarkKind.PEN, 0xDD0000, 3f,
        PdfMarkPath.Builder().moveTo(x1.toFloat(), y1.toFloat()).lineTo(x2.toFloat(), y2.toFloat()).build()
    )

    private fun build() {
        PdfNupBuilder.build(
            source = PdfSourceParts.open(File(dir, "source_temp")),
            totalSrcPages = 4,
            outFile = nup(),
            workDir = dir,
            config = scaled,
            rotation = IntArray(4),
            position = IntArray(4) { NupGeometry.DEFAULT_POSITION },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = { NoProgress }
        )
    }

    private fun save(added: Map<Int, List<PdfMark>>): File = write(PdfMarkSave.Plan(added = added))

    private fun saveStreams(streams: Map<Int, PdfStreamEdit>): File = write(PdfMarkSave.Plan(streams = streams))

    private fun write(plan: PdfMarkSave.Plan): File {
        val output = File(dir, "edited_${++saves}.pdf")
        assertTrue(PdfMarkSave.write(nup(), output, plan, 2, { NoProgress }, fonts) {})
        return output
    }

    private fun project(edited: File): Int =
        PdfEditProjection.project(PdfSourceParts.open(File(dir, "source_temp")), edited, 2, writeStage = { NoProgress }) {}.changed

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun areas(): List<NupArea> = PdfDocument(PdfReader(nup())).use { pdf ->
        val base = PdfEditProjection.privateData(pdf.getPage(1).pdfObject)!!.getAsDictionary(PdfName("Base"))
        PdfEditProjection.cells(base).map { cell -> hu.reelee81.reepdf.core.NupCellArea.of(cell.box!!, cell.matrix, cell.slot!!)!! }
    }

    private fun sideBySide(): Pair<NupArea, NupArea> {
        val all = areas()
        for (a in all) {
            for (b in all) {
                if (b.left > a.right - 1 && b.bottom < a.top && b.top > a.bottom) return a to b
            }
        }
        throw AssertionError("no labels side by side")
    }

    private fun layerCopies(): List<Int> = PdfDocument(PdfReader(nup())).use { pdf ->
        PdfEditProjection.editStreams(pdf.getPage(1)).filter { PdfEditProjection.isEditLayer(it) }.map { it.indirectReference.objNumber }
    }

    private fun layerStream(pdf: PdfDocument, index: Int): PdfStream? =
        PdfEditProjection.privateData(pdf.getPage(index + 1).pdfObject)?.getAsStream(PdfName("LayerForm"))

    private fun layer(index: Int): ByteArray? = PdfDocument(PdfReader(File(dir, "source_temp/0001.pdf"))).use { pdf -> layerStream(pdf, index)?.bytes }

    private fun layerFonts(index: Int): Map<PdfName, PdfDictionary> = PdfDocument(PdfReader(File(dir, "source_temp/0001.pdf"))).use { pdf ->
        val fonts = layerStream(pdf, index)?.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.Font) ?: return@use emptyMap()
        fonts.keySet().associateWith { fonts.getAsDictionary(it) }
    }

    private fun ownFonts(page: PdfPage): Map<String, PdfDictionary> {
        val fonts = page.pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.Font) ?: return emptyMap()
        return fonts.keySet().filter { it.value.startsWith(PdfMarkContent.FONT_PREFIX) }.associate { it.value to fonts.getAsDictionary(it) }
    }

    private fun fontFile(font: PdfDictionary): ByteArray {
        val descendant = font.getAsArray(PdfName.DescendantFonts).getAsDictionary(0)
        return descendant.getAsDictionary(PdfName.FontDescriptor).getAsStream(PdfName.FontFile2).bytes
    }

    private fun tables(font: ByteArray): Set<String> {
        val count = ByteBuffer.wrap(font).getShort(4).toInt()
        return (0 until count).map { String(font, 12 + it * 16, 4, Charsets.ISO_8859_1) }.toSet()
    }

    private fun rectangles(path: PdfMarkPath): List<DoubleArray> {
        val result = ArrayList<DoubleArray>()
        var point = 0
        var box: DoubleArray? = null
        for (verb in path.verbs) {
            val count = PdfMarkPath.pointsOf(verb) / 2
            repeat(count) {
                val x = path.points[point].toDouble()
                val y = path.points[point + 1].toDouble()
                point += 2
                box = box?.let { doubleArrayOf(minOf(it[0], x), minOf(it[1], y), max(it[2], x), max(it[3], y)) } ?: doubleArrayOf(x, y, x, y)
            }
            if (verb == PdfMarkPath.CLOSE) {
                box?.let(result::add)
                box = null
            }
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
