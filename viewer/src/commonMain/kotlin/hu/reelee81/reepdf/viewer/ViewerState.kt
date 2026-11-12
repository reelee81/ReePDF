package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ViewerState(val title: String, val canEdit: Boolean, private val scope: CoroutineScope) {
    private val started = TimeSource.Monotonic.markNow()
    private val wait = ViewerWait { started.elapsedNow().inWholeMilliseconds }
    private val cache = ViewerImageCache<Any, ViewerImage>(0L) { it.bytes }
    private val shown = mutableStateMapOf<Any, ViewerImage>()
    private val pageImages = mutableStateMapOf<Int, ViewerPageImage>()
    private val running = HashMap<Any, Job>()
    private var batch: TileBatch? = null
    private var tileOrder: List<Int> = emptyList()
    private val heavyPages = HashSet<Int>()
    private val failedTiles = HashSet<ViewerTile>()
    private var needed: Set<Any> = emptySet()
    private var document: ViewerDocument? = null
    private var sizes: ViewerPageSizes? = null
    private var tilePlan: ViewerTilePlan? = null
    private var openJob: Job? = null
    private var revealJob: Job? = null
    private var pendingPage = 0
    private var viewWidth = 0
    private var viewHeight = 0
    private var spacing = -1.0
    private var renderZoom = 0f
    private var zooming = false
    private var uncoveredHeight = Int.MAX_VALUE
    private var visibleRange = IntRange.EMPTY
    private val pageTexts = mutableStateMapOf<Int, String>()
    private val textJobs = HashMap<Int, Job>()

    val search = ViewerSearch(scope) { page, bounds -> reveal(page, bounds) }
    val selection = ViewerSelection(scope)

    private var readsTexts by mutableStateOf(false)

    var readsPageTexts: Boolean
        get() = readsTexts
        set(value) {
            if (readsTexts == value) return
            readsTexts = value
            if (value) readTexts(visibleRange) else dropTexts()
        }

    var keyboardCover by mutableIntStateOf(0)

    var bottomGap by mutableStateOf(true)

    var waitReason by mutableStateOf<ViewerWait.Reason?>(null)
        private set
    var waitRevealed by mutableStateOf(false)
        private set
    var openError by mutableStateOf<String?>(null)
        private set
    var layout by mutableStateOf<ViewerPageLayout?>(null)
        private set
    var viewport by mutableStateOf<ViewerViewport?>(null)
        private set
    var failedPages by mutableStateOf(emptySet<Int>())
        private set
    var scrollCount by mutableIntStateOf(0)
        private set
    private var tiles by mutableStateOf(emptyMap<Int, List<ViewerTile>>())

    val isWaiting: Boolean
        get() = waitReason != null

    val leavesOnCancel: Boolean
        get() = waitReason == ViewerWait.Reason.OPENING_EDITED

    val currentPage: Int
        get() {
            val pages = layout ?: return pendingPage
            val view = viewport ?: return pendingPage
            val top = view.contentTop
            val bottom = top + view.contentHeight
            var best = pages.labelPages(top, bottom).first
            var bestArea = -1.0
            for (page in pages.visiblePages(top, bottom)) {
                val left = pages.left(page, view.contentLeft, view.contentWidth)
                val width = min(left + pages.width(page), view.contentLeft + view.contentWidth) - max(left, view.contentLeft)
                val height = min(pages.bottom(page), bottom) - max(pages.top(page), top)
                val area = max(width, 0.0) * max(height, 0.0)
                if (area > bestArea) {
                    bestArea = area
                    best = page
                }
            }
            return best
        }

    fun pageImage(page: Int): ViewerImage? = pageImages[page]?.let { shown[it] }

    fun tilesOf(page: Int): List<Pair<ViewerTile, ViewerImage>> =
        tiles[page].orEmpty().mapNotNull { tile -> shown[tile]?.let { tile to it } }

    fun open(reason: ViewerWait.Reason, page: Int, opener: suspend () -> ViewerDocument) {
        openJob?.cancel()
        closeDocument()
        openError = null
        pendingPage = page
        startWait(reason)
        openJob = scope.launch {
            var opened: ViewerDocument? = null
            try {
                val created = opener()
                opened = created
                val pageSizes = created.pageSizes()
                document = created
                sizes = pageSizes
                search.attach(created)
                selection.attach(created)
                applyLayout()
            } catch (e: CancellationException) {
                if (document !== opened) opened?.close()
                throw e
            } catch (e: Exception) {
                if (document === opened) closeDocument() else opened?.close()
                stopWait()
                openError = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    fun onSize(width: Int, height: Int, pageSpacing: Double) {
        if (width <= 0 || height <= 0) return
        val resized = width != viewWidth || height != viewHeight
        val respaced = pageSpacing != spacing
        if (!resized && !respaced) return
        val built = layout
        val current = viewport
        if (respaced && built != null) pendingPage = currentPage
        viewWidth = width
        viewHeight = height
        spacing = pageSpacing
        tilePlan = ViewerTilePlan(TILE_SIZE, width.toLong() * height)
        cache.budgetBytes = width.toLong() * height * BYTES_PER_PIXEL * CACHE_SCREENS
        if (built == null || current == null || respaced) {
            applyLayout()
            return
        }
        val next = current.resized(width, height, built)
        viewport = next
        renderZoom = next.zoom
        scrolled()
        plan()
    }

    fun scrollBy(dx: Double, dy: Double) {
        val pages = layout ?: return
        val view = viewport ?: return
        update(view.scrolledBy(dx, dy, pages))
    }

    fun scrollToY(y: Double) {
        val pages = layout ?: return
        val view = viewport ?: return
        update(view.scrolledTo(view.scrollX, y, pages))
    }

    fun zoomBy(factor: Float, pivotX: Double, pivotY: Double) {
        val pages = layout ?: return
        val view = viewport ?: return
        zooming = true
        update(view.zoomedTo(view.zoom * factor, pivotX, pivotY, pages))
    }

    fun zoomFrom(start: ViewerViewport, zoom: Float, pivotX: Double, pivotY: Double) {
        val pages = layout ?: return
        zooming = true
        update(start.zoomedTo(zoom, pivotX, pivotY, pages))
    }

    fun endZoom() {
        val view = viewport ?: return
        zooming = false
        renderZoom = view.zoom
        plan()
    }

    fun scrollToPage(page: Int) {
        val pages = layout
        if (pages == null || viewWidth <= 0) {
            pendingPage = page
            return
        }
        val next = ViewerViewport.atPage(page, viewWidth, viewHeight, pages)
        zooming = false
        viewport = next
        renderZoom = next.zoom
        scrolled()
        plan()
    }

    fun onShown() {
        search.resume()
        plan()
    }

    fun onHidden() {
        val doc = document ?: return
        search.pause()
        cancelRenders()
        release(doc, cache.clear())
        shown.clear()
        doc.trimMemory()
    }

    fun trimMemory() {
        val doc = document ?: return
        release(doc, cache.retainOnly { it in needed })
        doc.trimMemory()
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        closeDocument()
        stopWait()
    }

    fun startSearch() {
        if (isWaiting || document == null) return
        selection.clear()
        search.start()
    }

    fun handleBack(): Boolean = when {
        selection.isActive -> {
            selection.clear()
            true
        }
        search.isActive -> {
            search.stop()
            true
        }
        else -> false
    }

    fun setUncoveredHeight(height: Int) {
        uncoveredHeight = if (height > 0) height else Int.MAX_VALUE
    }

    fun pageText(page: Int): String? = pageTexts[page]

    fun screenRect(page: Int, box: Rect): Rect? {
        val pages = layout ?: return null
        val view = viewport ?: return null
        if (page !in 0 until pages.pageCount) return null
        return screenBox(pages, view, page, box)
    }

    fun handlePoint(handle: ViewerTextHandle): Offset? =
        screenRect(handle.page, Rect(handle.x, handle.y, handle.x, handle.y))?.topLeft

    fun selectionBounds(): Rect? {
        val pages = layout ?: return null
        val view = viewport ?: return null
        val top = view.contentTop
        var bounds: Rect? = null
        for (page in pages.visiblePages(top, top + view.contentHeight)) {
            val rects = selection.rectsOf(page) ?: continue
            val count = ViewerTextRects.count(rects)
            if (count == 0) continue
            val shown = screenBox(pages, view, page, ViewerTextRects.bounds(rects, 0, count))
            bounds = bounds?.let {
                Rect(min(it.left, shown.left), min(it.top, shown.top), max(it.right, shown.right), max(it.bottom, shown.bottom))
            } ?: shown
        }
        return bounds
    }

    fun pagePoint(x: Float, y: Float): Pair<Int, ViewerTextPoint>? {
        val pages = layout ?: return null
        val view = viewport ?: return null
        val contentY = (view.scrollY + y) / view.zoom
        val page = pages.pageAt(contentY)
        val left = pages.left(page, view.contentLeft, view.contentWidth)
        val pageX = ((view.scrollX + x) / view.zoom - left).coerceIn(0.0, pages.width(page))
        val pageY = (contentY - pages.top(page)).coerceIn(0.0, pages.height(page))
        return page to ViewerTextPoint(pageX.toFloat(), pageY.toFloat())
    }

    fun reveal(page: Int, box: Rect) {
        val pages = layout ?: return
        val start = viewport ?: return
        if (page !in 0 until pages.pageCount) return
        val bottom = min(start.height, uncoveredHeight).toDouble()
        val shown = screenBox(pages, start, page, box)
        if (shown.left >= 0f && shown.right <= start.width && shown.top >= 0f && shown.bottom <= bottom) return
        var next = start
        val x = if (shown.left < 0f || shown.right > start.width) {
            start.scrollX + (shown.left + shown.right) / 2.0 - start.width / 2.0
        } else {
            start.scrollX
        }
        next = next.scrolledTo(x, start.scrollY + shown.top - bottom / REVEAL_FRACTION, pages)
        val moved = screenBox(pages, next, page, box)
        if (moved.left < 0f || moved.right > next.width) {
            next = next.scrolledTo(next.scrollX + (moved.left + moved.right) / 2.0 - next.width / 2.0, next.scrollY, pages)
        }
        zooming = false
        update(next)
    }

    private fun applyLayout() {
        val pageSizes = sizes ?: return
        if (viewWidth <= 0 || viewHeight <= 0 || spacing < 0.0) return
        val built = ViewerPageLayout(pageSizes.widths, pageSizes.heights, spacing)
        val start = ViewerViewport.atPage(pendingPage, viewWidth, viewHeight, built)
        layout = built
        viewport = start
        renderZoom = start.zoom
        zooming = false
        stopWait()
        scrolled()
        plan()
    }

    private fun update(next: ViewerViewport) {
        val view = viewport
        if (view != null && next.zoom == view.zoom && next.scrollX == view.scrollX && next.scrollY == view.scrollY) return
        viewport = next
        scrolled()
        plan()
    }

    private fun scrolled() {
        scrollCount++
    }

    private fun plan() {
        val doc = document ?: return
        val pages = layout ?: return
        val view = viewport ?: return
        val planner = tilePlan ?: return
        val zoom = renderZoom
        if (!zooming) failedTiles.removeAll { it.zoom != zoom }
        val top = view.contentTop
        val bottom = top + view.contentHeight
        val center = (top + bottom) / 2
        val range = pages.visiblePages(top, bottom)
        visibleRange = range
        search.onVisible(range)
        selection.onVisible(range)
        readTexts(range)
        val failed = failedPages
        val visible = pages.visiblePages(top, bottom)
            .filter { it !in failed }
            .sortedBy { abs((pages.top(it) + pages.bottom(it)) / 2 - center) }
        val wanted = LinkedHashSet<Any>()
        val wantedTiles = HashMap<Int, List<ViewerTile>>()
        for (page in visible) {
            val width = pages.width(page)
            val height = pages.height(page)
            val image = planner.pageImage(page, width, height, zoom)
            val chosen = keptPageImage(page, image, !zooming && zoom > image.scale) ?: image
            wanted += chosen
            if (pageImages[page] != chosen && shown[chosen] != null) pageImages[page] = chosen
            if (zooming) continue
            val left = pages.left(page, view.contentLeft, view.contentWidth)
            val pageTop = pages.top(page)
            val visibleLeft = max(0.0, view.contentLeft - left)
            val visibleRight = min(width, view.contentLeft + view.contentWidth - left)
            val visibleTop = max(0.0, top - pageTop)
            val visibleBottom = min(height, bottom - pageTop)
            if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) continue
            val pageTiles = planner.tiles(page, width, height, zoom, visibleLeft, visibleTop, visibleRight, visibleBottom)
            if (pageTiles.isEmpty()) continue
            wantedTiles[page] = pageTiles
            wanted.addAll(pageTiles)
        }
        if (zooming) {
            tiles.values.forEach(wanted::addAll)
        } else {
            tiles = wantedTiles
            tileOrder = visible
        }
        needed = wanted
        val iterator = running.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in wanted || isBatched(entry.key)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
        batch?.let { current ->
            if (current.tiles.none { it in wanted }) {
                current.job.cancel()
                batch = null
            }
        }
        for (key in wanted) {
            if (isBatched(key) || key in failedTiles || cache[key] != null || key in running) continue
            val job = scope.launch(start = CoroutineStart.LAZY) { render(doc, key) }
            running[key] = job
            job.start()
        }
        if (!zooming) startBatch(doc)
        trim(doc)
    }

    private fun keptPageImage(page: Int, image: ViewerPageImage, tiled: Boolean): ViewerPageImage? {
        if (!tiled) return null
        val current = pageImages[page] ?: return null
        if (current == image || shown[current] == null) return null
        return current.takeIf { it.scale * 2 >= image.scale }
    }

    private fun isBatched(key: Any): Boolean = key is ViewerTile && key.page in heavyPages

    private fun startBatch(doc: ViewerDocument) {
        if (batch != null) return
        val planner = tilePlan ?: return
        val planned = tiles
        for (page in tileOrder) {
            if (page !in heavyPages) continue
            val missing = planned[page]?.filter { cache.peek(it) == null && it !in failedTiles }
            if (missing.isNullOrEmpty()) continue
            val chosen = planner.batch(missing)
            val job = scope.launch(start = CoroutineStart.LAZY) { renderBatch(doc, chosen) }
            batch = TileBatch(chosen, job)
            job.start()
            return
        }
    }

    private suspend fun renderBatch(doc: ViewerDocument, batchTiles: List<ViewerTile>) {
        val result = try {
            doc.renderTiles(batchTiles)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ViewerTilesRender.Aborted
        }
        if (batch?.tiles === batchTiles) batch = null
        if (document !== doc) {
            if (result is ViewerTilesRender.Done) result.images.forEach(doc::release)
            return
        }
        when (result) {
            is ViewerTilesRender.Done -> {
                batchTiles.zip(result.images).forEach { (tile, image) ->
                    release(doc, cache.put(tile, image))
                    shown[tile] = image
                }
                trim(doc)
                if (!zooming) startBatch(doc)
            }
            ViewerTilesRender.Failed -> {
                failedTiles += batchTiles
                if (!zooming) startBatch(doc)
            }
            ViewerTilesRender.Aborted -> Unit
        }
    }

    private suspend fun render(doc: ViewerDocument, key: Any) {
        val result = try {
            when (key) {
                is ViewerPageImage -> doc.render(key.page, key.scale, 0, 0, key.width, key.height)
                is ViewerTile -> doc.render(key.page, key.zoom, key.left, key.top, key.width, key.height)
                else -> ViewerRender.Aborted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ViewerRender.Aborted
        }
        running.remove(key)
        if (document !== doc) {
            if (result is ViewerRender.Done) doc.release(result.image)
            return
        }
        when (result) {
            is ViewerRender.Done -> {
                release(doc, cache.put(key, result.image))
                shown[key] = result.image
                if (key is ViewerPageImage) {
                    pageImages[key.page] = key
                    val heavy = result.millis >= HEAVY_PAGE_MS
                    val changed = if (heavy) heavyPages.add(key.page) else heavyPages.remove(key.page)
                    if (changed && tiles[key.page] != null) {
                        plan()
                        return
                    }
                }
                trim(doc)
            }
            ViewerRender.Failed -> {
                if (key is ViewerTile) {
                    failedTiles += key
                } else {
                    failedPages = failedPages + pageOf(key)
                    plan()
                }
            }
            ViewerRender.Aborted -> Unit
        }
    }

    private fun pageOf(key: Any): Int = when (key) {
        is ViewerPageImage -> key.page
        is ViewerTile -> key.page
        else -> -1
    }

    private fun trim(doc: ViewerDocument) {
        release(doc, cache.trim { it in needed })
    }

    private fun release(doc: ViewerDocument, images: List<ViewerImage>) {
        if (images.isEmpty()) return
        val gone = images.toSet()
        shown.entries.removeAll { it.value in gone }
        images.forEach(doc::release)
    }

    private fun cancelRenders() {
        running.values.forEach { it.cancel() }
        running.clear()
        batch?.job?.cancel()
        batch = null
    }

    private fun closeDocument() {
        val doc = document
        cancelRenders()
        val images = cache.clear()
        if (doc != null) images.forEach(doc::release)
        shown.clear()
        pageImages.clear()
        tiles = emptyMap()
        needed = emptySet()
        document = null
        sizes = null
        layout = null
        viewport = null
        failedPages = emptySet()
        heavyPages.clear()
        failedTiles.clear()
        search.attach(null)
        selection.attach(null)
        dropTexts()
        visibleRange = IntRange.EMPTY
        doc?.close()
    }

    private fun screenBox(pages: ViewerPageLayout, view: ViewerViewport, page: Int, box: Rect): Rect {
        val left = pages.left(page, view.contentLeft, view.contentWidth)
        val top = pages.top(page)
        val zoom = view.zoom
        return Rect(
            ((left + box.left) * zoom - view.scrollX).toFloat(),
            ((top + box.top) * zoom - view.scrollY).toFloat(),
            ((left + box.right) * zoom - view.scrollX).toFloat(),
            ((top + box.bottom) * zoom - view.scrollY).toFloat()
        )
    }

    private fun readTexts(range: IntRange) {
        if (!readsPageTexts) return
        pageTexts.keys.filter { it !in range }.forEach(pageTexts::remove)
        val iterator = textJobs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in range) {
                entry.value.cancel()
                iterator.remove()
            }
        }
        val doc = document ?: return
        for (page in range) {
            if (page in pageTexts || page in textJobs) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val text = try {
                    doc.text(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                textJobs.remove(page)
                if (document === doc && readsPageTexts) pageTexts[page] = text
            }
            textJobs[page] = job
            job.start()
        }
    }

    private fun dropTexts() {
        textJobs.values.forEach { it.cancel() }
        textJobs.clear()
        pageTexts.clear()
    }

    private fun startWait(reason: ViewerWait.Reason) {
        wait.start(reason)
        waitReason = reason
        revealJob?.cancel()
        val remaining = wait.revealDelayMs() ?: return
        revealJob = scope.launch {
            delay(remaining.milliseconds)
            waitRevealed = true
        }
    }

    private fun stopWait() {
        wait.stop()
        revealJob?.cancel()
        revealJob = null
        waitReason = null
        waitRevealed = false
    }

    private class TileBatch(val tiles: List<ViewerTile>, val job: Job)

    private companion object {
        const val TILE_SIZE = 512
        const val HEAVY_PAGE_MS = 1_000L
        const val BYTES_PER_PIXEL = 4L
        const val CACHE_SCREENS = 4L
        const val REVEAL_FRACTION = 3.0
    }
}
