package hu.reelee81.reepdf.viewer

import kotlinx.coroutines.CompletableDeferred

internal class FakeTextDocument(override val pageCount: Int) : ViewerDocument {
    val calls = mutableListOf<String>()
    var hitCounts: (Int) -> Int = { 0 }
    var boxCounts: (Int) -> Int = { hitCounts(it) }
    var failingPages: Set<Int> = emptySet()
    var gated = false
    var caretGated = false
    var words: (Int, ViewerTextPoint) -> ViewerTextSpan? = { _, _ -> null }
    var carets: (Int, ViewerTextPoint) -> ViewerTextPoint? = { _, _ -> null }
    val caretCalls = mutableListOf<String>()
    var pageTexts: (Int) -> String = { "p$it" }
    private val gates = ArrayDeque<CompletableDeferred<Unit>>()

    var counting = false
        private set

    var releases = 0
        private set

    val waiting: Int
        get() = gates.size

    fun openGate() {
        gates.removeFirst().complete(Unit)
    }

    override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { 100f }, FloatArray(pageCount) { 200f })

    override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

    override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

    override suspend fun count(page: Int, query: String): Int {
        calls += "count $page $query"
        counting = true
        if (gated) CompletableDeferred<Unit>().also(gates::addLast).await()
        if (page in failingPages) throw IllegalStateException("broken page")
        return hitCounts(page)
    }

    override fun releaseCount() {
        counting = false
        releases++
    }

    override suspend fun search(page: Int, query: String): ViewerTextHits {
        calls += "search $page $query"
        if (page in failingPages) throw IllegalStateException("broken page")
        val count = boxCounts(page)
        val rects = FloatArray(count * ViewerTextHits.RECT_SIZE)
        for (hit in 0 until count) {
            val offset = hit * ViewerTextHits.RECT_SIZE
            rects[offset] = 10f * hit
            rects[offset + 1] = 20f
            rects[offset + 2] = 10f * hit + 5f
            rects[offset + 3] = 30f
        }
        return ViewerTextHits(rects, IntArray(count + 1) { it })
    }

    override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? {
        calls += "word $page"
        return words(page, point)
    }

    override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint? {
        caretCalls += "caret $page ${point.short()}"
        if (caretGated) CompletableDeferred<Unit>().also(gates::addLast).await()
        return carets(page, point)
    }

    override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): FloatArray {
        calls += "highlight $page ${from.short()} ${to.short()}"
        return floatArrayOf(from?.x ?: 0f, 10f, to?.x ?: 100f, 20f)
    }

    override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?): String {
        calls += "copy $page ${from.short()} ${to.short()}"
        return pageTexts(page)
    }

    override suspend fun text(page: Int): String {
        calls += "text $page"
        return "text $page"
    }

    override fun release(image: ViewerImage) = Unit

    override fun trimMemory() = Unit

    override fun close() = Unit

    private fun ViewerTextPoint?.short() = if (this == null) "null" else "$x,$y"
}
