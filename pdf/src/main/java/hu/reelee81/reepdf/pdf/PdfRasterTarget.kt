package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.NativeDevice

fun interface PdfRasterTarget {
    fun whiteDevice(left: Int, top: Int): NativeDevice
}
