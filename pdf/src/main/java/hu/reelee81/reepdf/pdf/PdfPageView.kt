package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import java.io.File

class PdfPageView(val left: Float, val bottom: Float, val right: Float, val top: Float, quarterTurns: Int) {
    val quarterTurns: Int = Math.floorMod(quarterTurns, 4)

    init {
        require(left.isFinite() && bottom.isFinite() && right.isFinite() && top.isFinite()) { "The page box is not finite" }
        require(right > left && top > bottom) { "The page box is empty" }
    }

    val width: Float
        get() = if (quarterTurns % 2 == 0) right - left else top - bottom

    val height: Float
        get() = if (quarterTurns % 2 == 0) top - bottom else right - left

    fun matrix(zoom: Float): FloatArray {
        var a = 1.0
        var b = 0.0
        var c = 0.0
        var d = -1.0
        var e = -left.toDouble()
        var f = top.toDouble()
        var shownWidth = right.toDouble() - left
        var shownHeight = top.toDouble() - bottom
        repeat(quarterTurns) {
            val nextB = -a
            val nextD = -c
            val nextF = shownWidth - e
            a = b
            c = d
            e = f
            b = nextB
            d = nextD
            f = nextF
            val previousWidth = shownWidth
            shownWidth = shownHeight
            shownHeight = previousWidth
        }
        val scale = zoom.toDouble()
        return floatArrayOf(
            (a * scale).toFloat(),
            (b * scale).toFloat(),
            (c * scale).toFloat(),
            (d * scale).toFloat(),
            (e * scale).toFloat(),
            (f * scale).toFloat()
        )
    }

    companion object {
        fun of(box: Rectangle, quarterTurns: Int) = PdfPageView(box.left, box.bottom, box.right, box.top, quarterTurns)

        fun read(file: File, pageIndex: Int, quarterTurns: Int): PdfPageView =
            PdfDocument(PdfIo.reader(file.absolutePath)).use { pdf ->
                require(pageIndex in 0 until pdf.numberOfPages) { "Page ${pageIndex + 1} is outside 1..${pdf.numberOfPages}" }
                of(PdfCopySupport.visibleBox(pdf.getPage(pageIndex + 1)), quarterTurns)
            }
    }
}
