package hu.reelee81.reepdf.pdf

import com.itextpdf.io.font.FontProgramFactory
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.annot.PdfAnnotation
import com.itextpdf.kernel.pdf.annot.PdfSquareAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.layer.PdfLayer
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfSharingFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private var saves = 0
    private val single = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val grid = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private val password = "titok".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun fourImportsOfOneLabelLeaveOneCopyInTheWorkspaceAndInTheLayout() {
        val label = label("label.pdf")
        val source = import(List(4) { label })
        assertCount(1, distinct(source.file(0), PdfName.Image))
        build(source, 4, grid)
        assertCount(1, distinct(nup(), PdfName.Image))
        assertCount(2, distinct(nup(), PdfName.Form))
        assertCount(2, maxDepth(nup()))
    }

    @Test
    fun identicalPagesImportedInSinglePageBatchesStayValidAndShared() {
        val source = import(List(3) { label("label.pdf", annotated = true, embedded = true) }, batchSize = 1)
        assertCount(1, distinct(source.file(0), PdfName.Image))
        assertCount(1, fontPrograms(source.file(0)))
        build(source, 3, single)
        PdfDocument(PdfReader(nup())).use { pdf ->
            for (number in 1..3) assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(number)).contains("LABEL"))
        }
        assertCount(1, distinct(nup(), PdfName.Image))
        assertCount(1, fontPrograms(nup()))
    }

    @Test
    fun editingOneOfTwoIdenticallyEditedPagesLeavesTheOtherAlone() {
        val label = label("label.pdf")
        build(import(List(2) { label }), 2, single)
        project(save(PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("same")), 1 to listOf(pen("same"))))))
        assertEquals(listOf("same"), layerIds(0))
        assertEquals(listOf("same"), layerIds(1))

        project(save(PdfMarkSave.Plan(blankPages = setOf(0))))
        assertNull(sourceLayer(0))
        assertEquals(listOf("same"), layerIds(1))

        project(save(PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("new"))))))
        assertEquals(listOf("new"), layerIds(0))
        assertEquals(listOf("same"), layerIds(1))
    }

    @Test
    fun documentsWithLayersKeepTheirOwnVisibility() {
        val source = import(listOf(layered("shown.pdf", hidden = false), layered("hidden.pdf", hidden = true)))
        PdfDocument(PdfReader(source.file(0))).use { pdf ->
            assertTrue(FILLED.containsMatchIn(formContent(pdf, 1)))
            assertFalse(FILLED.containsMatchIn(formContent(pdf, 2)))
        }
    }

    @Test
    fun fieldsRegeneratedOneByOneShareOneFontProgram() {
        val source = import(listOf(fields()))
        assertCount(1, fontPrograms(source.file(0)))
        PdfDocument(PdfReader(source.file(0))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            for (index in 1..FIELDS) assertTrue(text.contains("VALUE$index"))
        }
    }

    @Test
    fun aPasswordProtectedExportKeepsTheSharedContentAndOpens() {
        val label = label("label.pdf")
        build(import(List(4) { label }), 4, single)
        val result = runBlocking { export().execute {} }
        PdfDocument(reader(result.file, password)).use { pdf ->
            assertCount(4, pdf.numberOfPages)
            for (number in 1..4) assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(number)).contains("LABEL"))
        }
        assertCount(1, distinct(result.file, PdfName.Image, password))
    }

    @Test
    fun strokesOfAReopenedSavedFileStayVisibleButCannotBeErased() {
        val label = label("label.pdf")
        build(import(List(2) { label }), 2, single)
        project(save(PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("saved")), 1 to listOf(pen("saved"))))))
        for (number in 1..2) assertEquals(listOf("saved"), erasable(number))
        val saved = runBlocking { export(password = null).execute {} }.file
        val reopened = saved.copyTo(File(temp.newFolder(), "reopened.pdf"))
        File(dir, "source_temp").deleteRecursively()
        nup().delete()
        build(import(listOf(reopened)), 2, single)
        for (number in 1..2) {
            assertEquals(emptyList<String>(), erasable(number))
            assertEquals(listOf("saved"), drawn(number))
        }
        project(save(PdfMarkSave.Plan(added = mapOf(0 to listOf(pen("new"))))))
        assertEquals(listOf("new"), erasable(1))
        assertEquals(emptyList<String>(), erasable(2))
        assertEquals(listOf("new", "saved"), drawn(1).sorted())
        assertEquals(listOf("saved"), drawn(2))
    }

    private fun label(name: String, annotated: Boolean = false, embedded: Boolean = false): File {
        val file = File(dir, name)
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A6)
            val font = if (embedded) {
                PdfFontFactory.createFont(
                    FontProgramFactory.createFont(roboto()), PdfEncodings.WINANSI, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED
                ).also { it.isSubset = false }
            } else {
                PdfFontFactory.createFont()
            }
            val form = PdfFormXObject(Rectangle(80f, 80f))
            PdfCanvas(form, pdf).rectangle(5.0, 5.0, 30.0, 30.0).fill().rectangle(40.0, 40.0, 30.0, 30.0).fill()
            val image = PdfImageXObject(ImageDataFactory.create(40, 40, 1, 8, ByteArray(1600) { (it % 251).toByte() }, null))
            PdfCanvas(page).addXObjectAt(form, 10f, 10f)
                .addXObjectFittedIntoRectangle(image, Rectangle(120f, 10f, 40f, 40f))
                .beginText().setFontAndSize(font, 12f).moveText(20.0, 200.0).showText("LABEL").endText()
            if (annotated) {
                val appearance = PdfFormXObject(Rectangle(40f, 40f))
                PdfCanvas(appearance, pdf).rectangle(2.0, 2.0, 36.0, 36.0).stroke()
                page.addAnnotation(
                    PdfSquareAnnotation(Rectangle(20f, 120f, 40f, 40f)).setFlag(PdfAnnotation.PRINT).setNormalAppearance(appearance.pdfObject)
                )
            }
        }
        return file
    }

    private fun layered(name: String, hidden: Boolean): File {
        val file = File(dir, name)
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A6)
            val layer = PdfLayer("L", pdf).apply { isOn = !hidden }
            val form = PdfFormXObject(Rectangle(100f, 100f))
            PdfCanvas(form, pdf).also {
                it.beginLayer(layer)
                it.rectangle(0.0, 0.0, 50.0, 50.0).fill()
                it.endLayer()
            }
            PdfCanvas(page).addXObjectAt(form, 10f, 10f)
        }
        return file
    }

    private fun fields(): File {
        val file = File(dir, "fields.pdf")
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A5)
            val font = PdfFontFactory.createFont(
                FontProgramFactory.createFont(roboto()), PdfEncodings.WINANSI, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED
            ).also { it.isSubset = false }
            pdf.addFont(font)
            val fieldList = PdfArray()
            pdf.catalog.pdfObject.put(PdfName.AcroForm, PdfDictionary().apply {
                put(PdfName.Fields, fieldList)
                put(PdfName.DA, PdfString("/Rob 12 Tf 0 g"))
                put(PdfName.DR, PdfDictionary().apply { put(PdfName.Font, PdfDictionary().apply { put(PdfName("Rob"), font.pdfObject) }) })
            })
            for (index in 1..FIELDS) {
                val widget = PdfDictionary().apply {
                    put(PdfName.Type, PdfName.Annot)
                    put(PdfName.Subtype, PdfName.Widget)
                    put(PdfName.Rect, PdfArray(Rectangle(30f, 40f * index, 200f, 30f)))
                    put(PdfName.FT, PdfName.Tx)
                    put(PdfName.T, PdfString("field$index"))
                    put(PdfName.V, PdfString("VALUE$index"))
                    put(PdfName.DA, PdfString("/Rob 12 Tf 0 g"))
                    put(PdfName.F, PdfNumber(PdfAnnotation.PRINT))
                    makeIndirect(pdf)
                }
                page.addAnnotation(PdfAnnotation.makeAnnotation(widget))
                fieldList.add(widget)
            }
        }
        return file
    }

    private fun roboto(): ByteArray =
        listOf(File("../app/src/main/res/font/roboto_regular.ttf"), File("app/src/main/res/font/roboto_regular.ttf"))
            .first { it.isFile }
            .readBytes()

    private fun import(files: List<File>, batchSize: Int = PageBatch.PAGES): PdfSourceParts {
        val counts = files.map { file -> PdfDocument(PdfReader(file.absolutePath)).use { it.numberOfPages } }
        val empty = PdfSourceParts.empty(File(dir, "source_temp"))
        return empty.write(
            SourcePartPlan.append(empty.layout, files.indices.map { SourcePartPlan.Piece.Incoming(it, 0, counts[it] - 1) }),
            batchSize,
            NoProgress,
            PdfSourceParts.Inputs(
                incoming = files.map { PdfSourceParts.Incoming(it, null) },
                copierDir = dir,
                generateAppearances = { _, _ -> throw IOException("no MuPDF") }
            )
        )
    }

    private fun build(source: PdfSourceParts, pages: Int, config: NupConfig) {
        PdfNupBuilder.build(
            source = source,
            totalSrcPages = pages,
            outFile = nup(),
            workDir = dir,
            config = config,
            rotation = IntArray(pages),
            position = IntArray(pages) { 5 },
            crop = emptyMap(),
            batchSize = 2,
            progressStage = { NoProgress }
        )
    }

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun pen(id: String) = PdfMark(
        id, PdfMarkKind.PEN, 0xDD0000, 3f,
        PdfMarkPath.Builder().moveTo(50f, 150f).curveTo(60f, 160f, 70f, 140f, 80f, 150f).build()
    )

    private fun save(plan: PdfMarkSave.Plan): File {
        val output = File(dir, "edited_${++saves}.pdf")
        assertTrue(PdfMarkSave.write(nup(), output, plan, 2, ProgressTracker { 0L }.let { it::stage }) {})
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
                    config = single, builtConfig = single, crops = emptyMap(),
                    rotation = IntArray(2), position = IntArray(2) { 5 }, defaultPosition = 5,
                    batchSize = 2, selected = emptyList()
                ),
                progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L),
                copyEdited = { target -> edited.copyTo(target, overwrite = true) }
            ).execute {}
        }
    }

    private fun sourceLayer(sourceIndex: Int): ByteArray? {
        val part = File(dir, "source_temp").listFiles { file -> file.name.endsWith(".pdf") }.orEmpty().single()
        return PdfDocument(PdfReader(part)).use { pdf ->
            PdfEditProjection.privateData(pdf.getPage(sourceIndex + 1).pdfObject)?.getAsStream(PdfName("LayerForm"))?.bytes
        }
    }

    private fun layerIds(sourceIndex: Int): List<String>? =
        sourceLayer(sourceIndex)?.let { content -> PdfMarkContent.read(content).map { it.mark.id } }

    private fun erasable(number: Int): List<String> = PdfDocument(PdfReader(nup())).use { pdf ->
        PdfEditProjection.editStreams(pdf.getPage(number)).flatMap { stream -> PdfMarkContent.read(stream.bytes).map { it.mark.id } }
    }

    private fun drawn(number: Int): List<String> = PdfDocument(PdfReader(nup())).use { pdf ->
        val ids = ArrayList<String>()
        val seen = HashSet<Int>()
        fun visit(resources: PdfDictionary?) {
            val xObjects = resources?.getAsDictionary(PdfName.XObject) ?: return
            for (key in xObjects.keySet()) {
                val stream = xObjects.getAsStream(key)?.takeIf { it.getAsName(PdfName.Subtype) == PdfName.Form } ?: continue
                if (!seen.add(stream.indirectReference?.objNumber ?: continue)) continue
                ids += PdfMarkContent.read(stream.bytes).map { it.mark.id }
                visit(stream.getAsDictionary(PdfName.Resources))
            }
        }
        val page = pdf.getPage(number)
        for (index in 0 until page.contentStreamCount) ids += PdfMarkContent.read(page.getContentStream(index).bytes).map { it.mark.id }
        visit(page.resources.pdfObject)
        ids
    }

    private fun export(password: ByteArray? = this.password) = PdfOutputOperation(
        files = PdfOutputOperation.Files(dir, nup(), setOf("nup_temp.pdf", "source_temp", "source_temp_copy", "nup_temp_copy.pdf")),
        saveSelection = {},
        input = PdfOutputOperation.Input(
            baseName = "Labels",
            password = password,
            packed = true,
            batchSize = 2,
            messages = PdfOutputOperation.Messages("layout missing", "name reserved", "replace failed", "rename failed"),
            budget = Long.MAX_VALUE,
            direct = false
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L)
    )

    private fun reader(file: File, password: ByteArray?): PdfReader =
        PdfReader(file.absolutePath, ReaderProperties().apply { if (password != null) setPassword(password) })

    private fun formContent(pdf: PdfDocument, number: Int): String {
        val xObjects = pdf.getPage(number).resources.pdfObject.getAsDictionary(PdfName.XObject)
        val form = xObjects.keySet().mapNotNull { xObjects.getAsStream(it) }.first { it.getAsName(PdfName.Subtype) == PdfName.Form }
        return String(form.bytes, Charsets.ISO_8859_1)
    }

    private fun distinct(file: File, subtype: PdfName, password: ByteArray? = null): Int = PdfDocument(reader(file, password)).use { pdf ->
        val found = HashSet<Int>()
        val seen = HashSet<Int>()
        fun visit(resources: PdfDictionary?) {
            val xObjects = resources?.getAsDictionary(PdfName.XObject) ?: return
            for (key in xObjects.keySet()) {
                val stream = xObjects.getAsStream(key) ?: continue
                val number = stream.indirectReference?.objNumber ?: continue
                if (stream.getAsName(PdfName.Subtype) == subtype) found += number
                if (seen.add(number)) visit(stream.getAsDictionary(PdfName.Resources))
            }
        }
        for (number in 1..pdf.numberOfPages) visit(pdf.getPage(number).resources.pdfObject)
        found.size
    }

    private fun fontPrograms(file: File): Int = PdfDocument(PdfReader(file.absolutePath)).use { pdf ->
        val programs = HashSet<Int>()
        for (number in 1 until pdf.numberOfPdfObjects) {
            val descriptor = pdf.getPdfObject(number) as? PdfDictionary ?: continue
            if (descriptor.getAsName(PdfName.Type) != PdfName.FontDescriptor) continue
            for (key in listOf(PdfName.FontFile, PdfName.FontFile2, PdfName.FontFile3)) {
                descriptor.getAsStream(key)?.indirectReference?.objNumber?.let { programs += it }
            }
        }
        programs.size
    }

    private fun maxDepth(file: File): Int = PdfDocument(PdfReader(file.absolutePath)).use { pdf ->
        (1..pdf.numberOfPages).maxOf { depth(pdf.getPage(it).resources.pdfObject, 0, HashSet()) }
    }

    private fun depth(resources: PdfDictionary?, level: Int, seen: MutableSet<Int>): Int {
        val xObjects = resources?.getAsDictionary(PdfName.XObject) ?: return level
        var deepest = level
        for (key in xObjects.keySet()) {
            val stream = xObjects.getAsStream(key) ?: continue
            if (stream.getAsName(PdfName.Subtype) != PdfName.Form) continue
            val number = stream.indirectReference?.objNumber ?: continue
            if (!seen.add(number)) continue
            deepest = maxOf(deepest, depth(stream.getAsDictionary(PdfName.Resources), level + 1, seen))
            seen.remove(number)
        }
        return deepest
    }

    private fun assertCount(expected: Int, actual: Int) = assertEquals(expected, actual)

    private companion object {
        const val FIELDS = 3
        val FILLED = Regex("re\\s+f\\b")
    }

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }
}
