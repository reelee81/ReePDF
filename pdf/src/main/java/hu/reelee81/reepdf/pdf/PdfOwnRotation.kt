package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfPage
import hu.reelee81.reepdf.core.SourcePageArrays

internal object PdfOwnRotation {
    private val OWN_ROTATE = PdfName("OwnRotate")

    fun take(page: PdfPage): Int {
        val dictionary = page.pdfObject
        val degrees = dictionary.getAsNumber(PdfName.Rotate)?.intValue()
        val quarterTurns = degrees?.let(SourcePageArrays::quarterTurnsOfPageRotate) ?: 0
        val marked = PdfEditProjection.privateData(dictionary)?.containsKey(OWN_ROTATE) == true
        if (quarterTurns == 0 && !marked) return 0
        PdfEditProjection.unsharePrivateData(dictionary)
        PdfEditProjection.privateData(dictionary)?.remove(OWN_ROTATE)
        if (quarterTurns == 0) return 0
        PdfEditProjection.writablePrivateData(dictionary).put(OWN_ROTATE, PdfNumber((4 - quarterTurns) * 90))
        dictionary.remove(PdfName.Rotate)
        return quarterTurns
    }

    fun shownSize(page: PdfPage): Pair<Float, Float> {
        val box = page.cropBox ?: page.mediaBox
        val ownRotate = PdfEditProjection.privateData(page.pdfObject)?.getAsNumber(OWN_ROTATE)?.intValue() ?: 0
        return if (ownRotate % 180 == 0) box.width to box.height else box.height to box.width
    }
}
