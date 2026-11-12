package hu.reelee81.reepdf

import android.graphics.Bitmap
import android.os.Debug
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfRenderResult
import hu.reelee81.reepdf.pdf.PdfSearchCounter
import hu.reelee81.reepdf.pdf.PdfTextPoint
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.viewer.ViewerDocument
import hu.reelee81.reepdf.viewer.ViewerImage
import hu.reelee81.reepdf.viewer.ViewerPageSizes
import hu.reelee81.reepdf.viewer.ViewerRender
import hu.reelee81.reepdf.viewer.ViewerTextHits
import hu.reelee81.reepdf.viewer.ViewerTextPoint
import hu.reelee81.reepdf.viewer.ViewerTextSpan
import hu.reelee81.reepdf.viewer.ViewerTile
import hu.reelee81.reepdf.viewer.ViewerTilesRender
import java.util.concurrent.atomic.AtomicLong

class AndroidViewerDocument private constructor(
    private val document: PdfViewerDocument<PdfRasterTarget>,
    private val counter: PdfSearchCounter<PdfRasterTarget>,
    private val ownBytes: AtomicLong
) : ViewerDocument {

    override val pageCount: Int
        get() = document.pageCount

    override suspend fun pageSizes(): ViewerPageSizes =
        document.pageSizes().let { ViewerPageSizes(it.widths, it.heights) }

    override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender {
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = bitmap.allocationByteCount.toLong()
        ownBytes.addAndGet(bytes)
        var millis = 0L
        val result = try {
            document.render(page, zoom, left, top, AndroidBitmapRasterTarget(bitmap)) { millis = it }
        } catch (e: Throwable) {
            discard(bitmap, bytes)
            throw e
        }
        if (result == PdfRenderResult.Done) return ViewerRender.Done(ViewerImage(bitmap.asImageBitmap(), bytes), millis)
        discard(bitmap, bytes)
        return if (result == PdfRenderResult.Aborted) ViewerRender.Aborted else ViewerRender.Failed
    }

    override suspend fun renderTiles(tiles: List<ViewerTile>): ViewerTilesRender {
        val first = tiles.first()
        if (tiles.size == 1) {
            return when (val single = render(first.page, first.zoom, first.left, first.top, first.width, first.height)) {
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
            when (document.render(first.page, first.zoom, left, top, AndroidBitmapRasterTarget(area))) {
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

    override suspend fun search(page: Int, query: String): ViewerTextHits =
        document.search(page, query).let { ViewerTextHits(it.rects, it.starts) }

    override suspend fun count(page: Int, query: String): Int = counter.count(page, query)

    override fun releaseCount() = counter.release()

    override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? =
        document.word(page, point.toPdf(), tolerance)?.let { ViewerTextSpan(it.from.toViewer(), it.to.toViewer(), it.rects) }

    override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint? =
        document.caret(page, point.toPdf())?.toViewer()

    override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): FloatArray =
        document.highlight(page, from?.toPdf(), to?.toPdf())

    override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): String =
        document.copy(page, from?.toPdf(), to?.toPdf())

    override suspend fun text(page: Int): String = document.text(page)

    private fun ViewerTextPoint.toPdf() = PdfTextPoint(x, y)

    private fun PdfTextPoint.toViewer() = ViewerTextPoint(x, y)

    override fun release(image: ViewerImage) = discard(image.bitmap.asAndroidBitmap(), image.bytes)

    private fun discard(bitmap: Bitmap, bytes: Long) {
        bitmap.recycle()
        ownBytes.addAndGet(-bytes)
    }

    override fun trimMemory() = document.trimMemory()

    override fun close() {
        counter.release()
        document.close()
    }

    companion object {
        suspend fun open(path: String): AndroidViewerDocument {
            val ownBytes = AtomicLong()
            val nativeBytes = { Debug.getNativeHeapAllocatedSize() - ownBytes.get() }
            val document = PdfViewerDocument.open(nativeBytes) {
                MuPdfViewerEngine.open(path)
            }
            OpenViewerDocuments.add(document)
            val counter = PdfSearchCounter({
                PdfViewerDocument.open(nativeBytes) {
                    MuPdfViewerEngine.open(path, pageTreeCache = false, emptiesStoreOnClose = false)
                }
                    .also(OpenViewerDocuments::add)
            })
            return AndroidViewerDocument(document, counter, ownBytes)
        }
    }
}
