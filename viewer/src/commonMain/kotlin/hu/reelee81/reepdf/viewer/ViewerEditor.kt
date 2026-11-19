package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ViewerEditor(private val scope: CoroutineScope, private val onPagesChanged: (Set<Int>?) -> Unit) {
    private var document: ViewerDocument? = null
    private var source: ViewerMarkSource? = null
    private val session = ViewerEditSession()
    private val loaded = HashMap<Int, ViewerPageMarks>()
    private val loading = HashMap<Int, Job>()
    private val applied = HashMap<Int, ViewerStreamEdit>()
    private val mutex = Mutex()
    private var visible = IntRange.EMPTY
    private var appliedBlankAll = false
    private var stroke: Stroke? = null
    private var eraser: Eraser? = null
    private var pendingSaved = emptySet<ViewerEditSession.SavedRef>()

    var onSettingsChanged: (ViewerEditSettings) -> Unit = {}
    var onFailure: (String) -> Unit = {}

    var isActive by mutableStateOf(false)
        private set
    var settings by mutableStateOf(ViewerEditSettings())
        private set
    var state by mutableStateOf(ViewerEditSession.State.EMPTY)
        private set
    var wet by mutableStateOf<ViewerMark?>(null)
        private set
    var panel by mutableStateOf<ViewerEditPanel?>(null)
        private set
    var question by mutableStateOf<ViewerEditQuestion?>(null)
    var paused by mutableStateOf(false)
        private set
    var rowOpen by mutableStateOf(false)
        private set
    private var pendingMarks by mutableStateOf(emptySet<ViewerMark>())

    fun restore(saved: ViewerEditSettings) {
        settings = saved.sanitized()
    }

    fun attach(document: ViewerDocument?) {
        reset()
        this.document = document
        source = document as? ViewerMarkSource
    }

    fun start() {
        if (isActive || source == null) return
        isActive = true
        load(visible)
    }

    fun onVisible(range: IntRange) {
        visible = range
        if (isActive) load(range)
    }

    fun addedMarks(page: Int): List<ViewerMark> {
        val pending = pendingMarks
        return state.added[page]?.filter { it !in pending }.orEmpty()
    }

    fun togglePause() {
        cancelGesture()
        closePanel()
        paused = !paused
    }

    fun tapTool(tool: ViewerEditTool) {
        if (!tool.enabled) return
        if (tool == ViewerEditTool.ERASER) rowOpen = false
        if (tool == settings.tool) {
            if (tool.palette != null) togglePanel(ViewerEditPanel.WIDTH)
        } else {
            panel = null
            selectTool(tool)
        }
    }

    fun tapGroup() {
        panel = null
        if (settings.tool == ViewerEditTool.ERASER) {
            selectTool(settings.drawingTool)
            return
        }
        rowOpen = !rowOpen
    }

    fun tapSwatch() {
        rowOpen = false
        if (settings.tool.palette != null) togglePanel(ViewerEditPanel.COLORS)
    }

    fun closePanel() {
        panel = null
        rowOpen = false
    }

    private fun togglePanel(chosen: ViewerEditPanel) {
        panel = if (panel == chosen) null else chosen
    }

    fun selectTool(tool: ViewerEditTool) = update(settings.withTool(tool))

    fun chooseColor(index: Int) = update(settings.withColor(settings.swatchTool, index))

    fun chooseWidth(index: Int) = update(settings.withWidth(settings.swatchTool, index))

    fun placeUndoPanel(top: Boolean) = update(settings.copy(undoPanelTop = top))

    fun beginStroke(page: Int, point: ViewerTextPoint, minDistance: Float, textTolerance: Float) {
        if (!isActive || paused) return
        cancelGesture()
        val tool = settings.tool
        if (tool != ViewerEditTool.PEN && tool != ViewerEditTool.HIGHLIGHTER) return
        val started = Stroke(page, tool, settings.color(tool), settings.width(tool), ViewerStrokeBuilder(minDistance))
        started.builder.add(point.x, point.y)
        stroke = started
        if (tool == ViewerEditTool.PEN) {
            started.mode = Mode.FREE
            wet = started.freehand()
        } else {
            wet = started.freehand()
            decide(started, point, textTolerance)
        }
    }

    fun extendStroke(point: ViewerTextPoint) {
        val current = stroke ?: return
        current.last = point
        if (!current.builder.add(point.x, point.y)) return
        when (current.mode) {
            Mode.FREE -> wet = current.freehand()
            Mode.TEXT -> requestRects(current, point)
            Mode.UNDECIDED -> wet = current.freehand()
        }
    }

    fun endStroke() {
        val current = stroke ?: return
        current.last?.let { current.builder.finish(it.x, it.y) }
        current.ending = true
        when (current.mode) {
            Mode.FREE -> commit(current)
            Mode.TEXT -> if (current.job?.isActive != true) commit(current)
            Mode.UNDECIDED -> Unit
        }
    }

    fun cancelGesture() {
        stroke?.job?.cancel()
        stroke = null
        wet = null
        eraser = null
        pendingMarks = emptySet()
        if (pendingSaved.isNotEmpty()) {
            pendingSaved = emptySet()
            sync()
        }
    }

    fun beginErase(page: Int, point: ViewerTextPoint, tolerance: Float) {
        if (!isActive || paused) return
        cancelGesture()
        eraser = Eraser(page, point, tolerance)
        eraseAt(page, point, tolerance)
    }

    fun extendErase(page: Int, point: ViewerTextPoint) {
        val current = eraser ?: return
        if (page == current.page) {
            for ((x, y) in ViewerMarkHit.sweep(current.last.x, current.last.y, point.x, point.y, current.tolerance)) {
                eraseAt(page, ViewerTextPoint(x, y), current.tolerance)
            }
        } else {
            eraseAt(page, point, current.tolerance)
        }
        current.page = page
        current.last = point
    }

    fun endErase() {
        if (eraser == null) return
        val marks = pendingMarks
        val saved = pendingSaved
        eraser = null
        if (marks.isEmpty() && saved.isEmpty()) return
        state = session.perform(ViewerEditSession.Action.Erase(marks, saved))
        pendingMarks = emptySet()
        pendingSaved = emptySet()
    }

    fun eraseVisible(pages: Set<Int>) {
        if (!isActive || pages.isEmpty()) return
        cancelGesture()
        val anything = pages.any { page ->
            state.added[page].orEmpty().isNotEmpty() || (!state.isBlank(page) && loaded[page]?.streams?.isNotEmpty() == true)
        }
        if (!anything) return
        state = session.perform(ViewerEditSession.Action.Blank(pages))
        sync()
    }

    fun eraseAll() {
        if (!isActive || (state.blankAll && state.added.isEmpty())) return
        cancelGesture()
        state = session.perform(ViewerEditSession.Action.Blank(null))
        sync()
    }

    fun undo() {
        if (!state.canUndo) return
        cancelGesture()
        state = session.undo()
        sync()
    }

    fun redo() {
        if (!state.canRedo) return
        cancelGesture()
        state = session.redo()
        sync()
    }

    fun plan(): ViewerEditPlan = state.plan()

    fun discard() {
        cancelGesture()
        paused = false
        rowOpen = false
        panel = null
        question = null
        state = session.clear()
        isActive = false
        sync()
    }

    fun saved() {
        cancelGesture()
        paused = false
        rowOpen = false
        panel = null
        question = null
        state = session.clear()
        isActive = false
    }

    private fun update(next: ViewerEditSettings) {
        if (next == settings) return
        settings = next
        onSettingsChanged(next)
    }

    private fun decide(current: Stroke, start: ViewerTextPoint, tolerance: Float) {
        val doc = document
        if (doc == null) {
            current.mode = Mode.FREE
            return
        }
        current.job = scope.launch {
            val anchor = try {
                doc.word(current.page, start, tolerance)?.let { word -> doc.caret(current.page, start) ?: word.from }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (stroke !== current) return@launch
            if (anchor == null) {
                current.mode = Mode.FREE
                wet = current.freehand()
                if (current.ending) commit(current)
                return@launch
            }
            current.mode = Mode.TEXT
            current.anchor = anchor
            wet = null
            current.job = null
            val last = current.last
            if (last != null) requestRects(current, last) else if (current.ending) commit(current)
        }
    }

    private fun requestRects(current: Stroke, point: ViewerTextPoint) {
        current.target = point
        if (current.job?.isActive == true) return
        val doc = document ?: return
        val anchor = current.anchor ?: return
        current.job = scope.launch {
            while (true) {
                val target = current.target ?: break
                current.target = null
                val rects = try {
                    doc.caret(current.page, target)?.let { caret -> doc.highlight(current.page, anchor, caret) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (stroke !== current) return@launch
                current.rects = rects
                wet = current.textMark()
            }
            if (stroke === current && current.ending) commit(current)
        }
    }

    private fun commit(current: Stroke) {
        if (stroke !== current) return
        stroke = null
        val mark = when (current.mode) {
            Mode.FREE, Mode.UNDECIDED -> current.freehand()
            Mode.TEXT -> current.textMark()
        }
        wet = null
        if (mark != null) state = session.perform(ViewerEditSession.Action.Add(mark))
    }

    private fun eraseAt(page: Int, point: ViewerTextPoint, tolerance: Float) {
        val hitMarks = addedMarks(page).filter { ViewerMarkHit.hits(it.kind, it.path, it.width, point.x, point.y, tolerance) }
        if (hitMarks.isNotEmpty()) pendingMarks = pendingMarks + hitMarks
        val marks = loaded[page] ?: return
        val found = LinkedHashSet<ViewerEditSession.SavedRef>()
        for (saved in marks.marks) {
            val ref = ViewerEditSession.SavedRef(page, saved.stream, saved.id)
            if (ref in pendingSaved || ref in found || !state.isVisible(ref)) continue
            if (ViewerMarkHit.within(saved.clip, point.x, point.y, tolerance) &&
                ViewerMarkHit.hits(saved.kind, saved.path, saved.width, point.x, point.y, tolerance)
            ) {
                marks.marks.filter { it.id == saved.id }.forEach { found += ViewerEditSession.SavedRef(page, it.stream, it.id) }
            }
        }
        if (found.isEmpty()) return
        pendingSaved = pendingSaved + found
        sync()
    }

    private fun desired(page: Int, stream: Int): ViewerStreamEdit {
        val base = state.streamEdit(page, stream)
        if (base.blank) return base
        val extra = pendingSaved.filter { it.stream == stream }.map { it.id }
        return if (extra.isEmpty()) base else ViewerStreamEdit(false, base.erased + extra)
    }

    private fun sync() {
        val src = source ?: return
        scope.launch {
            mutex.withLock {
                if (source !== src) return@withLock
                val diff = LinkedHashMap<Int, ViewerStreamEdit>()
                val pages = HashSet<Int>()
                for ((page, marks) in loaded) {
                    for (stream in marks.streams) {
                        val wanted = desired(page, stream)
                        if (wanted != (applied[stream] ?: ViewerStreamEdit.ORIGINAL)) {
                            diff[stream] = wanted
                            pages += page
                        }
                    }
                }
                val blankAll = state.blankAll
                val refreshAll = blankAll != appliedBlankAll
                if (diff.isEmpty() && !refreshAll) return@withLock
                try {
                    if (diff.isNotEmpty()) src.editStreams(diff)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (source === src) onFailure(e.message ?: e::class.simpleName.orEmpty())
                    return@withLock
                }
                if (source !== src) return@withLock
                for ((stream, edit) in diff) if (edit.isOriginal) applied.remove(stream) else applied[stream] = edit
                appliedBlankAll = blankAll
                onPagesChanged(if (refreshAll) null else pages)
            }
        }
    }

    private fun load(range: IntRange) {
        val src = source ?: return
        for (page in range) {
            if (page in loaded || page in loading) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    mutex.withLock {
                        if (source !== src) return@withLock
                        val blank = state.isBlank(page)
                        val marks = try {
                            src.pageMarks(page, blank)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ViewerPageMarks.NONE
                        }
                        if (source !== src) return@withLock
                        loaded[page] = marks
                        if (blank) {
                            marks.streams.forEach { applied[it] = ViewerStreamEdit.BLANK }
                            if (marks.streams.isNotEmpty()) onPagesChanged(setOf(page))
                        }
                    }
                } finally {
                    if (source === src) loading.remove(page)
                }
                sync()
            }
            loading[page] = job
            job.start()
        }
    }

    private fun reset() {
        loading.values.forEach { it.cancel() }
        loading.clear()
        loaded.clear()
        applied.clear()
        appliedBlankAll = false
        stroke?.job?.cancel()
        stroke = null
        eraser = null
        wet = null
        pendingMarks = emptySet()
        pendingSaved = emptySet()
        state = session.clear()
        isActive = false
        paused = false
        rowOpen = false
        panel = null
        question = null
        document = null
        source = null
    }

    private enum class Mode { UNDECIDED, FREE, TEXT }

    private class Stroke(
        val page: Int,
        val tool: ViewerEditTool,
        val color: Int,
        val width: Float,
        val builder: ViewerStrokeBuilder
    ) {
        var mode = Mode.UNDECIDED
        var ending = false
        var last: ViewerTextPoint? = null
        var anchor: ViewerTextPoint? = null
        var target: ViewerTextPoint? = null
        var rects: FloatArray? = null
        var job: Job? = null

        fun freehand(): ViewerMark {
            val kind = if (tool == ViewerEditTool.PEN) ViewerMarkKind.PEN else ViewerMarkKind.HIGHLIGHTER
            return ViewerMark(page, kind, color, width, builder.path())
        }

        fun textMark(): ViewerMark? {
            val shown = rects ?: return null
            if (ViewerTextRects.count(shown) == 0) return null
            return ViewerMark(page, ViewerMarkKind.TEXT_HIGHLIGHT, color, 0f, ViewerMarkPath.rects(shown))
        }
    }

    private class Eraser(var page: Int, var last: ViewerTextPoint, val tolerance: Float)
}

enum class ViewerEditPanel { COLORS, WIDTH }

enum class ViewerEditQuestion { EXIT, ERASE_ALL }
