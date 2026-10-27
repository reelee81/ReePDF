package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.annot.PdfAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.layer.PdfLayer
import com.itextpdf.kernel.pdf.layer.PdfLayerMembership
import com.itextpdf.kernel.pdf.layer.PdfVisibilityExpression
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOptionalContentFilterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun contentOnHiddenLayersIsRemovedAndTheRestIsKept() {
        val original = makePdf { pdf, canvas ->
            val shown = PdfLayer("Shown", pdf)
            val hidden = PdfLayer("Hidden", pdf).apply { isOn = false }
            text(canvas, "BODY", 700f)
            canvas.beginLayer(shown)
            text(canvas, "SHOWN LAYER", 660f)
            canvas.endLayer()
            canvas.beginLayer(hidden)
            text(canvas, "HIDDEN LAYER", 620f)
            canvas.rectangle(20.0, 500.0, 100.0, 50.0).fill()
            canvas.endLayer()
            text(canvas, "AFTER", 580f)
        }
        val text = visibleText(import(original))
        assertTrue(text.contains("BODY"))
        assertTrue(text.contains("SHOWN LAYER"))
        assertTrue(text.contains("AFTER"))
        assertFalse(text.contains("HIDDEN LAYER"))
    }

    @Test
    fun hiddenFormsAndHiddenSectionsInsideFormsAreRemoved() {
        val original = makePdf { pdf, canvas ->
            val hidden = PdfLayer("Hidden", pdf).apply { isOn = false }
            val hiddenForm = PdfFormXObject(Rectangle(0f, 0f, 200f, 30f))
            text(PdfCanvas(hiddenForm, pdf), "HIDDEN FORM", 10f)
            hiddenForm.setLayer(hidden)
            val mixedForm = PdfFormXObject(Rectangle(0f, 0f, 200f, 60f))
            PdfCanvas(mixedForm, pdf).also {
                it.beginLayer(hidden)
                text(it, "NESTED HIDDEN", 40f)
                it.endLayer()
                text(it, "NESTED SHOWN", 10f)
            }
            canvas.addXObjectAt(hiddenForm, 20f, 400f)
            canvas.addXObjectAt(mixedForm, 20f, 300f)
        }
        val text = visibleText(import(original))
        assertFalse(text.contains("HIDDEN FORM"))
        assertFalse(text.contains("NESTED HIDDEN"))
        assertTrue(text.contains("NESTED SHOWN"))
    }

    @Test
    fun visibilityExpressionsAndPoliciesAreEvaluated() {
        val original = makePdf { pdf, canvas ->
            val a = PdfLayer("A", pdf)
            val c = PdfLayer("C", pdf).apply { isOn = false }
            val aAndNotC = PdfLayerMembership(pdf).apply {
                setVisibilityExpression(PdfVisibilityExpression(PdfName("And")).apply {
                    addOperand(a)
                    addOperand(PdfVisibilityExpression(PdfName("Not")).apply { addOperand(c) })
                })
            }
            val notA = PdfLayerMembership(pdf).apply {
                setVisibilityExpression(PdfVisibilityExpression(PdfName("Not")).apply { addOperand(a) })
            }
            val allOff = PdfLayerMembership(pdf).apply {
                addLayer(a)
                setVisibilityPolicy(PdfName("AllOff"))
            }
            canvas.beginLayer(aAndNotC)
            text(canvas, "A AND NOT C", 700f)
            canvas.endLayer()
            canvas.beginLayer(notA)
            text(canvas, "NOT A", 660f)
            canvas.endLayer()
            canvas.beginLayer(allOff)
            text(canvas, "ALL OFF", 620f)
            canvas.endLayer()
        }
        val result = import(original)
        val text = visibleText(result)
        assertTrue(text.contains("A AND NOT C"))
        assertFalse(text.contains("NOT A"))
        assertFalse(text.contains("ALL OFF"))
        PdfDocument(PdfReader(ByteArrayInputStream(result))).use { pdf ->
            assertTrue(String(pdf.getPage(1).contentBytes, Charsets.ISO_8859_1).contains("/VisibleContent BMC"))
        }
    }

    @Test
    fun printUsageOverridesTheViewStateWhenTheFileDefinesIt() {
        val original = makePdf { pdf, canvas ->
            val printOnly = PdfLayer("Print only", pdf).apply {
                isOn = false
                setPrint("Watermark", true)
            }
            val screenOnly = PdfLayer("Screen only", pdf).apply { setPrint("Watermark", false) }
            canvas.beginLayer(printOnly)
            text(canvas, "PRINT WATERMARK", 700f)
            canvas.endLayer()
            canvas.beginLayer(screenOnly)
            text(canvas, "SCREEN NOTE", 660f)
            canvas.endLayer()
        }
        val text = visibleText(import(original))
        assertTrue(text.contains("PRINT WATERMARK"))
        assertFalse(text.contains("SCREEN NOTE"))
    }

    @Test
    fun annotationsOnHiddenLayersAreNotPainted() {
        val original = makePdf { pdf, canvas ->
            text(canvas, "BODY", 700f)
            val hidden = PdfLayer("Hidden", pdf).apply { isOn = false }
            val appearance = PdfFormXObject(Rectangle(0f, 0f, 200f, 30f))
            text(PdfCanvas(appearance, pdf), "LAYER NOTE", 10f)
            val note = PdfDictionary().apply {
                put(PdfName.Type, PdfName.Annot)
                put(PdfName.Subtype, PdfName.Stamp)
                put(PdfName.Rect, PdfArray(Rectangle(30f, 40f, 200f, 30f)))
                put(PdfName.F, PdfNumber(4))
                put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance.pdfObject) })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(note).apply { setLayer(hidden) })
        }
        val text = visibleText(import(original))
        assertTrue(text.contains("BODY"))
        assertFalse(text.contains("LAYER NOTE"))
    }

    @Test
    fun visibleInlineImagesAreCopiedByteForByte() {
        val image = "q 20 0 0 10 100 100 cm BI /W 2 /H 1 /CS /G /BPC 8 ID ".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0xFF.toByte()) + " EI Q\n".toByteArray(Charsets.ISO_8859_1)
        val original = makePdf { pdf, canvas ->
            val hidden = PdfLayer("Hidden", pdf).apply { isOn = false }
            canvas.beginLayer(hidden)
            text(canvas, "HIDDEN LAYER", 620f)
            canvas.endLayer()
            canvas.contentStream.outputStream.writeBytes(image)
        }
        val result = import(original)
        assertFalse(visibleText(result).contains("HIDDEN LAYER"))
        PdfDocument(PdfReader(ByteArrayInputStream(result))).use { pdf ->
            assertTrue(indexOf(pdf.getPage(1).contentBytes, image) >= 0)
        }
    }

    @Test
    fun pagesWithoutHiddenContentKeepTheirContentUnchanged() {
        val original = makePdf { pdf, canvas ->
            val shown = PdfLayer("Shown", pdf)
            canvas.beginLayer(shown)
            text(canvas, "SHOWN LAYER", 660f)
            canvas.endLayer()
        }
        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { source ->
            PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { result ->
                assertArrayEquals(source.getPage(1).contentBytes, result.getPage(1).contentBytes)
            }
        }
    }

    private fun makePdf(block: (PdfDocument, PdfCanvas) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        PdfDocument(PdfWriter(bytes)).use { pdf ->
            val canvas = PdfCanvas(pdf.addNewPage())
            block(pdf, canvas)
        }
        return bytes.toByteArray()
    }

    private fun text(canvas: PdfCanvas, value: String, y: Float) {
        canvas.beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
            .moveText(20.0, y.toDouble()).showText(value).endText()
    }

    private fun import(original: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        PdfDocument(PdfReader(ByteArrayInputStream(original)).setMemorySavingMode(true)).use { source ->
            PdfDocument(PdfWriter(bytes)).use { destination ->
                source.copyPagesTo(1, source.numberOfPages, destination, PdfVisibleAppearanceCopier(temporary.root))
                PdfCopySupport.flushCopiedObjects(destination, source)
            }
        }
        return bytes.toByteArray()
    }

    private fun visibleText(pdfBytes: ByteArray): String {
        val builder = StringBuilder()
        PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo && data.textRenderMode != 3) builder.append(data.text).append('\n')
                }

                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(1))
        }
        return builder.toString()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) if (haystack[start + index] != needle[index]) continue@outer
            return start
        }
        return -1
    }
}
