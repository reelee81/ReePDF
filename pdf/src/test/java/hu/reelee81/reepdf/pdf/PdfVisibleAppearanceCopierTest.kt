package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.annot.PdfAnnotation
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfVisibleAppearanceCopierTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun normalPageContentBoxesAndRotationAreUnchanged() {
        val original = makePdf { pdf ->
            pdf.getPage(1).setCropBox(Rectangle(10f, 20f, 300f, 400f)).setRotation(90)
        }
        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { source ->
            PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { result ->
                assertArrayEquals(source.getPage(1).contentBytes, result.getPage(1).contentBytes)
                assertEquals(source.getPage(1).cropBox.toString(), result.getPage(1).cropBox.toString())
                assertEquals(90, result.getPage(1).rotation)
            }
        }
    }

    @Test
    fun existingFormAndSignatureAppearancesSurviveXObjectCopyWithoutRasterization() {
        val original = makePdf { pdf ->
            addWidget(pdf, PdfName.Tx, "FORM VALUE")
            addWidget(pdf, PdfName.Sig, "SIGNATURE BADGE", 100f)
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original, asXObject = true)))).use { result ->
            val text = PdfTextExtractor.getTextFromPage(result.getPage(1))
            assertTrue(text.contains("FORM VALUE"))
            assertTrue(text.contains("SIGNATURE BADGE"))
            assertTrue(result.getPage(1).annotations.isEmpty())
            assertFalse(result.catalog.pdfObject.containsKey(PdfName.AcroForm))
            assertOnlyFormXObjects(result.getPage(1).resources.pdfObject)
        }
    }

    @Test
    fun inheritedFormValueAndDocumentDefaultFontAreRegeneratedOnlyForMissingAppearance() {
        val original = makePdf { pdf ->
            val font = pdf.addFont(PdfFontFactory.createFont())
            val fonts = PdfDictionary().apply { put(PdfName("Helv"), font.pdfObject) }
            val form = PdfDictionary().apply {
                put(PdfName.Fields, PdfArray())
                put(PdfName.DA, PdfString("/Helv 12 Tf 0 g"))
                put(PdfName.DR, PdfDictionary().apply { put(PdfName.Font, fonts) })
            }
            pdf.catalog.pdfObject.put(PdfName.AcroForm, form)
            val parent = PdfDictionary().apply {
                put(PdfName.FT, PdfName.Tx)
                put(PdfName.V, PdfString("INHERITED VALUE"))
                put(PdfName.T, PdfString("parent"))
                put(PdfName.Kids, PdfArray())
                makeIndirect(pdf)
            }
            val widget = baseWidget(40f).apply {
                put(PdfName.Parent, parent)
                put(PdfName.F, PdfNumber(FLAG_PRINT))
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
            parent.getAsArray(PdfName.Kids).add(widget)
            form.getAsArray(PdfName.Fields).add(parent)
        }
        val result = import(original, asXObject = true)
        PdfDocument(PdfReader(ByteArrayInputStream(result))).use { pdf ->
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("INHERITED VALUE"))
            assertOnlyFormXObjects(pdf.getPage(1).resources.pdfObject)
        }
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("pdf_widget_appearance_") })
    }

    @Test
    fun checkboxOffDoesNotUseAnAvailableOnAppearance() {
        val original = makePdf { pdf ->
            val widget = baseWidget(40f).apply {
                put(PdfName.F, PdfNumber(FLAG_PRINT))
                put(PdfName.FT, PdfName.Btn)
                put(PdfName.V, PdfName("Off"))
                put(PdfName.AS, PdfName("Off"))
                put(PdfName.T, PdfString("checkbox"))
                put(PdfName.AP, PdfDictionary().apply {
                    put(PdfName.N, PdfDictionary().apply { put(PdfName("Yes"), appearance(pdf, "MUST NOT APPEAR")) })
                })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertFalse(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("MUST NOT APPEAR"))
        }
    }

    @Test
    fun explicitEmptyAppearanceIsNotRegenerated() {
        val original = makePdf { pdf ->
            val widget = baseWidget(40f).apply {
                put(PdfName.FT, PdfName.Tx)
                put(PdfName.V, PdfString("INTENTIONALLY INVISIBLE"))
                put(PdfName.AP, PdfDictionary().apply {
                    put(PdfName.N, PdfFormXObject(Rectangle(0f, 0f)).pdfObject)
                })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertFalse(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("INTENTIONALLY INVISIBLE"))
        }
    }

    @Test
    fun needAppearancesDoesNotReplaceAnExistingFormattedAppearance() {
        val original = makePdf { pdf ->
            addWidget(pdf, PdfName.Tx, "FORMATTED VALUE")
            pdf.getPage(1).annotations.single().pdfObject.put(PdfName.V, PdfString("RAW VALUE"))
            pdf.catalog.pdfObject.put(PdfName.AcroForm, PdfDictionary().apply {
                put(PdfName.NeedAppearances, PdfBoolean.TRUE)
            })
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("FORMATTED VALUE"))
            assertFalse(text.contains("RAW VALUE"))
        }
    }

    @Test
    fun explicitlyNamedEmptyButtonStateIsNotRegenerated() {
        val original = makePdf { pdf ->
            val widget = baseWidget(40f).apply {
                put(PdfName.FT, PdfName.Btn)
                put(PdfName.AS, PdfName("Off"))
                put(PdfName.AP, PdfDictionary().apply {
                    put(PdfName.N, PdfDictionary().apply { put(PdfName("Off"), PdfName("Off")) })
                })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertFalse(pdf.getPage(1).resources.pdfObject.containsKey(PdfName.XObject))
        }
    }

    @Test
    fun widgetAppearanceWithMissingBoxUsesWidgetDimensionsWithoutChangingTheSource() {
        val original = makePdf { pdf ->
            val stream = appearance(pdf, "MISSING BOX")
            stream.remove(PdfName.BBox)
            val widget = baseWidget(40f).apply {
                put(PdfName.FT, PdfName.Tx)
                put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, stream) })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("MISSING BOX"))
            assertOnlyFormXObjects(pdf.getPage(1).resources.pdfObject)
        }
        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { pdf ->
            val stream = pdf.getPage(1).annotations.single().pdfObject.getAsDictionary(PdfName.AP).getAsStream(PdfName.N)
            assertFalse(stream.containsKey(PdfName.BBox))
        }
    }

    @Test
    fun popupsAndHiddenWithoutPrintAreNotPainted() {
        val original = makePdf { pdf ->
            for ((subtype, flags) in listOf(PdfName.Popup to FLAG_PRINT, PdfName.Widget to FLAG_HIDDEN, PdfName.Text to FLAG_HIDDEN)) {
                val annotation = baseWidget(40f).apply {
                    put(PdfName.Subtype, subtype)
                    put(PdfName.F, PdfNumber(flags))
                    put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, "HIDDEN")) })
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(annotation))
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertEquals("BODY", PdfTextExtractor.getTextFromPage(pdf.getPage(1)).trim())
            assertTrue(pdf.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun invisibleFlagHidesOnlyNonStandardAnnotations() {
        val original = makePdf { pdf ->
            for ((index, entry) in listOf(
                PdfName.Widget to "INVISIBLE SIGNATURE",
                PdfName.Stamp to "INVISIBLE STAMP",
                PdfName("CustomMark") to "CUSTOM MARK"
            ).withIndex()) {
                val annotation = baseWidget(40f + index * 40f).apply {
                    put(PdfName.Subtype, entry.first)
                    put(PdfName.F, PdfNumber(FLAG_INVISIBLE or FLAG_PRINT or FLAG_LOCKED))
                    put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, entry.second)) })
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(annotation))
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("INVISIBLE SIGNATURE"))
            assertTrue(text.contains("INVISIBLE STAMP"))
            assertFalse(text.contains("CUSTOM MARK"))
            assertTrue(pdf.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun pushbuttonWithoutAppearanceIsNotGenerated() {
        val original = makePdf { pdf ->
            val font = pdf.addFont(PdfFontFactory.createFont())
            pdf.catalog.pdfObject.put(PdfName.AcroForm, PdfDictionary().apply {
                put(PdfName.Fields, PdfArray())
                put(PdfName.DA, PdfString("/Helv 12 Tf 0 g"))
                put(PdfName.DR, PdfDictionary().apply {
                    put(PdfName.Font, PdfDictionary().apply { put(PdfName("Helv"), font.pdfObject) })
                })
            })
            val button = baseWidget(40f).apply {
                put(PdfName.F, PdfNumber(FLAG_PRINT))
                put(PdfName.FT, PdfName.Btn)
                put(PdfName.Ff, PdfNumber(1 shl 16))
                put(PdfName.T, PdfString("reset"))
                put(PdfName.MK, PdfDictionary().apply { put(PdfName.CA, PdfString("PUSH CAPTION")) })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(button))
            pdf.catalog.pdfObject.getAsDictionary(PdfName.AcroForm).getAsArray(PdfName.Fields).add(button)
        }
        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { source ->
            PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { result ->
                assertArrayEquals(source.getPage(1).contentBytes, result.getPage(1).contentBytes)
                assertTrue(result.getPage(1).annotations.isEmpty())
            }
        }
    }

    @Test
    fun annotationsWithoutPrintAreNotPaintedButFieldsWithoutPrintAre() {
        val original = makePdf { pdf ->
            for ((index, entry) in listOf(
                Triple(PdfName.Ink, 0, "NOPRINT INK"),
                Triple(PdfName.Stamp, FLAG_NO_VIEW, "NOPRINT STAMP"),
                Triple(PdfName.Widget, 0, "NOPRINT FIELD")
            ).withIndex()) {
                val annotation = baseWidget(40f + index * 40f).apply {
                    put(PdfName.Subtype, entry.first)
                    if (entry.second != 0) put(PdfName.F, PdfNumber(entry.second))
                    put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, entry.third)) })
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(annotation))
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertFalse(text.contains("NOPRINT INK"))
            assertFalse(text.contains("NOPRINT STAMP"))
            assertTrue(text.contains("NOPRINT FIELD"))
            assertTrue(pdf.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun existingAppearanceIsPaintedForNoViewHiddenPrintAndTextAnnotations() {
        val original = makePdf { pdf ->
            for ((subtype, flags, text) in listOf(
                Triple(PdfName.Widget, FLAG_NO_VIEW, "NOVIEW FIELD"),
                Triple(PdfName.Ink, FLAG_HIDDEN or FLAG_PRINT, "HIDDEN PRINT INK"),
                Triple(PdfName.Text, FLAG_PRINT, "NOTE ICON")
            )) {
                val annotation = baseWidget(40f + flags * 10f).apply {
                    put(PdfName.Subtype, subtype)
                    put(PdfName.F, PdfNumber(flags))
                    put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, text)) })
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(annotation))
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("NOVIEW FIELD"))
            assertTrue(text.contains("HIDDEN PRINT INK"))
            assertTrue(text.contains("NOTE ICON"))
            assertTrue(pdf.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun printableMarkupWithoutAppearanceIsDrawnFromTheGeneratedAppearance() {
        val original = markupPdf(withNonPrintable = true)
        val seenKeys = ArrayList<Set<String>>()
        var missing = 0
        val copier = PdfVisibleAppearanceCopier(temporary.root, { missing++ }, drawingGenerator(seenKeys = seenKeys))

        PdfDocument(PdfReader(ByteArrayInputStream(import(original, copier = copier)))).use { result ->
            val text = PdfTextExtractor.getTextFromPage(result.getPage(1))
            for (subtype in markupSubtypes) assertTrue(text.contains("GENERATED ${subtype.value}"))
            assertTrue(result.getPage(1).annotations.isEmpty())
        }
        assertEquals(0, missing)
        assertEquals(markupSubtypes.size, seenKeys.size)
        for (keys in seenKeys) {
            assertTrue("P" in keys && "QuadPoints" in keys && "C" in keys)
            assertFalse("A" in keys || "Popup" in keys || "AP" in keys)
        }
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("pdf_annotation_appearance_") })
    }

    @Test
    fun markupWhoseAppearanceCannotBeGeneratedIsReportedAndLeavesThePageAlone() {
        val original = markupPdf(withNonPrintable = true)
        var missing = 0
        val copier = PdfVisibleAppearanceCopier(temporary.root, { missing++ }, noMuPdf)

        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { source ->
            PdfDocument(PdfReader(ByteArrayInputStream(import(original, copier = copier)))).use { result ->
                assertArrayEquals(source.getPage(1).contentBytes, result.getPage(1).contentBytes)
                assertTrue(result.getPage(1).annotations.isEmpty())
            }
        }
        assertEquals(markupSubtypes.size, missing)
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("pdf_annotation_appearance_") })
    }

    @Test
    fun onlyTheMarkupLeftWithoutAGeneratedAppearanceIsReported() {
        val original = markupPdf(withNonPrintable = false)
        var missing = 0
        val copier = PdfVisibleAppearanceCopier(temporary.root, { missing++ }, drawingGenerator(skip = setOf("Squiggly")))

        PdfDocument(PdfReader(ByteArrayInputStream(import(original, copier = copier)))).use { result ->
            val text = PdfTextExtractor.getTextFromPage(result.getPage(1))
            assertTrue(text.contains("GENERATED Highlight"))
            assertTrue(text.contains("GENERATED Underline"))
            assertTrue(text.contains("GENERATED StrikeOut"))
            assertFalse(text.contains("GENERATED Squiggly"))
        }
        assertEquals(1, missing)
    }

    private val markupSubtypes = listOf(PdfName.Highlight, PdfName.Underline, PdfName.Squiggly, PdfName.StrikeOut)

    private fun markupPdf(withNonPrintable: Boolean): ByteArray = makePdf { pdf ->
        val flags = if (withNonPrintable) listOf(FLAG_PRINT, 0) else listOf(FLAG_PRINT)
        for (flag in flags) {
            for ((index, subtype) in markupSubtypes.withIndex()) {
                val y = 40f + index * 40f + if (flag == 0) 200f else 0f
                val annotation = baseWidget(y).apply {
                    put(PdfName.Subtype, subtype)
                    put(PdfName.F, PdfNumber(flag))
                    put(PdfName.C, PdfArray(floatArrayOf(1f, 1f, 0f)))
                    put(PdfName.QuadPoints, PdfArray(floatArrayOf(30f, y + 30f, 230f, y + 30f, 30f, y, 230f, y)))
                    put(PdfName.A, PdfDictionary().apply {
                        put(PdfName.S, PdfName.GoTo)
                        put(PdfName.D, PdfArray(listOf(pdf.getPage(1).pdfObject, PdfName.Fit)))
                    })
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(annotation))
            }
        }
    }

    private fun drawingGenerator(
        skip: Set<String> = emptySet(),
        seenKeys: MutableList<Set<String>>? = null
    ): (File, File) -> Unit = { input, output ->
        PdfDocument(PdfReader(input.absolutePath), PdfWriter(output.absolutePath)).use { pdf ->
            val annotations = pdf.getPage(1).pdfObject.getAsArray(PdfName.Annots)
            for (index in 0 until annotations.size()) {
                val annotation = annotations.getAsDictionary(index)
                seenKeys?.add(annotation.keySet().map { it.value }.toSet())
                val subtype = annotation.getAsName(PdfName.Subtype).value
                if (subtype in skip) continue
                annotation.put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, "GENERATED $subtype")) })
            }
        }
    }

    private val noMuPdf: (File, File) -> Unit = { _, _ -> throw IOException("MuPDF is not available in JVM tests") }

    @Test
    fun missingFieldAppearanceIsGeneratedOnlyWhenPrintableAndNotHidden() {
        val original = makePdf { pdf ->
            val font = pdf.addFont(PdfFontFactory.createFont())
            pdf.catalog.pdfObject.put(PdfName.AcroForm, PdfDictionary().apply {
                put(PdfName.Fields, PdfArray())
                put(PdfName.DA, PdfString("/Helv 12 Tf 0 g"))
                put(PdfName.DR, PdfDictionary().apply {
                    put(PdfName.Font, PdfDictionary().apply { put(PdfName("Helv"), font.pdfObject) })
                })
            })
            for ((index, pair) in listOf(
                0 to "NOPRINT VALUE",
                (FLAG_HIDDEN or FLAG_PRINT) to "HIDDEN VALUE",
                FLAG_PRINT to "PRINTED VALUE"
            ).withIndex()) {
                val widget = baseWidget(40f + index * 40f).apply {
                    put(PdfName.F, PdfNumber(pair.first))
                    put(PdfName.FT, PdfName.Tx)
                    put(PdfName.T, PdfString("field$index"))
                    put(PdfName.V, PdfString(pair.second))
                }
                pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
                pdf.catalog.pdfObject.getAsDictionary(PdfName.AcroForm).getAsArray(PdfName.Fields).add(widget)
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("PRINTED VALUE"))
            assertFalse(text.contains("NOPRINT VALUE"))
            assertFalse(text.contains("HIDDEN VALUE"))
        }
    }

    @Test
    fun failedFieldGenerationSkipsOnlyThatFieldNotTheImport() {
        val original = makePdf { pdf ->
            val broken = baseWidget(40f).apply {
                put(PdfName.F, PdfNumber(FLAG_PRINT))
                put(PdfName.FT, PdfName.Ch)
                put(PdfName.Ff, PdfNumber(1 shl 17))
                put(PdfName.T, PdfString("combo"))
                put(PdfName.V, PdfString("NO OPTIONS"))
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(broken))
            addWidget(pdf, PdfName.Tx, "STILL VISIBLE", 120f)
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("BODY"))
            assertTrue(text.contains("STILL VISIBLE"))
            assertTrue(pdf.getPage(1).annotations.isEmpty())
        }
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("pdf_widget_appearance_") })
    }

    @Test
    fun cropBoxOutsideMediaBoxGivesABlankPageWithoutAnnotations() {
        val original = makePdf { pdf ->
            pdf.getPage(1).setCropBox(Rectangle(700f, 900f, 100f, 100f))
            addWidget(pdf, PdfName.Tx, "OUTSIDE")
        }
        PdfDocument(PdfReader(ByteArrayInputStream(original))).use { source ->
            PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { result ->
                val page = result.getPage(1)
                assertEquals(source.getPage(1).pageSize.toString(), page.pageSize.toString())
                assertEquals(page.pageSize.toString(), PdfCopySupport.visibleBox(page).toString())
                assertEquals("", PdfTextExtractor.getTextFromPage(page).trim())
                assertTrue(page.annotations.isEmpty())
            }
        }
    }

    @Test
    fun importingStaticWorkingCopyDoesNotAddAnotherAppearanceLayer() {
        val original = makePdf { pdf -> addWidget(pdf, PdfName.Tx, "ONLY ONCE") }
        val first = import(original)
        val second = import(first)
        PdfDocument(PdfReader(ByteArrayInputStream(first))).use { a ->
            PdfDocument(PdfReader(ByteArrayInputStream(second))).use { b ->
                assertArrayEquals(a.getPage(1).contentBytes, b.getPage(1).contentBytes)
            }
        }
    }

    @Test
    fun radioOnStateIsGeneratedWhenItHasNoAppearance() {
        val original = makePdf { pdf ->
            val widget = baseWidget(40f).apply {
                put(PdfName.F, PdfNumber(FLAG_PRINT))
                put(PdfName.FT, PdfName.Btn)
                put(PdfName.Ff, PdfNumber(1 shl 15))
                put(PdfName.V, PdfName("Selected"))
                put(PdfName.AS, PdfName("Selected"))
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            assertNotNull(pdf.getPage(1).resources.pdfObject.getAsDictionary(PdfName.XObject))
        }
    }

    @Test
    fun appearanceMatrixAndOffsetBoundsKeepNativeCoordinatesOnRotatedPages() {
        val original = makePdf { pdf ->
            pdf.getPage(1).setRotation(270).setCropBox(Rectangle(10f, 20f, 400f, 500f))
            val form = PdfFormXObject(Rectangle(10f, 20f, 200f, 30f))
            form.put(PdfName.Matrix, PdfArray(floatArrayOf(1f, 0f, 0f, 1f, 7f, 11f)))
            PdfCanvas(form, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 10f)
                .moveText(13.0, 30.0).showText("POSITION").endText()
            val widget = baseWidget(40f).apply {
                put(PdfName.FT, PdfName.Tx)
                put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, form.pdfObject) })
            }
            pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
        }
        PdfDocument(PdfReader(ByteArrayInputStream(import(original)))).use { pdf ->
            var found = false
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo && data.text == "POSITION") {
                        val position = data.baseline.startPoint
                        assertEquals(33f, position.get(0), 0.001f)
                        assertEquals(50f, position.get(1), 0.001f)
                        found = true
                    }
                }
                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(1))
            assertTrue(found)
            assertEquals(270, pdf.getPage(1).rotation)
        }
    }

    @Test
    fun stressMixedPagesKeepTheExistingReopenAndFlushPattern() {
        val count = System.getProperty("reepdf.stressPages")?.toIntOrNull() ?: 0
        assumeTrue("Enabled explicitly for bounded-heap stress verification", count > 0)
        val sourceFile = temporary.newFile("many-pages.pdf")
        PdfDocument(PdfWriter(sourceFile.absolutePath)).use { pdf ->
            val font = pdf.addFont(PdfFontFactory.createFont())
            val shared = appearance(pdf, "VISIBLE BADGE")
            val defaults = PdfDictionary().apply {
                put(PdfName.Fields, PdfArray())
                put(PdfName.DA, PdfString("/Helv 12 Tf 0 g"))
                put(PdfName.DR, PdfDictionary().apply {
                    put(PdfName.Font, PdfDictionary().apply { put(PdfName("Helv"), font.pdfObject) })
                })
            }
            pdf.catalog.pdfObject.put(PdfName.AcroForm, defaults)
            for (index in 1..count) {
                val page = pdf.addNewPage()
                PdfCanvas(page).beginText().setFontAndSize(font, 12f)
                    .moveText(20.0, 700.0).showText("PAGE $index").endText()
                when (index % 10) {
                    0 -> {
                        val widget = baseWidget(40f).apply {
                            put(PdfName.F, PdfNumber(FLAG_PRINT))
                            put(PdfName.FT, PdfName.Tx)
                            put(PdfName.T, PdfString("value$index"))
                            put(PdfName.V, PdfString("VALUE $index"))
                        }
                        page.addAnnotation(PdfAnnotation.makeAnnotation(widget))
                        defaults.getAsArray(PdfName.Fields).add(widget)
                    }
                    1 -> {
                        val widget = baseWidget(40f).apply {
                            put(PdfName.FT, PdfName.Sig)
                            put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, shared) })
                        }
                        page.addAnnotation(PdfAnnotation.makeAnnotation(widget))
                    }
                    2 -> {
                        val markup = baseWidget(40f).apply { put(PdfName.Subtype, PdfName.Highlight) }
                        page.addAnnotation(PdfAnnotation.makeAnnotation(markup))
                    }
                }
                page.flush()
            }
        }
        val destinationFile = temporary.newFile("imported.pdf")
        var peakHeap = 0L
        PdfDocument(PdfWriter(destinationFile.absolutePath)).use { destination ->
            destination.setFlushUnusedObjects(true)
            val batch = (System.getProperty("reepdf.stressBatch")?.toIntOrNull() ?: 10).coerceAtLeast(1)
            for (start in 1..count step batch) {
                val end = minOf(count, start + batch - 1)
                PdfDocument(PdfReader(sourceFile.absolutePath).setMemorySavingMode(true)).use { source ->
                    source.copyPagesTo(start, end, destination, PdfVisibleAppearanceCopier(temporary.root))
                    PdfCopySupport.flushCopiedObjects(destination, source)
                }
                for (page in start..end) destination.getPage(page).flush()
                val runtime = Runtime.getRuntime()
                peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
            }
        }
        PdfDocument(PdfReader(destinationFile.absolutePath).setMemorySavingMode(true)).use { result ->
            assertEquals(count, result.numberOfPages)
            assertTrue(PdfTextExtractor.getTextFromPage(result.getPage(count)).contains("VALUE $count"))
            assertTrue(result.getPage(count).annotations.isEmpty())
        }
        val chunk = temporary.newFile("xobject-chunk.pdf")
        val batch = (System.getProperty("reepdf.stressBatch")?.toIntOrNull() ?: 10).coerceAtLeast(1)
        for (start in 1..count step batch) {
            val end = minOf(count, start + batch - 1)
            PdfDocument(PdfReader(destinationFile.absolutePath).setMemorySavingMode(true)).use { source ->
                PdfDocument(PdfWriter(chunk.absolutePath)).use { output ->
                    output.setFlushUnusedObjects(true)
                    for (index in start..end) {
                        val target = output.addNewPage()
                        val sourcePage = source.getPage(index)
                        val form = sourcePage.copyAsFormXObject(output)
                        form.bBox = PdfArray(PdfCopySupport.visibleBox(sourcePage))
                        PdfCanvas(target).addXObjectWithTransformationMatrix(form, 1f, 0f, 0f, 1f, 0f, 0f)
                        form.flush()
                        target.flush()
                    }
                }
            }
            PdfDocument(PdfReader(chunk.absolutePath).setMemorySavingMode(true)).use { result ->
                val text = PdfTextExtractor.getTextFromPage(result.lastPage)
                assertTrue(text.contains("PAGE $end"))
                if (end % 10 == 0) assertTrue(text.contains("VALUE $end"))
            }
            val runtime = Runtime.getRuntime()
            peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
        }
        println("Mixed PDF stress: pages=$count, peak sampled heap=$peakHeap, heap limit=${Runtime.getRuntime().maxMemory()}")
    }

    @Test
    fun suppliedMixedPdfsCanBeImportedWithReopenedSinglePageBatches() {
        val folder = TestPdfs.folder("Forms_and_annotations")
        val files = folder.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedBy { it.path }.toList()
        assumeTrue("The Forms_and_annotations folder contains no PDF", files.isNotEmpty())
        val outputFolder = TestPdfs.outputFolder("Appearance_review")
        val combined = ByteArrayOutputStream()
        var totalPages = 0
        PdfDocument(PdfWriter(combined)).use { destination ->
            destination.setFlushUnusedObjects(true)
            for (file in files) {
                val pageCount = PdfDocument(PdfReader(file.absolutePath)).use { it.numberOfPages }
                val perFile = ByteArrayOutputStream()
                PdfDocument(PdfWriter(perFile)).use { document ->
                    document.setFlushUnusedObjects(true)
                    for (page in 1..pageCount) {
                        PdfDocument(PdfReader(file.absolutePath).setMemorySavingMode(true)).use { source ->
                            source.copyPagesTo(page, page, destination, PdfVisibleAppearanceCopier(temporary.root, generateAppearances = noMuPdf))
                            source.copyPagesTo(page, page, document, PdfVisibleAppearanceCopier(temporary.root, generateAppearances = noMuPdf))
                            destination.flushCopiedObjects(source)
                            document.flushCopiedObjects(source)
                        }
                        destination.lastPage.flush()
                        document.lastPage.flush()
                    }
                }
                val result = import(perFile.toByteArray(), asXObject = true)
                val outputName = file.relativeTo(folder).path.replace(File.separatorChar, '_')
                Files.write(File(outputFolder, outputName).toPath(), result)
                totalPages += pageCount
            }
        }
        PdfDocument(PdfReader(ByteArrayInputStream(combined.toByteArray()))).use { result ->
            assertEquals(totalPages, result.numberOfPages)
            for (page in 1..result.numberOfPages) assertTrue(result.getPage(page).annotations.isEmpty())
        }
    }

    private fun import(
        original: ByteArray,
        asXObject: Boolean = false,
        copier: PdfVisibleAppearanceCopier = PdfVisibleAppearanceCopier(temporary.root, generateAppearances = noMuPdf)
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        PdfDocument(PdfReader(ByteArrayInputStream(original)).setMemorySavingMode(true)).use { source ->
            PdfDocument(PdfWriter(bytes)).use { destination ->
                source.copyPagesTo(1, source.numberOfPages, destination, copier)
                PdfCopySupport.flushCopiedObjects(destination, source)
            }
        }
        if (!asXObject) return bytes.toByteArray()
        val output = ByteArrayOutputStream()
        PdfDocument(PdfReader(ByteArrayInputStream(bytes.toByteArray()))).use { source ->
            PdfDocument(PdfWriter(output)).use { destination ->
                for (index in 1..source.numberOfPages) {
                    val page = source.getPage(index)
                    val target = destination.addNewPage(PageSize(page.pageSize))
                    PdfCanvas(target).addXObjectAt(page.copyAsFormXObject(destination), 0f, 0f)
                }
            }
        }
        return output.toByteArray()
    }

    private fun makePdf(block: (PdfDocument) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        PdfDocument(PdfWriter(bytes)).use { pdf ->
            val page = pdf.addNewPage()
            PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(), 12f)
                .moveText(20.0, 700.0).showText("BODY").endText()
            block(pdf)
        }
        return bytes.toByteArray()
    }

    private fun baseWidget(y: Float): PdfDictionary = PdfDictionary().apply {
        put(PdfName.Type, PdfName.Annot)
        put(PdfName.Subtype, PdfName.Widget)
        put(PdfName.Rect, PdfArray(Rectangle(30f, y, 200f, 30f)))
    }

    private fun addWidget(pdf: PdfDocument, type: PdfName, text: String, y: Float = 40f) {
        val widget = baseWidget(y).apply {
            put(PdfName.FT, type)
            put(PdfName.T, PdfString(text))
            put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, appearance(pdf, text)) })
        }
        pdf.getPage(1).addAnnotation(PdfAnnotation.makeAnnotation(widget))
    }

    private fun appearance(pdf: PdfDocument, text: String): PdfStream {
        val form = PdfFormXObject(Rectangle(0f, 0f, 200f, 30f))
        PdfCanvas(form, pdf).beginText().setFontAndSize(PdfFontFactory.createFont(), 10f)
            .moveText(3.0, 10.0).showText(text).endText()
        return form.pdfObject
    }

    private companion object {
        const val FLAG_INVISIBLE = 1
        const val FLAG_HIDDEN = 2
        const val FLAG_PRINT = 4
        const val FLAG_NO_VIEW = 32
        const val FLAG_LOCKED = 128
    }

    private fun assertOnlyFormXObjects(resources: PdfDictionary) {
        val objects = resources.getAsDictionary(PdfName.XObject)
        assertNotNull(objects)
        for (key in objects.keySet()) {
            val stream = objects.getAsStream(key)
            assertEquals(PdfName.Form, stream.getAsName(PdfName.Subtype))
            stream.getAsDictionary(PdfName.Resources)?.let {
                if (it.containsKey(PdfName.XObject)) assertOnlyFormXObjects(it)
            }
        }
    }
}
