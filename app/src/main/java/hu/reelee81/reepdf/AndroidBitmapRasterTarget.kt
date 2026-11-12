package hu.reelee81.reepdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.NativeDevice
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import hu.reelee81.reepdf.pdf.PdfRasterTarget

class AndroidBitmapRasterTarget(val bitmap: Bitmap) : PdfRasterTarget {
    init {
        require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) { "The bitmap must be a mutable ARGB_8888 bitmap" }
    }

    override fun whiteDevice(left: Int, top: Int): NativeDevice = AndroidDrawDevice(bitmap, left, top, true)
}
