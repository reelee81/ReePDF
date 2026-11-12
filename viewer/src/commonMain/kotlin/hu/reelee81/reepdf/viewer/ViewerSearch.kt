package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ViewerSearch(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val onJump: (page: Int, bounds: Rect) -> Unit
) {
    data class Hit(val page: Int, val index: Int)

    private var document: ViewerDocument? = null
    private var counts = IntArray(0)
    private var countedBefore = 0
    private var wanted: IntRange = IntRange.EMPTY
    private val pageHits = mutableStateMapOf<Int, ViewerTextHits>()
    private val fetching = HashMap<Int, Job>()
    private var runJob: Job? = null
    private var runPage = 0
    private var runLeft = 0
    private var moveJob: Job? = null

    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var total by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var current by mutableStateOf<Hit?>(null)
        private set
    var isFinished by mutableStateOf(false)
        private set

    val noMatch: Boolean
        get() = isFinished && total == 0

    fun hitsOf(page: Int): ViewerTextHits? = pageHits[page]

    fun attach(document: ViewerDocument?) {
        stop()
        this.document = document
        counts = IntArray(document?.pageCount ?: 0) { UNKNOWN }
    }

    fun start() {
        isActive = true
    }

    fun stop() {
        cancelAll()
        clearResults()
        query = ""
        isActive = false
    }

    fun setQuery(text: String, fromPage: Int) {
        if (text == query) return
        query = text
        cancelJobs()
        clearResults()
        val doc = document ?: return
        if (text.isBlank() || counts.isEmpty()) {
            doc.releaseCount()
            return
        }
        runPage = fromPage.coerceIn(0, counts.size - 1)
        runLeft = counts.size
        launchRun(doc, text, wait = true)
    }

    fun pause() = cancelAll()

    fun resume() {
        val doc = document ?: return
        if (query.isBlank() || runLeft == 0 || runJob?.isActive == true) return
        launchRun(doc, query, wait = false)
    }

    fun next() = move(forward = true)

    fun previous() = move(forward = false)

    fun onVisible(pages: IntRange) {
        wanted = pages
        retain()
        val iterator = fetching.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!keeps(entry.key)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
        val doc = document ?: return
        if (query.isBlank()) return
        for (page in pages) {
            if (page !in counts.indices || counts[page] <= 0 || page in pageHits || page in fetching) continue
            fetch(doc, page)
        }
    }

    private fun launchRun(doc: ViewerDocument, text: String, wait: Boolean) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (wait) delay(debounceMs.milliseconds)
            run(doc, text)
        }
        runJob = job
        job.start()
    }

    private suspend fun run(doc: ViewerDocument, text: String) {
        while (runLeft > 0) {
            val page = runPage
            val startHits = if (runLeft == counts.size) searchPage(doc, page, text) else null
            val count = startHits?.count ?: countPage(doc, page, text)
            val shows = count > 0 && (current == null || keeps(page) && page !in pageHits)
            val shown = startHits ?: if (shows) searchPage(doc, page, text) else null
            counts[page] = count
            total += count
            runPage = (page + 1) % counts.size
            runLeft--
            if (count > 0) found(page, count, shown)
        }
        doc.releaseCount()
        isFinished = true
    }

    private fun found(page: Int, count: Int, shown: ViewerTextHits?) {
        val now = current
        if (now != null && page < now.page) {
            countedBefore += count
            position = countedBefore + now.index + 1
        }
        if (shown == null) return
        if (now == null && shown.count > 0) {
            pageHits[page] = shown
            select(Hit(page, 0))
        } else if (keeps(page)) {
            pageHits[page] = shown
        }
    }

    private fun move(forward: Boolean) {
        val now = current ?: return
        val doc = document ?: return
        val target = if (forward) {
            if (now.index + 1 < counts[now.page]) Hit(now.page, now.index + 1)
            else pageWithHits(now.page, 1)?.let { Hit(it, 0) }
        } else {
            if (now.index > 0) Hit(now.page, now.index - 1)
            else pageWithHits(now.page, -1)?.let { Hit(it, counts[it] - 1) }
        } ?: return
        moveJob?.cancel()
        moveJob = null
        if (target.page in pageHits) {
            select(target)
            return
        }
        val text = query
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val hits = searchPage(doc, target.page, text)
            if (hits.count > target.index) {
                pageHits[target.page] = hits
                select(target)
            }
        }
        moveJob = job
        job.start()
    }

    private fun select(hit: Hit) {
        current = hit
        var before = 0
        for (page in 0 until hit.page) before += counts[page].coerceAtLeast(0)
        countedBefore = before
        position = before + hit.index + 1
        retain()
        pageHits[hit.page]?.takeIf { hit.index < it.count }?.let { onJump(hit.page, it.bounds(hit.index)) }
    }

    private fun pageWithHits(from: Int, step: Int): Int? {
        val size = counts.size
        var page = from
        repeat(size) {
            page = (page + step + size) % size
            if (counts[page] > 0) return page
        }
        return null
    }

    private fun fetch(doc: ViewerDocument, page: Int) {
        val text = query
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val hits = searchPage(doc, page, text)
            fetching.remove(page)
            if (keeps(page)) pageHits[page] = hits
        }
        fetching[page] = job
        job.start()
    }

    private suspend fun searchPage(doc: ViewerDocument, page: Int, text: String): ViewerTextHits =
        try {
            doc.search(page, text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ViewerTextHits.NONE
        }

    private suspend fun countPage(doc: ViewerDocument, page: Int, text: String): Int =
        try {
            doc.count(page, text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }

    private fun keeps(page: Int): Boolean = page in wanted || page == current?.page

    private fun retain() {
        pageHits.keys.filterNot(::keeps).forEach(pageHits::remove)
    }

    private fun cancelAll() {
        cancelJobs()
        document?.releaseCount()
    }

    private fun cancelJobs() {
        runJob?.cancel()
        runJob = null
        moveJob?.cancel()
        moveJob = null
        fetching.values.forEach { it.cancel() }
        fetching.clear()
    }

    private fun clearResults() {
        counts.fill(UNKNOWN)
        runLeft = 0
        countedBefore = 0
        pageHits.clear()
        total = 0
        position = 0
        current = null
        isFinished = false
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        private const val UNKNOWN = -1
    }
}
