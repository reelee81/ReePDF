package hu.reelee81.reepdf.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.action.PdfAction
import com.itextpdf.kernel.pdf.annot.PdfLinkAnnotation
import com.itextpdf.kernel.pdf.annot.PdfSquareAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfSharingWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun identicalContentFromTwoDocumentsIsWrittenOnce() {
        read(copy(listOf(source("a.pdf"), source("b.pdf")))) { pdf ->
            assertCount(2, pdf.numberOfPages)
            assertCount(2, (1..2).map { pdf.getPage(it).pdfObject.indirectReference.objNumber }.toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun aPlainWriterKeepsBothCopies() {
        read(copy(listOf(source("a.pdf"), source("b.pdf"))) { PdfWriter(it.absolutePath, PdfIo.writerProperties()) }) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(2, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun differentContentStaysSeparate() {
        read(copy(listOf(source("a.pdf"), source("b.pdf", content = "0 0 60 60 re f")))) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun annotationsAreNeverShared() {
        read(copy(listOf(source("a.pdf", annotated = true), source("b.pdf", annotated = true)))) { pdf ->
            val annotations = (1..2).map { pdf.getPage(it).pdfObject.getAsArray(PdfName.Annots).getAsDictionary(0).indirectReference.objNumber }
            assertCount(2, annotations.toSet().size)
        }
    }

    @Test
    fun objectsCarryingOwnEditDataAreNeverShared() {
        read(copy(listOf(source("a.pdf", tagged = true), source("b.pdf", tagged = true)))) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun aFormThatCallsItselfIsCopiedWithoutLooping() {
        read(copy(listOf(source("a.pdf", selfCalling = true), source("b.pdf", selfCalling = true)))) { pdf ->
            for (number in 1..2) {
                val form = pdf.getPage(number).resources.pdfObject.getAsDictionary(PdfName.XObject).keySet()
                    .mapNotNull { pdf.getPage(number).resources.pdfObject.getAsDictionary(PdfName.XObject).getAsStream(it) }
                    .first { it.getAsName(PdfName.Subtype) == PdfName.Form }
                val inner = form.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject)
                assertTrue(inner.keySet().any { inner.getAsStream(it).indirectReference.objNumber == form.indirectReference.objNumber })
            }
        }
    }

    @Test
    fun sharingCanBeSwitchedOffForOneDocument() {
        read(copy(listOf(source("a.pdf"), source("b.pdf"), source("c.pdf")), off = setOf(0))) { pdf ->
            val forms = xObjects(pdf, PdfName.Form)
            assertCount(2, forms.toSet().size)
            assertEquals(forms[1], forms[2])
        }
    }

    @Test
    fun newKeysStopAtTheLimitWhileTheKeptOnesStillMatch() {
        val sources = listOf(source("a.pdf"), source("b.pdf"))
        val limited = ArrayList<PdfSharingWriter>()
        val small = copy(sources) { file ->
            PdfSharingWriter(file.absolutePath, PdfIo.writerProperties(), direct = false, readableCopies = true, maxKeys = 1).also { limited += it }
        }
        val full = copy(sources)
        val none = copy(sources, off = setOf(0, 1))
        assertCount(1, limited.single().keys)
        assertTrue(objects(full) < objects(small))
        assertTrue(objects(small) < objects(none))
    }

    @Test
    fun aStreamAboveTheHashLimitIsNotShared() {
        val sources = listOf(source("a.pdf"), source("b.pdf"))
        read(copy(sources) { PdfSharingWriter(it.absolutePath, PdfIo.writerProperties(), direct = false, readableCopies = true, maxHashedBytes = 100) }) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Image).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Form).toSet().size)
        }
    }

    @Test
    fun aSharedObjectStaysValidAfterItsFirstDocumentWasFlushed() {
        read(copy(listOf(source("a.pdf"), source("b.pdf")), flushBetween = true) { sharing(it, readableCopies = false) }) { pdf ->
            val forms = xObjects(pdf, PdfName.Form)
            assertCount(1, forms.toSet().size)
            val form = pdf.getPage(2).resources.pdfObject.getAsDictionary(PdfName.XObject).keySet()
                .mapNotNull { pdf.getPage(2).resources.pdfObject.getAsDictionary(PdfName.XObject).getAsStream(it) }
                .first { it.getAsName(PdfName.Subtype) == PdfName.Form }
            assertEquals(CONTENT, String(form.bytes, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun aWrittenDictionaryIsCopiedAgainWhereTheCopiesAreStillRead() {
        val sources = listOf(source("a.pdf", indirectState = true), source("b.pdf", indirectState = true))
        read(copy(sources, flushBetween = true)) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun aWrittenDictionaryIsReusedWhereNothingReadsTheCopies() {
        val sources = listOf(source("a.pdf", indirectState = true), source("b.pdf", indirectState = true))
        read(copy(sources, flushBetween = true) { sharing(it, readableCopies = false) }) { pdf ->
            assertCount(1, xObjects(pdf, PdfName.Form).toSet().size)
            assertCount(1, xObjects(pdf, PdfName.Image).toSet().size)
        }
    }

    @Test
    fun arraysAreNeverSharedButTheirContentStillMatches() {
        read(copy(listOf(source("a.pdf", indirectArray = true), source("b.pdf", indirectArray = true)))) { pdf ->
            assertCount(1, xObjects(pdf, PdfName.Form).toSet().size)
        }
        read(copy(listOf(source("a.pdf", indirectArray = true), source("b.pdf", indirectArray = true, arrayNumber = "3")))) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
        }
    }

    @Test
    fun numbersWrittenDifferentlyKeepTheirOwnText() {
        read(copy(listOf(source("a.pdf", indirectArray = true), source("b.pdf", indirectArray = true, arrayNumber = "2.0")))) { pdf ->
            assertCount(2, xObjects(pdf, PdfName.Form).toSet().size)
            val written = (1..2).map { number ->
                val forms = pdf.getPage(number).resources.pdfObject.getAsDictionary(PdfName.XObject)
                val form = forms.keySet().mapNotNull { forms.getAsStream(it) }.first { it.getAsName(PdfName.Subtype) == PdfName.Form }
                form.getAsArray(PdfName("Custom")).getAsNumber(1).toString()
            }
            assertEquals(listOf("2", "2.0"), written)
        }
    }

    @Test
    fun anObjectSharedByPagesOfOneFileIsWrittenOnceAcrossBatches() {
        val file = File(temp.root, "shared.pdf")
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val image = PdfImageXObject(ImageDataFactory.create(40, 100, 1, 8, ByteArray(40 * 100) { (it % 251).toByte() }, null))
            repeat(2) { PdfCanvas(pdf.addNewPage(PageSize.A6)).addXObjectFittedIntoRectangle(image, Rectangle(10f, 10f, 40f, 100f)) }
        }
        val reused = batches(file) { PdfIo.sameSourceWriter(it.absolutePath) }
        val plain = batches(file) { PdfWriter(it.absolutePath, PdfIo.writerProperties()) }
        read(reused) { pdf -> assertCount(1, xObjects(pdf, PdfName.Image).toSet().size) }
        read(plain) { pdf -> assertCount(2, xObjects(pdf, PdfName.Image).toSet().size) }
    }

    @Test
    fun anObjectHoldingAPageJumpIsCopiedAgainForEachBatch() {
        val file = File(temp.root, "jump.pdf")
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val first = pdf.addNewPage(PageSize.A6)
            pdf.addNewPage(PageSize.A6)
            val jump = PdfDictionary().apply {
                put(PdfName.S, PdfName.GoTo)
                put(PdfName.D, PdfArray(listOf(first.pdfObject, PdfName.Fit)))
            }
            val actions = PdfDictionary().apply { put(PdfName.O, jump) }.makeIndirect(pdf)
            for (number in 1..2) pdf.getPage(number).pdfObject.put(PdfName.AA, actions)
        }
        val reused = batches(file) { PdfIo.sameSourceWriter(it.absolutePath) }
        read(reused) { pdf ->
            val actions = (1..2).map { pdf.getPage(it).pdfObject.getAsDictionary(PdfName.AA).indirectReference }
            assertCount(2, actions.toSet().size)
        }
    }

    @Test
    fun linksStillLeadToThePagesOfTheirOwnDocument() {
        read(copy(listOf(source("a.pdf", linked = true), source("b.pdf", linked = true)))) { pdf ->
            assertCount(4, pdf.numberOfPages)
            for (first in listOf(1, 3)) {
                val action = pdf.getPage(first).pdfObject.getAsArray(PdfName.Annots).getAsDictionary(0).getAsDictionary(PdfName.A)
                val target = action.getAsArray(PdfName.D).getAsDictionary(0).indirectReference.objNumber
                assertCount(pdf.getPage(first + 1).pdfObject.indirectReference.objNumber, target)
            }
        }
    }

    private fun source(
        name: String,
        content: String = CONTENT,
        tagged: Boolean = false,
        annotated: Boolean = false,
        selfCalling: Boolean = false,
        linked: Boolean = false,
        indirectState: Boolean = false,
        indirectArray: Boolean = false,
        arrayNumber: String = "2"
    ): File {
        val file = File(temp.root, name)
        PdfDocument(PdfWriter(file.absolutePath)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A6)
            val form = PdfFormXObject(Rectangle(100f, 100f))
            form.pdfObject.setData(content.toByteArray(Charsets.ISO_8859_1))
            form.resources.addExtGState(PdfExtGState().setFillOpacity(0.5f).also { if (indirectState) it.makeIndirect(pdf) })
            if (indirectArray) form.pdfObject.put(PdfName("Custom"), PdfArray(listOf(PdfNumber(1), PdfNumber(arrayNumber.toByteArray(Charsets.ISO_8859_1)))).makeIndirect(pdf))
            form.makeIndirect(pdf)
            if (tagged) PdfEditProjection.writablePrivateData(form.pdfObject).put(PdfName("Layer"), PdfBoolean.TRUE)
            if (selfCalling) form.resources.addForm(form)
            val image = PdfImageXObject(ImageDataFactory.create(40, 100, 1, 8, ByteArray(40 * 100) { (it % 251).toByte() }, null))
            PdfCanvas(page).addXObjectAt(form, 10f, 10f).addXObjectFittedIntoRectangle(image, Rectangle(120f, 10f, 40f, 100f))
            if (annotated) page.addAnnotation(PdfSquareAnnotation(Rectangle(20f, 20f, 30f, 30f)))
            if (linked) {
                val second = pdf.addNewPage(PageSize.A6)
                page.addAnnotation(
                    PdfLinkAnnotation(Rectangle(20f, 200f, 50f, 20f)).setAction(PdfAction.createGoTo(PdfExplicitDestination.createFit(second)))
                )
            }
        }
        return file
    }

    private fun copy(
        sources: List<File>,
        off: Set<Int> = emptySet(),
        flushBetween: Boolean = false,
        writer: (File) -> PdfWriter = { sharing(it) }
    ): File {
        val out = File(temp.root, "out_${System.nanoTime()}.pdf")
        PdfDocument(writer(out)).use { dest ->
            for ((index, file) in sources.withIndex()) {
                PdfDocument(PdfReader(file.absolutePath)).use { src ->
                    if (index in off) PdfIo.withoutSharing(dest) { src.copyPagesTo(1, src.numberOfPages, dest) }
                    else src.copyPagesTo(1, src.numberOfPages, dest)
                    if (flushBetween) {
                        dest.flushCopiedObjects(src)
                        for (number in 1..dest.numberOfPages) dest.getPage(number).flush()
                    }
                }
            }
        }
        return out
    }

    private fun batches(file: File, writer: (File) -> PdfWriter): File {
        val out = File(temp.root, "batches_${System.nanoTime()}.pdf")
        PdfDocument(writer(out)).use { dest ->
            for (number in 1..2) {
                PdfDocument(PdfReader(file.absolutePath)).use { src ->
                    src.copyPagesTo(number, number, dest)
                    dest.flushCopiedObjects(src)
                    dest.getPage(number).flush()
                }
            }
        }
        return out
    }

    private fun sharing(file: File, readableCopies: Boolean = true): PdfWriter =
        PdfSharingWriter(file.absolutePath, PdfIo.writerProperties(), direct = false, readableCopies = readableCopies)

    private fun xObjects(pdf: PdfDocument, subtype: PdfName): List<Int> = (1..pdf.numberOfPages).flatMap { number ->
        val xObjects = pdf.getPage(number).resources.pdfObject.getAsDictionary(PdfName.XObject) ?: return@flatMap emptyList()
        xObjects.keySet().mapNotNull { key ->
            xObjects.getAsStream(key)?.takeIf { it.getAsName(PdfName.Subtype) == subtype }?.indirectReference?.objNumber
        }
    }

    private fun objects(file: File): Int = read(file) { it.numberOfPdfObjects }

    private fun <T> read(file: File, block: (PdfDocument) -> T): T = PdfDocument(PdfReader(file.absolutePath)).use(block)

    private fun assertCount(expected: Int, actual: Int) = assertEquals(expected, actual)

    private companion object {
        const val CONTENT = "0 0 50 50 re f"
    }
}
