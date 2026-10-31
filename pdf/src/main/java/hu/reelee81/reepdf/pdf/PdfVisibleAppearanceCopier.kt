package hu.reelee81.reepdf.pdf

import com.itextpdf.forms.fields.PdfFormField
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.IPdfPageExtraCopier
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import java.io.File
import java.io.IOException

class PdfVisibleAppearanceCopier(
    private val cacheDir: File,
    private val onMissingAppearance: () -> Unit = {}
) : IPdfPageExtraCopier {
    override fun copy(fromPage: PdfPage, toPage: PdfPage) {
        val visible = PdfCopySupport.repairInvalidBoxes(toPage)
        PdfCopySupport.repairContents(toPage)
        PdfIdentityCMapRepair.repair(toPage)
        val optionalContent = optionalContentFor(fromPage.document)
        if (visible) optionalContent?.filterPage(fromPage, toPage)
        val annotations = fromPage.pdfObject.getAsArray(PdfName.Annots)
        if (!visible || annotations == null) {
            toPage.pdfObject.remove(PdfName.Annots)
            return
        }
        var canvas: PdfCanvas? = null
        fun contentCanvas(): PdfCanvas = canvas ?: PdfCanvas(toPage, true).also { canvas = it }

        try {
            for (index in 0 until annotations.size()) {
                val annotation = annotations.getAsDictionary(index) ?: continue
                val subtype = annotation.getAsName(PdfName.Subtype)
                if (subtype == PdfName.Popup || subtype == PdfName.Link) continue
                if (optionalContent?.isHidden(annotation.getAsDictionary(PdfName.OC)) == true) continue
                val flags = annotation.getAsNumber(PdfName.F)?.intValue() ?: 0
                if (flags and FLAG_INVISIBLE != 0 && subtype !in standardSubtypes) continue
                val printable = flags and FLAG_PRINT != 0
                val hidden = flags and FLAG_HIDDEN != 0
                if (hidden && !printable) continue
                if (subtype != PdfName.Widget && !printable) continue
                val rect = annotation.getAsArray(PdfName.Rect)?.toRectangle() ?: continue
                if (!validRectangle(rect)) continue

                val appearance = normalAppearance(annotation)
                if (appearance != null) {
                    drawAppearance(appearance, rect, contentCanvas(), toPage.document, subtype == PdfName.Widget)
                } else if (subtype == PdfName.Widget && printable && !hidden) {
                    val normal = annotation.getAsDictionary(PdfName.AP)?.getAsDictionary(PdfName.N)
                    if (normal?.get(appearanceState(annotation, normal)) is PdfName) continue
                    val fieldType = inherited(annotation, PdfName.FT)
                    val fieldFlags = (inherited(annotation, PdfName.Ff) as? PdfNumber)?.intValue() ?: 0
                    if (fieldType == PdfName.Btn && fieldFlags and FIELD_PUSHBUTTON != 0) continue
                    if (fieldType == PdfName.Tx || fieldType == PdfName.Btn || fieldType == PdfName.Ch) {
                        try {
                            drawGeneratedWidget(annotation, fromPage.document, rect, contentCanvas(), toPage.document)
                        } catch (_: Exception) {
                            onMissingAppearance()
                        }
                    }
                }
            }
        } finally {
            canvas?.release()
        }

        toPage.pdfObject.remove(PdfName.Annots)
    }

    private var optionalContentSource: PdfDocument? = null
    private var cachedOptionalContent: PdfOptionalContentFilter? = null

    private fun optionalContentFor(document: PdfDocument): PdfOptionalContentFilter? {
        if (optionalContentSource !== document) {
            optionalContentSource = document
            cachedOptionalContent = PdfOptionalContentFilter.of(document)
        }
        return cachedOptionalContent
    }

    private fun normalAppearance(annotation: PdfDictionary): PdfStream? {
        val normal = annotation.getAsDictionary(PdfName.AP)?.get(PdfName.N) ?: return null
        if (normal is PdfStream) return normal
        if (normal !is PdfDictionary) return null
        return normal.getAsStream(appearanceState(annotation, normal))
    }

    private fun appearanceState(annotation: PdfDictionary, normal: PdfDictionary?): PdfName {
        annotation.getAsName(PdfName.AS)?.let { return it }
        val value = inherited(annotation, PdfName.V) as? PdfName
        val flags = (inherited(annotation, PdfName.Ff) as? PdfNumber)?.intValue() ?: 0
        val isToggle = inherited(annotation, PdfName.FT) == PdfName.Btn && flags and (1 shl 16) == 0
        val isCheckbox = isToggle && flags and (1 shl 15) == 0
        if (value != null && (normal == null || normal.containsKey(value) || isCheckbox)) return value
        if (!isToggle && normal != null && normal.size() == 1) return normal.keySet().first()
        return offState
    }

    private fun drawAppearance(
        stream: PdfStream,
        rect: Rectangle,
        canvas: PdfCanvas,
        destination: PdfDocument,
        allowMissingBounds: Boolean = false
    ) {
        val bounds = stream.getAsArray(PdfName.BBox)?.toRectangle()
            ?: if (allowMissingBounds) Rectangle(0f, 0f, rect.width, rect.height) else return
        if (!validRectangle(bounds)) return
        val copied = stream.copyTo(destination) as PdfStream
        if (!copied.containsKey(PdfName.BBox)) copied.put(PdfName.BBox, PdfArray(bounds))
        val xObject = PdfFormXObject(copied)
        val matrix = FloatArray(6)
        PdfFormXObject.calcAppearanceTransformToAnnotRect(xObject, rect).getMatrix(matrix)
        if (matrix.any { !it.isFinite() }) return
        canvas.saveState()
            .addXObjectWithTransformationMatrix(xObject, matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5])
            .restoreState()
    }

    private fun drawGeneratedWidget(
        annotation: PdfDictionary,
        source: PdfDocument,
        rect: Rectangle,
        canvas: PdfCanvas,
        destination: PdfDocument
    ) {
        val file = File.createTempFile("pdf_widget_appearance_", ".pdf", cacheDir)
        try {
            PdfDocument(PdfWriter(file.absolutePath)).use { scratch ->
                val page = scratch.addNewPage()
                val sourceForm = source.catalog.pdfObject.getAsDictionary(PdfName.AcroForm)
                val defaults = PdfDictionary()
                for (key in arrayOf(PdfName.DA, PdfName.Q)) {
                    sourceForm?.get(key)?.let { defaults.put(key, it.copyTo(scratch)) }
                }
                copyDefaultFonts(annotation, sourceForm, defaults, scratch)
                defaults.put(PdfName.Fields, PdfArray())
                scratch.catalog.pdfObject.put(PdfName.AcroForm, defaults)

                val widget = annotation.copyTo(
                    scratch, listOf(PdfName.Parent, PdfName.Kids, PdfName.P, PdfName.AA), true
                )
                for (key in inheritedFieldKeys) {
                    inherited(annotation, key)?.let { widget.put(key, it.copyTo(scratch)) }
                }
                if (!widget.containsKey(PdfName.DA)) defaults.get(PdfName.DA)?.let { widget.put(PdfName.DA, it) }
                if (!widget.containsKey(PdfName.Q)) defaults.get(PdfName.Q)?.let { widget.put(PdfName.Q, it) }
                widget.put(PdfName.T, PdfString("appearance"))
                widget.put(PdfName.P, page.pdfObject)
                val flags = widget.getAsNumber(PdfName.Ff)?.intValue() ?: 0
                if (widget.getAsName(PdfName.FT) == PdfName.Btn && flags and (1 shl 15) != 0) {
                    val state = appearanceState(annotation, annotation.getAsDictionary(PdfName.AP)?.getAsDictionary(PdfName.N))
                    if (state != offState) {
                        val normal = widget.getAsDictionary(PdfName.AP)?.getAsDictionary(PdfName.N) ?: PdfDictionary()
                        if (!normal.containsKey(state)) normal.put(state, PdfFormXObject(Rectangle(0f, 0f)).pdfObject)
                        widget.put(PdfName.AP, PdfDictionary().apply { put(PdfName.N, normal) })
                    }
                }
                widget.makeIndirect(scratch)
                val field = PdfFormField.makeFormField(widget, scratch)
                    ?: throw IOException("Cannot generate PDF form field appearance")
                if (!field.regenerateField()) throw IOException("Cannot regenerate PDF form field appearance")
                val regenerated = field.widgets.singleOrNull()
                    ?: throw IOException("Missing regenerated PDF form widget")
                page.addAnnotation(regenerated)
                defaults.getAsArray(PdfName.Fields).add(field.pdfObject)
            }
            PdfDocument(PdfReader(file.absolutePath).setMemorySavingMode(true)).use { scratch ->
                val widget = scratch.getPage(1).pdfObject.getAsArray(PdfName.Annots).getAsDictionary(0)
                val normal = widget.getAsDictionary(PdfName.AP)?.get(PdfName.N)
                val stream = (normal as? PdfStream)
                    ?: (normal as? PdfDictionary)?.getAsStream(
                        appearanceState(widget, normal)
                    )
                    ?: throw IOException("Missing regenerated PDF form field appearance")
                drawAppearance(stream, rect, canvas, destination)
                destination.flushCopiedObjects(scratch)
            }
        } finally {
            file.delete()
        }
    }

    private fun copyDefaultFonts(
        annotation: PdfDictionary,
        sourceForm: PdfDictionary?,
        defaults: PdfDictionary,
        scratch: PdfDocument
    ) {
        val appearance = inherited(annotation, PdfName.DA) as? PdfString
            ?: sourceForm?.getAsString(PdfName.DA) ?: return
        val sourceFonts = sourceForm?.getAsDictionary(PdfName.DR)?.getAsDictionary(PdfName.Font) ?: return
        val fonts = PdfDictionary()
        val bytes = PdfEncodings.convertToBytes(appearance.value, null)
        PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes))).use { tokenizer ->
            val parser = PdfCanvasParser(tokenizer)
            val operands = ArrayList<PdfObject>()
            while (parser.parse(operands).isNotEmpty()) {
                if (operands.size >= 3 && operands.last().toString() == "Tf") {
                    val name = operands[operands.size - 3] as? PdfName ?: continue
                    sourceFonts.get(name)?.let { fonts.put(name, it.copyTo(scratch)) }
                }
            }
        }
        if (fonts.size() > 0) defaults.put(PdfName.DR, PdfDictionary().apply { put(PdfName.Font, fonts) })
    }

    private fun inherited(annotation: PdfDictionary, key: PdfName): PdfObject? {
        var current: PdfDictionary? = annotation
        val visited = HashSet<PdfDictionary>()
        while (current != null && visited.add(current)) {
            current.get(key)?.let { return it }
            current = current.getAsDictionary(PdfName.Parent)
        }
        return null
    }

    private fun validRectangle(rect: Rectangle): Boolean =
        rect.x.isFinite() && rect.y.isFinite() && rect.width.isFinite() && rect.height.isFinite() &&
            rect.width > 0f && rect.height > 0f

    private companion object {
        const val FLAG_INVISIBLE = 1
        const val FLAG_HIDDEN = 2
        const val FLAG_PRINT = 4
        const val FIELD_PUSHBUTTON = 1 shl 16
        val offState = PdfName("Off")
        val standardSubtypes = setOf(
            "Text", "Link", "FreeText", "Line", "Square", "Circle", "Polygon", "PolyLine", "Highlight",
            "Underline", "Squiggly", "StrikeOut", "Caret", "Stamp", "Ink", "Popup", "FileAttachment",
            "Sound", "Movie", "Screen", "Widget", "PrinterMark", "TrapNet", "Watermark", "3D", "Redact",
            "Projection", "RichMedia"
        ).map(::PdfName).toSet()
        val inheritedFieldKeys = arrayOf(
            PdfName.FT, PdfName.Ff, PdfName.V, PdfName.DV, PdfName.DA, PdfName.Q,
            PdfName.MaxLen, PdfName.Opt, PdfName.I, PdfName.TI, PdfName.DS, PdfName.RV
        )
    }
}
