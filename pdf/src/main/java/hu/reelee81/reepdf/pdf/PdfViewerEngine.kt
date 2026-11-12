package hu.reelee81.reepdf.pdf

interface PdfViewerEngine<T> {
    val pageCount: Int

    fun pageSize(pageIndex: Int): PageSize

    fun render(pageIndex: Int, zoom: Float, left: Int, top: Int, target: T, abort: PdfRenderAbort)

    fun search(pageIndex: Int, needle: String): PdfTextHits

    fun count(pageIndex: Int, needle: String): Int

    fun word(pageIndex: Int, point: PdfTextPoint, tolerance: Float): PdfTextSpan?

    fun caret(pageIndex: Int, point: PdfTextPoint): PdfTextPoint?

    fun highlight(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): FloatArray

    fun copy(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): String

    fun text(pageIndex: Int): String

    fun emptyStore()

    fun dropLoadedObjects()

    fun releaseFreeMemory()

    fun close()

    class PageSize(val width: Float, val height: Float)
}
