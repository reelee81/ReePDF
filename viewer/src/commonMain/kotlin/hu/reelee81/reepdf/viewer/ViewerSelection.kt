package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ViewerSelection(private val scope: CoroutineScope) {
    data class Anchor(val page: Int, val point: ViewerTextPoint?)

    enum class Handle { START, END }

    private data class Part(val from: ViewerTextPoint?, val to: ViewerTextPoint?)

    private class DragTarget(val fixed: Anchor, val page: Int, val point: ViewerTextPoint)

    private var document: ViewerDocument? = null
    private var wanted: IntRange = IntRange.EMPTY
    private val pageRects = mutableStateMapOf<Int, FloatArray>()
    private val shownParts = HashMap<Int, Part>()
    private val fetching = HashMap<Int, Pair<Part, Job>>()
    private var wordJob: Job? = null
    private var dragJob: Job? = null
    private var dragTarget: DragTarget? = null
    private var fixed: Anchor? = null

    var first by mutableStateOf<Anchor?>(null)
        private set
    var last by mutableStateOf<Anchor?>(null)
        private set
    var dragged by mutableStateOf<Handle?>(null)
        private set

    val isActive: Boolean
        get() = first != null && last != null

    val pages: IntRange
        get() {
            val a = first ?: return IntRange.EMPTY
            val b = last ?: return IntRange.EMPTY
            return minOf(a.page, b.page)..maxOf(a.page, b.page)
        }

    fun rectsOf(page: Int): FloatArray? = pageRects[page]

    fun attach(document: ViewerDocument?) {
        clear()
        this.document = document
    }

    fun selectWord(page: Int, point: ViewerTextPoint, tolerance: Float) {
        val doc = document ?: return
        wordJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val span = try {
                doc.word(page, point, tolerance)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            forget()
            first = Anchor(page, span.from)
            last = Anchor(page, span.to)
            pageRects[page] = span.rects
            shownParts[page] = Part(span.from, span.to)
            refresh()
        }
        wordJob = job
        job.start()
    }

    fun selectAll(page: Int) {
        wordJob?.cancel()
        forget()
        first = Anchor(page, null)
        last = Anchor(page, null)
        refresh()
    }

    fun isWholePage(page: Int): Boolean = first == Anchor(page, null) && last == Anchor(page, null)

    fun clear() {
        wordJob?.cancel()
        wordJob = null
        forget()
    }

    fun onVisible(pages: IntRange) {
        wanted = pages
        refresh()
    }

    fun startHandle(): ViewerTextHandle? {
        val page = pages.takeIf { !it.isEmpty() }?.first ?: return null
        val rects = pageRects[page] ?: return null
        if (ViewerTextRects.count(rects) == 0) return null
        val rect = ViewerTextRects.rect(rects, 0)
        return ViewerTextHandle(page, rect.left, rect.bottom, rect.height)
    }

    fun endHandle(): ViewerTextHandle? {
        val page = pages.takeIf { !it.isEmpty() }?.last ?: return null
        val rects = pageRects[page] ?: return null
        val count = ViewerTextRects.count(rects)
        if (count == 0) return null
        val rect = ViewerTextRects.rect(rects, count - 1)
        return ViewerTextHandle(page, rect.right, rect.bottom, rect.height)
    }

    fun beginDrag(handle: Handle): Boolean {
        val a = first ?: return false
        val b = last ?: return false
        val other = if (a.page == b.page) {
            val start = startHandle() ?: return false
            val end = endHandle() ?: return false
            if (handle == Handle.START) {
                Anchor(end.page, ViewerTextPoint(end.x, end.y - end.lineHeight / 2))
            } else {
                Anchor(start.page, ViewerTextPoint(start.x, start.y - start.lineHeight / 2))
            }
        } else {
            val (low, high) = if (a.page < b.page) a to b else b to a
            if (handle == Handle.START) high else low
        }
        fixed = other
        dragged = handle
        return true
    }

    fun dragTo(page: Int, point: ViewerTextPoint) {
        val anchor = fixed ?: return
        val doc = document ?: return
        dragTarget = DragTarget(anchor, page, point)
        if (dragJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val target = dragTarget ?: break
                dragTarget = null
                val caret = try {
                    doc.caret(target.page, target.point)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                first = target.fixed
                last = Anchor(target.page, caret ?: target.point)
                refresh()
            }
        }
        dragJob = job
        job.start()
    }

    fun endDrag() {
        fixed = null
        dragged = null
    }

    suspend fun copyText(): String? {
        val doc = document ?: return ""
        val range = pages
        if (range.isEmpty()) return ""
        if (range.last - range.first + 1 > MAX_COPY_PAGES) return null
        val text = StringBuilder()
        for (page in range) {
            val part = partOn(page) ?: continue
            val piece = try {
                doc.copy(page, part.from, part.to)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            if (page > range.first) text.append('\n')
            text.append(piece)
            if (text.length > MAX_COPY_CHARS) return null
        }
        return text.toString()
    }

    private fun partOn(page: Int): Part? {
        val a = first ?: return null
        val b = last ?: return null
        val (low, high) = if (a.page <= b.page) a to b else b to a
        if (page < low.page || page > high.page) return null
        return Part(if (page == low.page) low.point else null, if (page == high.page) high.point else null)
    }

    private fun refresh() {
        val range = pages
        pageRects.keys.filter { it !in wanted || it !in range }.forEach {
            pageRects.remove(it)
            shownParts.remove(it)
        }
        val iterator = fetching.entries.iterator()
        while (iterator.hasNext()) {
            val (page, request) = iterator.next()
            if (page !in wanted || partOn(page) != request.first) {
                request.second.cancel()
                iterator.remove()
            }
        }
        val doc = document ?: return
        for (page in wanted) {
            val part = partOn(page) ?: continue
            if (shownParts[page] == part || fetching[page]?.first == part) continue
            fetch(doc, page, part)
        }
    }

    private fun fetch(doc: ViewerDocument, page: Int, part: Part) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val rects = try {
                doc.highlight(page, part.from, part.to)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FloatArray(0)
            }
            fetching.remove(page)
            if (page in wanted && partOn(page) == part) {
                pageRects[page] = rects
                shownParts[page] = part
            }
        }
        fetching[page] = part to job
        job.start()
    }

    private fun forget() {
        dragJob?.cancel()
        dragJob = null
        dragTarget = null
        fetching.values.forEach { it.second.cancel() }
        fetching.clear()
        pageRects.clear()
        shownParts.clear()
        fixed = null
        dragged = null
        first = null
        last = null
    }

    companion object {
        const val MAX_COPY_PAGES = 1_000
        const val MAX_COPY_CHARS = 100_000
    }
}
