package hu.reelee81.reepdf.pdf

import java.io.Closeable
import java.io.File

fun interface SignedPageRenderer {
    fun open(layout: File): Pages

    interface Pages : Closeable {
        val pageCount: Int

        fun render(pageIndex: Int, signature: Signature): RgbImage?
    }

    class Signature(
        val file: File,
        val width: Int,
        val height: Int,
        val refPx: Float,
        val xMm: Float,
        val yMm: Float,
        val isLandscape: Boolean,
        val scanEffect: Boolean,
        val grayscale: Boolean
    )

    class RgbImage(val width: Int, val height: Int, val rgb: ByteArray)
}
