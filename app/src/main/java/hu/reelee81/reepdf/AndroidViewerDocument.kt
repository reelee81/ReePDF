package hu.reelee81.reepdf

import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfEditProjection
import hu.reelee81.reepdf.pdf.PdfMark
import hu.reelee81.reepdf.pdf.PdfMarkKind
import hu.reelee81.reepdf.pdf.PdfMarkPath
import hu.reelee81.reepdf.pdf.PdfMarkSave
import hu.reelee81.reepdf.pdf.PdfMarkText
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfSearchCounter
import hu.reelee81.reepdf.pdf.PdfStreamEdit
import hu.reelee81.reepdf.pdf.PdfTextPoint
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.viewer.ViewerDocument
import hu.reelee81.reepdf.viewer.ViewerEditPlan
import hu.reelee81.reepdf.viewer.ViewerImage
import hu.reelee81.reepdf.viewer.ViewerMark
import hu.reelee81.reepdf.viewer.ViewerMarkKind
import hu.reelee81.reepdf.viewer.ViewerMarkPath
import hu.reelee81.reepdf.viewer.ViewerMarkText
import hu.reelee81.reepdf.viewer.ViewerMarkSource
import hu.reelee81.reepdf.viewer.ViewerPageMarks
import hu.reelee81.reepdf.viewer.ViewerPageSizes
import hu.reelee81.reepdf.viewer.ViewerRender
import hu.reelee81.reepdf.viewer.ViewerSavedMark
import hu.reelee81.reepdf.viewer.ViewerStreamEdit
import hu.reelee81.reepdf.viewer.ViewerTextHighlight
import hu.reelee81.reepdf.viewer.ViewerTextHits
import hu.reelee81.reepdf.viewer.ViewerTextPoint
import hu.reelee81.reepdf.viewer.ViewerTextSpan
import hu.reelee81.reepdf.viewer.ViewerTile
import hu.reelee81.reepdf.viewer.ViewerTilesRender
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AndroidViewerDocument private constructor(
    val path: String,
    private val document: PdfViewerDocument<PdfRasterTarget>,
    private val counter: PdfSearchCounter<PdfRasterTarget>,
    private val raster: AndroidPageRaster
) : ViewerDocument, ViewerMarkSource {
    private val toPages = ConcurrentHashMap<Int, DoubleArray>()

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

    override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): ViewerTextHighlight =
        document.highlight(page, from?.toPdf(), to?.toPdf()).let { ViewerTextHighlight(it.rects, it.start?.toViewer(), it.end?.toViewer()) }

    override suspend fun baselines(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): FloatArray =
        document.baselines(page, from?.toPdf(), to?.toPdf()).runs

    override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): String =
        document.copy(page, from?.toPdf(), to?.toPdf())

    override suspend fun text(page: Int): String = document.text(page)

    override suspend fun pageMarks(page: Int, blank: Boolean): ViewerPageMarks {
        val marks = document.pageMarks(page, blank)
        toPages[page] = marks.toPage
        return ViewerPageMarks(
            marks.marks.map { found ->
                val mark = found.mark
                ViewerSavedMark(
                    mark.id, found.stream, ViewerMarkKind.valueOf(mark.kind.name), mark.color, mark.width,
                    ViewerMarkPath(mark.path.verbs, mark.path.points), found.clip, mark.text?.toViewer()
                )
            },
            marks.streams
        )
    }

    override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) =
        document.editStreams(edits.mapValues { (_, edit) -> PdfStreamEdit(edit.blank, edit.erased) })

    suspend fun markPlan(plan: ViewerEditPlan): PdfMarkSave.Plan {
        val added = plan.added.mapValues { (page, marks) ->
            val toPage = toPages[page] ?: document.pageMarks(page).toPage.also { toPages[page] = it }
            val toUser = checkNotNull(PdfEditProjection.inverse(toPage)) { "Page ${page + 1} has no usable transform" }
            marks.map { mark -> mark.toPdf(toUser) }
        }
        return PdfMarkSave.Plan(
            added = added,
            streams = plan.erased.mapValues { (_, ids) -> PdfStreamEdit(false, ids) },
            blankPages = plan.blankPages,
            blankAll = plan.blankAll,
            editedPages = plan.erasedPages
        )
    }

    private fun ViewerTextPoint.toPdf() = PdfTextPoint(x, y)

    private fun PdfTextPoint.toViewer() = ViewerTextPoint(x, y)

    override fun release(image: ViewerImage) = raster.release(image)

    override fun trimMemory() = document.trimMemory()

    override fun close() {
        counter.release()
        document.close()
    }

    companion object {
        fun ViewerMark.toPdf(toUser: DoubleArray): PdfMark {
            val typed = text?.let { PdfMarkText(it.text, it.weight, it.size, DoubleArray(it.matrix.size) { index -> it.matrix[index].toDouble() }) }
            return PdfMark(PdfMark.newId(), PdfMarkKind.valueOf(kind.name), color, width, PdfMarkPath(path.verbs, path.points), typed)
                .transformed(toUser)
        }

        fun PdfMarkText.toViewer(): ViewerMarkText? {
            val values = FloatArray(matrix.size) { matrix[it].toFloat() }
            return if (values.all { it.isFinite() }) ViewerMarkText(text, weight, size, values) else null
        }

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
            return AndroidViewerDocument(path, document, counter, AndroidPageRaster(document, ownBytes))
        }
    }
}
