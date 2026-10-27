package hu.reelee81.reepdf.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object SignatureGeometry {
    class PageRaster(val width: Int, val height: Int, val scale: Float, val offsetX: Float, val offsetY: Float)

    class Placement(val left: Float, val top: Float, val width: Int, val height: Int, val sampleSize: Int)

    class ImageFit(val x: Float, val y: Float, val width: Float, val height: Float)

    fun refPxForSignatureDpi(idx: Int): Float {
        return when (idx) {
            0 -> 1190f
            1 -> 2480f
            else -> 1190f
        }
    }

    fun pageWidthMm(isLandscape: Boolean): Float = if (isLandscape) 297f else 210f

    fun pageHeightMm(isLandscape: Boolean): Float = if (isLandscape) 210f else 297f

    fun maxPositionMm(pxW: Int, pxH: Int, refPx: Float, isLandscape: Boolean): Pair<Float, Float> {
        val a4Wmm = pageWidthMm(isLandscape)
        val a4Hmm = pageHeightMm(isLandscape)
        val refAxisMm = if (isLandscape) a4Hmm else a4Wmm
        val mmPerSigPx = refAxisMm / refPx
        val imgWmm = pxW * mmPerSigPx
        val imgHmm = pxH * mmPerSigPx
        return (a4Wmm - imgWmm) to (a4Hmm - imgHmm)
    }

    fun pageRaster(pageWidth: Int, pageHeight: Int, refPx: Float, isLandscape: Boolean): PageRaster {
        val (targetW, targetH) = if (!isLandscape) {
            val k = refPx / pageWidth.toFloat()
            val w = refPx.toInt()
            val h = max(1, round(pageHeight * k).toInt())
            w to h
        } else {
            val k = refPx / pageHeight.toFloat()
            val h = refPx.toInt()
            val w = max(1, round(pageWidth * k).toInt())
            w to h
        }

        val s = min(
            targetW.toFloat() / pageWidth.toFloat(),
            targetH.toFloat() / pageHeight.toFloat()
        )
        val dx = (targetW - pageWidth * s) / 2f
        val dy = (targetH - pageHeight * s) / 2f
        return PageRaster(targetW, targetH, s, dx, dy)
    }

    fun placement(
        rasterWidth: Int,
        rasterHeight: Int,
        signatureWidth: Int,
        signatureHeight: Int,
        refPx: Float,
        xMm: Float,
        yMm: Float,
        isLandscape: Boolean
    ): Placement {
        val pxPerMMX = rasterWidth.toFloat() / pageWidthMm(isLandscape)
        val pxPerMMY = rasterHeight.toFloat() / pageHeightMm(isLandscape)
        val pxX = xMm * pxPerMMX
        val pxY = yMm * pxPerMMY

        val refAxisPx = if (isLandscape) rasterHeight.toFloat() else rasterWidth.toFloat()
        val sigScale = refAxisPx / refPx

        val desiredSigW = max(1, round(signatureWidth * sigScale).toInt())
        val desiredSigH = max(1, round(signatureHeight * sigScale).toInt())

        val sample = sampleSize(signatureWidth, signatureHeight, desiredSigW, desiredSigH)
        return Placement(pxX, pxY, desiredSigW, desiredSigH, sample)
    }

    fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        var sample = 1
        val halfW = srcW / 2
        val halfH = srcH / 2
        while (halfW / sample >= dstW && halfH / sample >= dstH) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    fun fitIntoPage(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): ImageFit {
        val imgRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val pageRatio = pageWidth / pageHeight
        val (destW, destH) = if (imgRatio > pageRatio) {
            pageWidth to pageWidth / imgRatio
        } else {
            pageHeight * imgRatio to pageHeight
        }
        val offX = (pageWidth - destW) / 2f
        val offY = (pageHeight - destH) / 2f
        return ImageFit(offX, offY, destW, destH)
    }
}
