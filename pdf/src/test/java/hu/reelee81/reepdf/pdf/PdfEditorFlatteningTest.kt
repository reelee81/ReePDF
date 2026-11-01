package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.annot.PdfAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import com.itextpdf.kernel.utils.PdfAnnotationFlattener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfEditorFlatteningTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val flattenedTypes = setOf(PdfName.Stamp, PdfName.Ink, PdfName.Highlight, PdfName.FreeText)
    private val placement = doubleArrayOf(0.5, 0.0, 0.0, 0.5, 100.0, 200.0)

    @Test
    fun aLongLayoutIsFlattenedLikeBefore() {
        val flattened = assertFlattenedLikeBefore(annotate(buildNup(makeSource()), withHighlight = true))

        PdfDocument(PdfReader(flattened)).use { pdf ->
            for (number in listOf(1, 60, 125, 250)) assertEquals("page $number", 2, pdf.getPage(number).contentStreamCount)
            assertEquals(1, pdf.getPage(3).contentStreamCount)
            assertEquals(listOf(PdfName.Link), pdf.getPage(2).annotations.map { it.subtype })
        }
    }

    @Test
    fun editsDrawnOverTheLayoutAreProjectedFromTheFlattenedFile() {
        val source = makeSource()
        val flattened = temporary.newFile()
        PdfEditorFlattening.flatten(annotate(buildNup(source), withHighlight = false), flattened, { NoProgress }) {}

        val parts = temporary.newFolder()
        source.copyTo(File(parts, "0001.pdf"))
        val projected = PdfEditProjection.project(PdfSourceParts.open(parts), flattened, batchSize = 100, writeStage = { NoProgress }) {}
        assertEquals(3, projected.changed)
    }

    @Test
    fun pagesSharingAResourceDictionaryAreFlattenedLikeBefore() {
        assertFlattenedLikeBefore(threePages(Resources.SHARED, annotated = setOf(1, 3)))
    }

    @Test
    fun pagesInheritingTheirResourcesAreFlattenedLikeBefore() {
        assertFlattenedLikeBefore(threePages(Resources.INHERITED, annotated = setOf(1, 3)))
    }

    @Test
    fun anUntouchedPageSharingItsContentWithAnAnnotatedPageIsFlattenedLikeBefore() {
        val flattened = assertFlattenedLikeBefore(threePages(Resources.OWN, annotated = setOf(2), sharedContent = true))
        PdfDocument(PdfReader(flattened)).use { pdf ->
            assertEquals(1, pdf.getPage(1).contentStreamCount)
            assertEquals(2, pdf.getPage(2).contentStreamCount)
            assertEquals("BT /F1 12 Tf 40 780 Td (PAGE 1) Tj ET", text(pdf.getPage(2).getContentStream(0)))
        }
    }

    @Test
    fun anAnnotationListedOnTwoPagesIsFlattenedLikeBefore() {
        assertFlattenedLikeBefore(threePages(Resources.OWN, annotated = emptySet(), sharedAnnotation = true))
    }

    @Test
    fun aTaggedDocumentIsFlattenedLikeBefore() {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            pdf.setTagged()
            val font = PdfFontFactory.createFont()
            repeat(3) { index ->
                PdfCanvas(pdf.addNewPage(PageSize.A4)).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 780.0).showText("PAGE ${index + 1}").endText()
            }
            addAnnotation(pdf, 2, PdfName.Ink) { put(PdfName.InkList, inkList()) }
        }
        assertTrue(PdfDocument(PdfReader(file)).use { it.isTagged })
        assertFlattenedLikeBefore(file)
    }

    @Test
    fun progressAdvancesPerPageAndACancellationStopsTheFlattening() {
        val input = threePages(Resources.OWN, annotated = setOf(2))
        val progress = CountingProgress()
        PdfEditorFlattening.flatten(input, temporary.newFile(), { total -> progress.also { it.total = total } }) {}
        assertEquals(3L, progress.total)
        assertEquals(3, progress.advanced)
        assertEquals(1, progress.idled)

        var checks = 0
        try {
            PdfEditorFlattening.flatten(input, temporary.newFile(), { CountingProgress() }) {
                if (++checks == 2) throw CancellationException("cancelled")
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(2, checks)
    }

    private fun assertFlattenedLikeBefore(input: File): File {
        val before = temporary.newFile()
        flattenLikeBefore(input, before)
        val after = temporary.newFile()
        PdfEditorFlattening.flatten(input, after, { NoProgress }) {}
        val expected = describePages(before)
        assertEquals(expected.size, describePages(input).size)
        assertEquals(expected, describePages(after))
        PdfDocument(PdfReader(after)).use { pdf ->
            for (number in 1..pdf.numberOfPages) {
                val page = pdf.getPage(number)
                assertTrue("page $number", page.annotations.none { it.subtype in flattenedTypes })
                val xObjects = page.resources.pdfObject.getAsDictionary(PdfName.XObject)
                for (index in 0 until page.contentStreamCount) {
                    for (call in doCall.findAll(text(page.getContentStream(index)))) {
                        assertTrue("page $number ${call.value}", xObjects?.containsKey(PdfName(call.groupValues[1])) == true)
                    }
                }
            }
        }
        return after
    }

    private val doCall = Regex("""/([^\s/\[\]<>(){}%]+)\s+Do\b""")

    private fun flattenLikeBefore(file: File, flatFile: File) {
        PdfDocument(PdfReader(file.absolutePath), PdfWriter(flatFile.absolutePath, PdfIo.writerProperties())).use { pdf ->
            val flattener = PdfAnnotationFlattener()
            for (pageNumber in 1..pdf.numberOfPages) {
                val annotations = pdf.getPage(pageNumber).annotations.filter { it.subtype in flattenedTypes }
                if (annotations.isNotEmpty()) flattener.flatten(annotations)
            }
        }
    }

    private fun describePages(file: File): List<String> = PdfDocument(PdfReader(file)).use { pdf ->
        (1..pdf.numberOfPages).map { describe(pdf.getPage(it).pdfObject, HashSet()) }
    }

    private fun describe(value: PdfObject?, seen: MutableSet<PdfObject>): String = when (value) {
        null -> "null"
        is PdfIndirectReference -> describe(value.refersTo, seen)
        is PdfStream -> if (!seen.add(value)) "<seen>" else "stream" + entries(value, seen) + text(value)
        is PdfDictionary -> if (!seen.add(value)) "<seen>" else entries(value, seen)
        is PdfArray -> (0 until value.size()).joinToString(" ", "[", "]") { describe(value.get(it, false), seen) }
        else -> value.toString()
    }

    private fun entries(dictionary: PdfDictionary, seen: MutableSet<PdfObject>): String =
        dictionary.keySet()
            .filter { it !in ignoredKeys }
            .sortedBy { it.value }
            .joinToString(" ", "<<", ">>") { "$it ${describe(dictionary.get(it, false), seen)}" }

    private val ignoredKeys = setOf(PdfName.Parent, PdfName.Length, PdfName.Filter, PdfName.DecodeParms)

    private fun text(stream: PdfStream): String = String(stream.bytes, Charsets.ISO_8859_1).trim()

    private fun makeSource(): File {
        val file = temporary.newFile()
        PdfDocument(PdfWriter(file)).use { pdf ->
            val font = PdfFontFactory.createFont()
            repeat(250) { index ->
                PdfCanvas(pdf.addNewPage(PageSize.A4)).beginText().setFontAndSize(font, 12f)
                    .moveText(40.0, 780.0).showText("SOURCE ${index + 1}").endText()
            }
        }
        return file
    }

    private fun buildNup(source: File): File {
        val file = temporary.newFile()
        val generation = PdfEditProjection.newGeneration()
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { out ->
            PdfDocument(PdfReader(source)).use { src ->
                for (number in 1..src.numberOfPages) {
                    val page = out.addNewPage(PageSize.A4)
                    val form = src.getPage(number).copyAsFormXObject(out)
                    val canvas = PdfCanvas(page)
                    canvas.saveState()
                    PdfCopySupport.concatPlacementMatrix(
                        canvas, placement[0], placement[1], placement[2], placement[3], placement[4], placement[5]
                    )
                    canvas.addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                    canvas.restoreState()
                    PdfEditProjection.markNupPage(page, listOf(PdfEditProjection.Cell(number - 1, placement)), generation)
                }
            }
        }
        return file
    }

    private fun annotate(nup: File, withHighlight: Boolean): File {
        val file = temporary.newFile()
        PdfDocument(PdfReader(nup), PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { pdf ->
            addAnnotation(pdf, 1, PdfName.Ink) { put(PdfName.InkList, inkList()) }
            addAnnotation(pdf, 60, PdfName.FreeText) { put(PdfName.DA, PdfString("/Helv 12 Tf 0 g")) }
            if (withHighlight) {
                val highlight = addAnnotation(pdf, 125, PdfName.Highlight) {
                    put(PdfName.QuadPoints, PdfArray(floatArrayOf(100f, 340f, 200f, 340f, 100f, 300f, 200f, 300f)))
                }
                val popup = PdfDictionary().apply {
                    put(PdfName.Type, PdfName.Annot)
                    put(PdfName.Subtype, PdfName.Popup)
                    put(PdfName.Rect, PdfArray(Rectangle(220f, 300f, 100f, 60f)))
                    put(PdfName.Parent, highlight)
                }
                popup.makeIndirect(pdf)
                highlight.put(PdfName.Popup, popup)
                pdf.getPage(125).addAnnotation(PdfAnnotation.makeAnnotation(popup))
            }
            addAnnotation(pdf, 250, PdfName.Stamp) {}
            val link = PdfDictionary().apply {
                put(PdfName.Type, PdfName.Annot)
                put(PdfName.Subtype, PdfName.Link)
                put(PdfName.Rect, PdfArray(Rectangle(40f, 40f, 100f, 20f)))
                put(PdfName.Dest, PdfArray(listOf(pdf.getPage(3).pdfObject, PdfName.Fit)))
            }
            link.makeIndirect(pdf)
            pdf.getPage(2).addAnnotation(PdfAnnotation.makeAnnotation(link))
        }
        return file
    }

    private fun addAnnotation(
        pdf: PdfDocument,
        pageNumber: Int,
        subtype: PdfName,
        extra: PdfDictionary.() -> Unit
    ): PdfDictionary {
        val appearance = PdfFormXObject(Rectangle(0f, 0f, 100f, 40f))
        PdfCanvas(appearance, pdf).rectangle(2.0, 2.0, 96.0, 36.0).stroke()
        appearance.pdfObject.makeIndirect(pdf)
        val dictionary = PdfDictionary().apply {
            put(PdfName.Type, PdfName.Annot)
            put(PdfName.Subtype, subtype)
            put(PdfName.Rect, PdfArray(Rectangle(100f, 300f, 100f, 40f)))
            put(PdfName.F, PdfNumber(4))
            put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance.pdfObject) })
            extra()
        }
        dictionary.makeIndirect(pdf)
        pdf.getPage(pageNumber).addAnnotation(PdfAnnotation.makeAnnotation(dictionary))
        return dictionary
    }

    private fun inkList(): PdfArray = PdfArray(listOf(PdfArray(floatArrayOf(105f, 305f, 150f, 335f, 195f, 305f))))

    private enum class Resources { OWN, SHARED, INHERITED }

    private fun threePages(
        resources: Resources,
        annotated: Set<Int>,
        sharedContent: Boolean = false,
        sharedAnnotation: Boolean = false
    ): File {
        val raw = RawPdf()
        val catalog = raw.reserve()
        val tree = raw.reserve()
        val pages = List(3) { raw.reserve() }
        val font = raw.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val ownResources = "<< /Font << /F1 $font 0 R >> >>"
        val shared = raw.add(ownResources)
        val contents = List(3) { index -> raw.stream("", "BT /F1 12 Tf 40 780 Td (PAGE ${index + 1}) Tj ET") }
        val common = if (sharedAnnotation) raw.ink(pages[0], 300) else null
        pages.forEachIndexed { index, page ->
            val annotations = ArrayList<Int>()
            if (index + 1 in annotated) annotations += raw.ink(page, 100)
            if (common != null && index != 1) annotations += common
            val content = if (sharedContent && index == 1) contents[0] else contents[index]
            val pageResources = when (resources) {
                Resources.OWN -> " /Resources $ownResources"
                Resources.SHARED -> " /Resources $shared 0 R"
                Resources.INHERITED -> ""
            }
            val annots = if (annotations.isEmpty()) "" else annotations.joinToString(" ", " /Annots [", "]") { "$it 0 R" }
            raw.put(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 595 842] /Contents $content 0 R$pageResources$annots >>")
        }
        val inherited = if (resources == Resources.INHERITED) " /Resources $shared 0 R" else ""
        raw.put(tree, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count 3$inherited >>")
        raw.put(catalog, "<< /Type /Catalog /Pages $tree 0 R >>")
        return raw.write(temporary.newFile(), catalog)
    }

    private class RawPdf {
        private val bodies = ArrayList<String>()

        fun reserve(): Int {
            bodies += ""
            return bodies.size
        }

        fun put(number: Int, body: String) {
            bodies[number - 1] = body
        }

        fun add(body: String): Int = reserve().also { put(it, body) }

        fun stream(dictionary: String, content: String): Int =
            add("<< $dictionary /Length ${content.length} >>\nstream\n$content\nendstream")

        fun ink(page: Int, x: Int): Int {
            val appearance = stream("/Type /XObject /Subtype /Form /BBox [0 0 100 40]", "1 0 0 RG 2 w 2 2 96 36 re S")
            return add(
                "<< /Type /Annot /Subtype /Ink /Rect [$x 300 ${x + 100} 340] /F 4 " +
                    "/InkList [[${x + 5} 305 ${x + 95} 335]] /AP << /N $appearance 0 R >> /P $page 0 R >>"
            )
        }

        fun write(file: File, root: Int): File {
            val out = ByteArrayOutputStream()
            out.write("%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1))
            val offsets = bodies.mapIndexed { index, body ->
                out.size().also { out.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
            }
            val start = out.size()
            val xref = StringBuilder("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
            for (offset in offsets) xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
            xref.append("trailer\n<< /Size ${bodies.size + 1} /Root $root 0 R >>\nstartxref\n$start\n%%EOF\n")
            out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
            file.writeBytes(out.toByteArray())
            return file
        }
    }

    private class CountingProgress : PdfProgress {
        var total = -1L
        var advanced = 0
        var idled = 0

        override fun advance(units: Int) {
            advanced += units
        }

        override fun idle() {
            idled++
        }
    }

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }
}
