package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Quad

internal object PdfTextQuads {
    fun put(rects: FloatArray, index: Int, quad: Quad, left: Float, top: Float) {
        val offset = index * PdfTextHits.RECT_SIZE
        rects[offset] = minX(quad) - left
        rects[offset + 1] = minY(quad) - top
        rects[offset + 2] = maxX(quad) - left
        rects[offset + 3] = maxY(quad) - top
    }

    private fun minX(quad: Quad) = minOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)

    private fun minY(quad: Quad) = minOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)

    private fun maxX(quad: Quad) = maxOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)

    private fun maxY(quad: Quad) = maxOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
}
