package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    private var document: ViewerDocument? = null
    private var openJob: Job? = null
    private var revealJob: Job? = null
    private var uncoveredHeight = Int.MAX_VALUE
    private var visibleRange = IntRange.EMPTY
    private val pageTexts = mutableStateMapOf<Int, String>()
    private val textJobs = HashMap<Int, Job>()

    val search = ViewerSearch(scope) { page, bounds -> reveal(page, bounds) }
    val selection = ViewerSelection(scope)
    val editor = ViewerEditor(scope) { pages -> surface.refresh(pages) }
    private var saveJob: Job? = null
    private var pendingResume: ViewerResume? = null

    var editSaver: (suspend (ViewerEditPlan) -> ViewerEditSaveResult)? = null
    var editMessage by mutableStateOf<ViewerEditMessage?>(null)
        private set

    init {
        editor.onFailure = { text -> editMessage = ViewerEditMessage.EraseFailed(text) }
        editor.signatures.onFailure = { text -> editMessage = ViewerEditMessage.SignatureFailed(text) }
    }

    internal val surface: ViewerSurface = ViewerSurface(
        scope,
        onVisible = { range ->
            visibleRange = range
            search.onVisible(range)
            selection.onVisible(range)
            editor.onVisible(range)
            readTexts(range)
        },
        onLayout = { stopWait() }
    )

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

    val layout: ViewerPageLayout?
        get() = surface.layout

    val viewport: ViewerViewport?
        get() = surface.viewport

    val failedPages: Set<Int>
        get() = surface.failedPages

    val scrollCount: Int
        get() = surface.scrollCount

    val isWaiting: Boolean
        get() = waitReason != null

    val currentPage: Int
        get() = surface.currentPage

    fun pageImage(page: Int): ViewerImage? = surface.pageImage(page)

    fun tilesOf(page: Int): List<Pair<ViewerTile, ViewerImage>> = surface.tilesOf(page)

    fun open(
        reason: ViewerWait.Reason,
        page: Int,
        resume: ViewerResume? = null,
        edits: (suspend () -> ViewerEditJournalRead)? = null,
        opener: suspend () -> ViewerDocument
    ) {
        openJob?.cancel()
        closeDocument()
        openError = null
        pendingResume = resume
        surface.startAt(page, resume?.spot)
        startWait(reason)
        openJob = scope.launch {
            var opened: ViewerDocument? = null
            try {
                val created = opener()
                opened = created
                val pageSizes = created.pageSizes()
                document = created
                surface.attach(created, pageSizes)
                search.attach(created)
                selection.attach(created)
                editor.attach(created)
                pendingResume?.let { resumed -> resumeEditing(resumed, edits, created.pageCount) }
                pendingResume = null
                surface.applyLayout()
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

    fun resumeState(): ViewerResume = pendingResume ?: ViewerResume(
        spot = surface.spot,
        editing = editor.isActive,
        paused = editor.paused,
        panel = editor.panel,
        rowOpen = editor.rowOpen,
        question = editor.question,
        deleting = editor.signatures.deleting,
        saving = waitReason == ViewerWait.Reason.SAVING_EDITS
    )

    private suspend fun resumeEditing(resume: ViewerResume, edits: (suspend () -> ViewerEditJournalRead)?, pageCount: Int) {
        val read = try {
            edits?.invoke() ?: ViewerEditJournalRead.None
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ViewerEditJournalRead.Unreadable
        }
        val found = (read as? ViewerEditJournalRead.Found)?.restore
        val restored = found?.takeIf { it.fits(pageCount) }
        if (canEdit && (resume.editing || restored != null)) editor.resume(resume, restored)
        val damaged = read is ViewerEditJournalRead.Unreadable || (found != null && (restored == null || !restored.complete))
        when {
            damaged -> editMessage = ViewerEditMessage.RestoreIncomplete
            resume.saving && editor.state.hasChanges -> editMessage = ViewerEditMessage.SaveInterrupted
        }
    }

    fun onSize(width: Int, height: Int, pageSpacing: Double) {
        surface.onSize(width, height, pageSpacing)
    }

    fun scrollBy(dx: Double, dy: Double) {
        surface.scrollBy(dx, dy)
    }

    fun scrollToY(y: Double) {
        surface.scrollToY(y)
    }

    fun zoomBy(factor: Float, pivotX: Double, pivotY: Double) {
        surface.zoomBy(factor, pivotX, pivotY)
    }

    fun zoomFrom(start: ViewerViewport, zoom: Float, pivotX: Double, pivotY: Double) {
        surface.zoomFrom(start, zoom, pivotX, pivotY)
    }

    fun endZoom() {
        surface.endZoom()
    }

    fun onShown() {
        search.resume()
        surface.plan()
    }

    fun onHidden() {
        if (!surface.isAttached) return
        search.pause()
        surface.hide()
    }

    fun trimMemory() {
        surface.trimMemory()
    }

    fun close() {
        saveJob?.cancel()
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

    fun startEditing() {
        if (!canEdit || isWaiting || document == null || editor.isActive) return
        selection.clear()
        if (search.isActive) search.stop()
        editor.start()
    }

    fun saveEdits() {
        val saver = editSaver ?: return
        if (isWaiting || saveJob != null || !editor.isActive) return
        editor.closePanel()
        editor.question = null
        editor.commitTyping()
        val plan = editor.plan()
        if (plan.isEmpty) {
            editMessage = ViewerEditMessage.NothingToSave
            return
        }
        editor.cancelGesture()
        startWait(ViewerWait.Reason.SAVING_EDITS)
        val page = currentPage
        saveJob = scope.launch {
            try {
                when (val result = saver(plan)) {
                    is ViewerEditSaveResult.Saved -> open(ViewerWait.Reason.OPENING_EDITED, page, opener = result.opener)
                    ViewerEditSaveResult.Unchanged -> {
                        stopWait()
                        editor.discard()
                        editMessage = ViewerEditMessage.NothingToSave
                    }
                    is ViewerEditSaveResult.Failed -> {
                        stopWait()
                        editMessage = ViewerEditMessage.SaveFailed(result.message)
                    }
                }
            } catch (e: CancellationException) {
                if (waitReason == ViewerWait.Reason.SAVING_EDITS) stopWait()
                throw e
            } finally {
                saveJob = null
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
    }

    fun messageShown() {
        editMessage = null
    }

    fun handleBack(): Boolean = when {
        editor.isActive -> {
            when {
                editor.signatures.pad?.isDrawing == true -> Unit
                editor.signatures.pad != null -> editor.signatures.cancelPad()
                editor.signatures.deleting != null -> editor.signatures.dismissDelete()
                editor.question != null -> editor.question = null
                editor.panel != null || editor.rowOpen -> editor.closePanel()
                editor.typing != null -> editor.commitTyping()
                editor.state.hasChanges -> editor.question = ViewerEditQuestion.EXIT
                else -> editor.discard()
            }
            true
        }
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

    fun screenRect(page: Int, box: Rect): Rect? = surface.screenRect(page, box)

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

    fun pagePoint(x: Float, y: Float): Pair<Int, ViewerTextPoint>? = surface.pagePoint(x, y)

    fun labeledPages(): Set<Int> {
        val pages = layout ?: return emptySet()
        val view = viewport ?: return emptySet()
        return pages.labelPages(view.contentTop, view.contentTop + view.contentHeight).toSet()
    }

    fun pageHit(x: Float, y: Float): Pair<Int, ViewerTextPoint>? = surface.pageHit(x, y)

    fun pointOn(page: Int, x: Float, y: Float): ViewerTextPoint? = surface.pointOn(page, x, y)

    fun reveal(page: Int, box: Rect) {
        surface.reveal(page, box, uncoveredHeight)
    }

    private fun closeDocument() {
        val doc = surface.detach()
        document = null
        search.attach(null)
        selection.attach(null)
        editor.attach(null)
        dropTexts()
        visibleRange = IntRange.EMPTY
        doc?.close()
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
}

sealed interface ViewerEditSaveResult {
    class Saved(val opener: suspend () -> ViewerDocument) : ViewerEditSaveResult

    data object Unchanged : ViewerEditSaveResult

    class Failed(val message: String) : ViewerEditSaveResult
}

sealed interface ViewerEditMessage {
    data object NothingToSave : ViewerEditMessage

    class SaveFailed(val text: String) : ViewerEditMessage

    class EraseFailed(val text: String) : ViewerEditMessage

    class SignatureFailed(val text: String) : ViewerEditMessage

    data object RestoreIncomplete : ViewerEditMessage

    data object SaveInterrupted : ViewerEditMessage
}
