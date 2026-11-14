package hu.reelee81.reepdf

import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfRasterTarget
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
    private val raster: AndroidPageRaster
) : ViewerDocument {

    override val pageCount: Int
        get() = document.pageCount

    override suspend fun pageSizes(): ViewerPageSizes =
        document.pageSizes().let { ViewerPageSizes(it.widths, it.heights) }

    override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender =
        raster.render(page, zoom, left, top, width, height)

    override suspend fun renderTiles(tiles: List<ViewerTile>): ViewerTilesRender = raster.renderTiles(tiles.first().page, tiles)

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

    override fun release(image: ViewerImage) = raster.release(image)

    override fun trimMemory() = document.trimMemory()

    override fun close() {
        counter.release()
        document.close()
    }

    companion object {
        suspend fun open(path: String): AndroidViewerDocument {
            val ownBytes = AtomicLong()
            val nativeBytes = AndroidPageRaster.nativeBytes(ownBytes)
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
            return AndroidViewerDocument(document, counter, AndroidPageRaster(document, ownBytes))
        }
    }
}
