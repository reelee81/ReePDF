package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Pixmap
import hu.reelee81.reepdf.core.SignedPageEffects
import java.io.File

object MuPdfSignatureImage {
    fun read(file: File): SignedPageEffects.Rgba {
        val image = Image(file.absolutePath)
        try {
            val decoded = image.toPixmap()
            try {
                val rgb = if (decoded.colorSpace?.isRGB == true) decoded
                else decoded.convertToColorSpace(ColorSpace.DeviceRGB, null, null, 0, true)
                try {
                    return rgba(rgb)
                } finally {
                    if (rgb !== decoded) rgb.destroy()
                }
            } finally {
                decoded.destroy()
            }
        } finally {
            image.destroy()
        }
    }

    private fun rgba(pixmap: Pixmap): SignedPageEffects.Rgba {
        val width = pixmap.width
        val height = pixmap.height
        val n = pixmap.numberOfComponents
        val hasAlpha = pixmap.alpha
        val stride = pixmap.stride
        val samples = pixmap.samples
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) {
            var s = y * stride
            var d = y * width * 4
            repeat(width) {
                out[d] = samples[s]
                out[d + 1] = samples[s + 1]
                out[d + 2] = samples[s + 2]
                out[d + 3] = if (hasAlpha) samples[s + n - 1] else 255.toByte()
                s += n
                d += 4
            }
        }
        return SignedPageEffects.Rgba(width, height, out)
    }
}
