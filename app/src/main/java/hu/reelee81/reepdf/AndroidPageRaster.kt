package hu.reelee81.reepdf

import android.graphics.Bitmap
import android.os.Debug
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfRenderResult
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.viewer.ViewerImage
import hu.reelee81.reepdf.viewer.ViewerRender
import hu.reelee81.reepdf.viewer.ViewerTile
import hu.reelee81.reepdf.viewer.ViewerTilesRender
import java.util.concurrent.atomic.AtomicLong

class AndroidPageRaster(private val document: PdfViewerDocument<PdfRasterTarget>, private val ownBytes: AtomicLong) {
    suspend fun render(pageIndex: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender {
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = bitmap.allocationByteCount.toLong()
        ownBytes.addAndGet(bytes)
        var millis = 0L
        val result = try {
            document.render(pageIndex, zoom, left, top, AndroidBitmapRasterTarget(bitmap)) { millis = it }
        } catch (e: Throwable) {
            discard(bitmap, bytes)
            throw e
        }
        if (result == PdfRenderResult.Done) return ViewerRender.Done(ViewerImage(bitmap.asImageBitmap(), bytes), millis)
        discard(bitmap, bytes)
        return if (result == PdfRenderResult.Aborted) ViewerRender.Aborted else ViewerRender.Failed
    }

    suspend fun renderTiles(pageIndex: Int, tiles: List<ViewerTile>): ViewerTilesRender {
        val first = tiles.first()
        if (tiles.size == 1) {
            return when (val single = render(pageIndex, first.zoom, first.left, first.top, first.width, first.height)) {
                is ViewerRender.Done -> ViewerTilesRender.Done(listOf(single.image))
                ViewerRender.Aborted -> ViewerTilesRender.Aborted
                ViewerRender.Failed -> ViewerTilesRender.Failed
            }
        }
        val left = tiles.minOf { it.left }
        val top = tiles.minOf { it.top }
        val right = tiles.maxOf { it.left + it.width }
        val bottom = tiles.maxOf { it.top + it.height }
        val area = createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val areaBytes = area.allocationByteCount.toLong()
        ownBytes.addAndGet(areaBytes)
        try {
            when (document.render(pageIndex, first.zoom, left, top, AndroidBitmapRasterTarget(area))) {
                PdfRenderResult.Done -> Unit
                PdfRenderResult.Aborted -> return ViewerTilesRender.Aborted
                is PdfRenderResult.Failed -> return ViewerTilesRender.Failed
            }
            val images = ArrayList<ViewerImage>(tiles.size)
            try {
                for ((_, _, tileLeft, tileTop, width, height) in tiles) {
                    val piece = Bitmap.createBitmap(area, tileLeft - left, tileTop - top, width, height)
                    val bytes = piece.allocationByteCount.toLong()
                    ownBytes.addAndGet(bytes)
                    images += ViewerImage(piece.asImageBitmap(), bytes)
                }
            } catch (e: Throwable) {
                images.forEach(::release)
                throw e
            }
            return ViewerTilesRender.Done(images)
        } finally {
            discard(area, areaBytes)
        }
    }

    fun release(image: ViewerImage) = discard(image.bitmap.asAndroidBitmap(), image.bytes)

    private fun discard(bitmap: Bitmap, bytes: Long) {
        bitmap.recycle()
        ownBytes.addAndGet(-bytes)
    }

    companion object {
        fun nativeBytes(ownBytes: AtomicLong): () -> Long = { Debug.getNativeHeapAllocatedSize() - ownBytes.get() }
    }
}
