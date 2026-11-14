package hu.reelee81.reepdf

import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfPageView
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.viewer.ViewerImage
import hu.reelee81.reepdf.viewer.ViewerPageSizes
import hu.reelee81.reepdf.viewer.ViewerPageSource
import hu.reelee81.reepdf.viewer.ViewerRender
import hu.reelee81.reepdf.viewer.ViewerTile
import hu.reelee81.reepdf.viewer.ViewerTilesRender
import java.util.concurrent.atomic.AtomicLong

class AndroidSinglePage private constructor(
    private val document: PdfViewerDocument<PdfRasterTarget>,
    private val raster: AndroidPageRaster,
    private val pageIndex: Int
) : ViewerPageSource {

    override val pageCount: Int = 1

    override suspend fun pageSizes(): ViewerPageSizes {
        val size = document.pageSize(pageIndex) ?: throw IllegalStateException("Page ${pageIndex + 1} has no valid size")
        return ViewerPageSizes(floatArrayOf(size.width), floatArrayOf(size.height))
    }

    override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int): ViewerRender =
        raster.render(pageIndex, zoom, left, top, width, height)

    override suspend fun renderTiles(tiles: List<ViewerTile>): ViewerTilesRender = raster.renderTiles(pageIndex, tiles)

    override fun release(image: ViewerImage) = raster.release(image)

    override fun trimMemory() = document.trimMemory()

    override fun close() = document.close()

    companion object {
        suspend fun open(path: String, pageIndex: Int, view: PdfPageView? = null): AndroidSinglePage {
            val ownBytes = AtomicLong()
            val views = if (view == null) emptyMap() else mapOf(pageIndex to view)
            val document = PdfViewerDocument.open(AndroidPageRaster.nativeBytes(ownBytes)) {
                MuPdfViewerEngine.open(path, pageTreeCache = false, views = views)
            }
            OpenViewerDocuments.add(document)
            if (pageIndex !in 0 until document.pageCount) {
                document.close()
                throw IllegalArgumentException("Page ${pageIndex + 1} is outside 1..${document.pageCount}")
            }
            return AndroidSinglePage(document, AndroidPageRaster(document, ownBytes), pageIndex)
        }
    }
}
