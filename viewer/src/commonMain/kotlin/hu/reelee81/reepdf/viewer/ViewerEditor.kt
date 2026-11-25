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

    val signatures = ViewerSignatures(scope)
    private var signatureDrag: SignatureDrag? = null

    var textFonts by mutableStateOf<ViewerTextFonts?>(null)
    private var textDrag: TextDrag? = null
    private var weightDrag: ViewerTypingDraft? = null

    var journal: ViewerEditJournal? = null
    private var journalLive = 0L
    private var journalDead = 0L
    private var journalPad = 0L
    private var journalTyping = 0L

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
    var framed by mutableStateOf<ViewerFramedSignature?>(null)
        private set
    var movedSignature by mutableStateOf<ViewerMark?>(null)
        private set
    var typing by mutableStateOf<ViewerTypingDraft?>(null)
        private set
    var draggedText by mutableStateOf<ViewerMark?>(null)
        private set
    private var pendingMarks by mutableStateOf(emptySet<ViewerMark>())

    init {
        signatures.onRecord = ::recordPad
    }

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
        signatures.load()
        load(visible)
    }

    internal fun resume(resume: ViewerResume, restored: ViewerEditRestore?) {
        if (source == null) return
        if (restored != null) {
            state = session.restore(restored.actions, restored.position)
            restored.pad?.let(signatures::restorePad)
            typing = restored.typing
        }
        start()
        paused = resume.paused
        panel = resume.panel
        rowOpen = resume.rowOpen
        question = resume.question
        resume.deleting?.let(signatures::restoreDeleting)
        if (restored != null) rewriteJournal()
    }

    fun onVisible(range: IntRange) {
        visible = range
        if (isActive) load(range)
    }

    val canUndo: Boolean
        get() = state.canUndo || typingStep

    val canRedo: Boolean
        get() = state.canRedo && !typingStep

    private val typingStep: Boolean
        get() {
            val open = typing ?: return false
            return if (open.mark.text?.text.isNullOrBlank()) !open.isNew else open.isNew || open.changed
        }

    fun addedMarks(page: Int): List<ViewerMark> {
        val pending = pendingMarks
        val edited = typing?.editing.orEmpty()
        return state.added[page]?.filter { it !in pending && it !in edited }.orEmpty()
    }

    fun togglePause() {
        commitTyping()
        releaseSignature()
        cancelGesture()
        closePanel()
        paused = !paused
    }

    fun tapTool(tool: ViewerEditTool) {
        if (!tool.enabled) return
        if (tool != settings.tool) commitTyping()
        if (tool == ViewerEditTool.ERASER) rowOpen = false
        if (tool == settings.tool) {
            when {
                tool.palette?.hasWidths == true -> togglePanel(ViewerEditPanel.WIDTH)
                tool == ViewerEditTool.UNDERLINE -> togglePanel(ViewerEditPanel.STYLE)
                tool == ViewerEditTool.SIGNATURE -> togglePanel(ViewerEditPanel.SIGNATURES)
            }
        } else {
            panel = null
            selectTool(tool)
        }
    }

    fun tapGroup() {
        releaseSignature()
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
        releaseSignature()
        panel = if (panel == chosen) null else chosen
    }

    fun selectTool(tool: ViewerEditTool) {
        if (tool != settings.tool) {
            commitTyping()
            releaseSignature()
        }
        update(settings.withTool(tool))
    }

    fun chooseColor(index: Int) {
        update(settings.withColor(settings.swatchTool, index))
        val open = typing ?: return
        if (settings.swatchTool != ViewerEditTool.TEXT) return
        val color = settings.color(ViewerEditTool.TEXT)
        if (color != open.mark.color) showStepped(open.copy(mark = ViewerTypedPlacement.recolored(open.mark, color), changed = true))
    }

    fun beginWidthChoice() {
        weightDrag = typing?.takeIf { settings.swatchTool == ViewerEditTool.TEXT }
    }

    fun chooseWidth(index: Int) {
        update(settings.withWidth(settings.swatchTool, index))
        val open = typing ?: return
        val fonts = textFonts ?: return
        if (settings.swatchTool != ViewerEditTool.TEXT) return
        val weight = settings.weight
        val drag = weightDrag
        if (drag != null) {
            if (weight == drag.mark.text?.weight) showTyping(drag) else showTyping(reweighted(drag, weight, fonts))
            return
        }
        if (weight == open.mark.text?.weight) return
        showStepped(reweighted(open, weight, fonts))
    }

    fun endWidthChoice() {
        val drag = weightDrag ?: return
        weightDrag = null
        val open = typing ?: return
        if (open !== drag && open.mark.text?.weight != drag.mark.text?.weight) showStepped(open)
    }

    private fun reweighted(draft: ViewerTypingDraft, weight: Int, fonts: ViewerTextFonts): ViewerTypingDraft {
        val mark = ViewerTypedPlacement.reweighted(draft.mark, fonts.font(weight), weight, draft.pageWidth, draft.pageHeight)
        return draft.copy(mark = mark, cursor = draft.cursor.coerceAtMost(mark.text?.text?.length ?: 0), changed = true)
    }

    fun shownColor(tool: ViewerEditTool): Int = openText(tool)?.mark?.color ?: settings.color(tool)

    fun shownColorIndex(tool: ViewerEditTool): Int {
        val open = openText(tool) ?: return settings.colorIndex(tool)
        val palette = tool.palette ?: return NOT_IN_PALETTE
        return (0 until palette.size).firstOrNull { palette.color(it) == open.mark.color } ?: NOT_IN_PALETTE
    }

    fun shownWidthIndex(tool: ViewerEditTool): Int {
        val weight = openText(tool)?.mark?.text?.weight ?: return settings.widthIndex(tool)
        return ViewerMarkText.WEIGHTS.indexOf(weight).takeIf { it >= 0 } ?: settings.widthIndex(tool)
    }

    private fun openText(tool: ViewerEditTool): ViewerTypingDraft? = typing?.takeIf { tool == ViewerEditTool.TEXT }

    private fun rememberLook(mark: ViewerMark) {
        val text = mark.text ?: return
        val palette = ViewerEditPalettes.text
        val color = (0 until palette.size).firstOrNull { palette.color(it) == mark.color } ?: settings.textColor
        val weight = ViewerMarkText.WEIGHTS.indexOf(text.weight).takeIf { it >= 0 } ?: settings.textWeight
        update(settings.copy(textColor = color, textWeight = weight))
    }

    fun chooseUnderlineStyle(style: ViewerUnderlineStyle) = update(settings.copy(underlineStyle = style))

    fun placeUndoPanel(top: Boolean) = update(settings.copy(undoPanelTop = top))

    val chosenSignature: Int?
        get() = signatures.chosen(settings.signatureSlot)

    fun chooseSignature(slot: Int) {
        if (signatures.saved.getOrNull(slot) != null) update(settings.copy(signatureSlot = slot))
    }

    fun openSignaturePad(slot: Int) = signatures.openPad(slot, settings.signatureWidth)

    fun editSignaturePad(slot: Int) = signatures.editPad(slot)

    fun chooseSignatureWidth(index: Int) {
        val pad = signatures.pad ?: return
        pad.chooseWidth(index)
        update(settings.copy(signatureWidth = pad.widthIndex))
    }

    fun confirmSignaturePad() {
        val slot = signatures.confirmPad() ?: return
        update(settings.copy(signatureSlot = slot))
    }

    fun tapSignature(page: Int, point: ViewerTextPoint, pageWidth: Float, pageHeight: Float, tolerance: Float) {
        if (!isActive || paused || settings.tool != ViewerEditTool.SIGNATURE) return
        if (framed != null) {
            releaseSignature()
            return
        }
        cancelGesture()
        val found = signatureAt(page, point, tolerance)
        if (found != null) {
            framed = found
            return
        }
        val signature = chosenSignature?.let { signatures.saved[it] }
        if (signature == null) {
            panel = ViewerEditPanel.SIGNATURES
            return
        }
        val mark = ViewerSignaturePlacement.place(signature, settings.color(ViewerEditTool.SIGNATURE), page, point, pageWidth, pageHeight)
        perform(ViewerEditSession.Action.Add(mark))
        framed = ViewerFramedSignature(mark, emptySet())
    }

    fun grabSignature(page: Int, point: ViewerTextPoint, handleReach: Float, tolerance: Float, margin: Float): Boolean {
        val current = framed ?: return false
        if (!isActive || paused || current.mark.page != page) return false
        val frame = ViewerSignaturePlacement.framed(current.box, margin)
        val resize = ViewerSignaturePlacement.onHandle(frame, point, handleReach)
        if (!resize && !ViewerSignaturePlacement.inside(frame, point, tolerance)) return false
        cancelGesture()
        signatureDrag = SignatureDrag(current, point, resize)
        movedSignature = current.mark
        if (current.saved.isEmpty()) {
            pendingMarks = setOf(current.mark)
        } else {
            pendingSaved = current.saved
            sync()
        }
        return true
    }

    fun dragSignature(point: ViewerTextPoint, pageWidth: Float, pageHeight: Float) {
        val drag = signatureDrag ?: return
        val base = drag.framed
        movedSignature = if (drag.resize) {
            val corner = ViewerTextPoint(base.box[2] + point.x - drag.start.x, base.box[3] + point.y - drag.start.y)
            ViewerSignaturePlacement.resized(base.mark, base.box, corner, pageWidth, pageHeight)
        } else {
            ViewerSignaturePlacement.moved(base.mark, base.box, point.x - drag.start.x, point.y - drag.start.y, pageWidth, pageHeight)
        }
    }

    fun endSignatureDrag() {
        val drag = signatureDrag ?: return
        val moved = movedSignature
        val base = drag.framed
        signatureDrag = null
        movedSignature = null
        pendingMarks = emptySet()
        val hidSaved = pendingSaved.isNotEmpty()
        pendingSaved = emptySet()
        val same = moved != null && moved.width == base.mark.width && moved.path.points.contentEquals(base.mark.path.points)
        if (moved != null && moved !== base.mark && !same) {
            val unsaved = if (base.saved.isEmpty()) setOf(base.mark) else emptySet()
            perform(ViewerEditSession.Action.Replace(unsaved, base.saved, moved))
            framed = ViewerFramedSignature(moved, emptySet())
        }
        if (hidSaved) sync()
    }

    fun releaseSignature() {
        if (signatureDrag != null) cancelGesture()
        framed = null
    }

    private fun signatureAt(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerFramedSignature? {
        addedMarks(page).lastOrNull { it.kind == ViewerMarkKind.SIGNATURE && ViewerSignaturePlacement.inside(ViewerSignaturePlacement.box(it.path, it.width), point, tolerance) }
            ?.let { return ViewerFramedSignature(it, emptySet()) }
        val marks = loaded[page] ?: return null
        val hit = marks.marks.lastOrNull { saved ->
            saved.kind == ViewerMarkKind.SIGNATURE &&
                state.isVisible(ViewerEditSession.SavedRef(page, saved.stream, saved.id)) &&
                ViewerMarkHit.within(saved.clip, point.x, point.y, tolerance) &&
                ViewerSignaturePlacement.inside(ViewerSignaturePlacement.box(saved.path, saved.width), point, tolerance)
        } ?: return null
        val refs = marks.marks.filter { it.id == hit.id }
            .map { ViewerEditSession.SavedRef(page, it.stream, it.id) }
            .filter(state::isVisible)
            .toSet()
        return ViewerFramedSignature(ViewerMark(page, ViewerMarkKind.SIGNATURE, hit.color, hit.width, hit.path), refs)
    }

    fun tapText(page: Int, point: ViewerTextPoint, pageWidth: Float, pageHeight: Float, tolerance: Float) {
        if (!isActive || paused || settings.tool != ViewerEditTool.TEXT) return
        val fonts = textFonts ?: return
        val open = typing
        if (open != null) {
            val text = open.mark.text ?: return
            val box = ViewerTypedPlacement.shown(text, fonts.font(text.weight), open.pageWidth, open.pageHeight)
            if (open.mark.page == page && ViewerSignaturePlacement.inside(box, point, tolerance)) {
                placeCaret(page, point)
            } else {
                commitTyping()
            }
            return
        }
        cancelGesture()
        val found = textAt(page, point, tolerance, fonts, pageWidth, pageHeight)
        if (found != null) {
            rememberLook(found.mark)
            showTyping(found)
            return
        }
        val weight = settings.weight
        val mark = ViewerTypedPlacement.placed(
            page, settings.color(ViewerEditTool.TEXT), "", weight, ViewerMarkText.DEFAULT_SIZE, point, fonts.font(weight), pageWidth, pageHeight
        )
        showTyping(ViewerTypingDraft(mark, 0, pageWidth, pageHeight))
    }

    fun typeText(text: String, cursor: Int) {
        val open = typing ?: return
        val fonts = textFonts ?: return
        val current = open.mark.text ?: return
        val font = fonts.font(current.weight)
        val filtered = ViewerTypedLayout.accepted(font, text)
        val typedCaret = ViewerTypedLayout.accepted(font, text.take(cursor.coerceIn(0, text.length))).length.coerceAtMost(filtered.length)
        val (accepted, caret) = ViewerTypedLayout.limited(current.text, filtered, typedCaret)
        if (accepted == current.text) {
            if (caret != open.cursor) showTyping(open.copy(cursor = caret))
            return
        }
        val mark = ViewerTypedPlacement.edited(open.mark, font, accepted, open.pageWidth, open.pageHeight)
        showTyping(open.copy(mark = mark, cursor = caret, changed = true))
    }

    fun placeCaret(page: Int, point: ViewerTextPoint) {
        val open = typing ?: return
        val fonts = textFonts ?: return
        val text = open.mark.text ?: return
        if (open.mark.page != page) return
        val caret = ViewerTypedLayout.caretAt(fonts.font(text.weight), text, point)
        if (caret != open.cursor) showTyping(open.copy(cursor = caret))
    }

    fun moveCaret(cursor: Int) {
        val open = typing ?: return
        val length = open.mark.text?.text?.length ?: return
        val caret = cursor.coerceIn(0, length)
        if (caret != open.cursor) showTyping(open.copy(cursor = caret))
    }

    fun grabText(page: Int, point: ViewerTextPoint, handleReach: Float, tolerance: Float, margin: Float): Boolean {
        val open = typing ?: return false
        val fonts = textFonts ?: return false
        val text = open.mark.text ?: return false
        if (!isActive || paused || open.mark.page != page) return false
        val frame = ViewerSignaturePlacement.framed(ViewerTypedPlacement.shown(text, fonts.font(text.weight), open.pageWidth, open.pageHeight), margin)
        val resize = ViewerSignaturePlacement.onHandle(frame, point, handleReach)
        if (!resize && !ViewerSignaturePlacement.inside(frame, point, tolerance)) return false
        cancelGesture()
        textDrag = TextDrag(open, point, resize)
        draggedText = open.mark
        return true
    }

    fun dragText(point: ViewerTextPoint) {
        val drag = textDrag ?: return
        val fonts = textFonts ?: return
        val base = drag.draft
        val text = base.mark.text ?: return
        val font = fonts.font(text.weight)
        val dx = point.x - drag.start.x
        val dy = point.y - drag.start.y
        draggedText = if (drag.resize) {
            val frame = ViewerTypedPlacement.shown(text, font, base.pageWidth, base.pageHeight)
            ViewerTypedPlacement.resized(base.mark, font, ViewerTextPoint(frame[2] + dx, frame[3] + dy), base.pageWidth, base.pageHeight)
        } else {
            ViewerTypedPlacement.moved(base.mark, font, dx, dy, base.pageWidth, base.pageHeight)
        }
    }

    fun endTextDrag() {
        val drag = textDrag ?: return
        val moved = draggedText
        textDrag = null
        draggedText = null
        if (moved == null || moved === drag.draft.mark || typing !== drag.draft) return
        val before = drag.draft.mark.text
        val after = moved.text
        if (before != null && after != null && before.size == after.size && before.matrix.contentEquals(after.matrix)) return
        showStepped(drag.draft.copy(mark = moved, changed = true))
    }

    fun commitTyping() {
        val open = typing ?: return
        textDrag = null
        weightDrag = null
        draggedText = null
        val text = open.mark.text?.text.orEmpty()
        val step = when {
            text.isBlank() -> if (open.isNew) null else ViewerEditSession.Action.Erase(open.editing, open.saved)
            open.isNew -> ViewerEditSession.Action.Add(open.mark)
            open.changed -> ViewerEditSession.Action.Replace(open.editing, open.saved, open.mark)
            else -> null
        }
        showTyping(null, step)
    }

    private fun showTyping(draft: ViewerTypingDraft?, step: ViewerEditSession.Action? = null) {
        val hidden = typing?.saved.orEmpty()
        val refs = step?.let(::performed)
        typing = draft
        val target = journal
        if (target != null) {
            val box = typingRecordFor(draft)
            target.append(if (step == null || refs == null) box else ViewerEditRecord.Performed(step, refs, box))
            compactJournal()
        }
        if (hidden != draft?.saved.orEmpty()) sync()
    }

    private fun showStepped(draft: ViewerTypingDraft) {
        if (draft.mark.text?.text.isNullOrBlank()) {
            showTyping(draft)
            return
        }
        val step = if (draft.isNew) {
            ViewerEditSession.Action.Add(draft.mark)
        } else {
            ViewerEditSession.Action.Replace(draft.editing, draft.saved, draft.mark)
        }
        showTyping(draft.copy(editing = setOf(draft.mark), saved = emptySet(), changed = false), step)
    }

    private fun textAt(
        page: Int,
        point: ViewerTextPoint,
        tolerance: Float,
        fonts: ViewerTextFonts,
        pageWidth: Float,
        pageHeight: Float
    ): ViewerTypingDraft? {
        fun frameOf(text: ViewerMarkText) = ViewerTypedPlacement.frame(text, fonts.font(text.weight))
        addedMarks(page).lastOrNull { mark ->
            val text = mark.text
            mark.kind == ViewerMarkKind.TYPED && text != null && ViewerSignaturePlacement.inside(frameOf(text), point, tolerance)
        }?.let { mark ->
            val text = requireNotNull(mark.text)
            return ViewerTypingDraft(mark, ViewerTypedLayout.caretAt(fonts.font(text.weight), text, point), pageWidth, pageHeight, editing = setOf(mark))
        }
        val marks = loaded[page] ?: return null
        val hit = marks.marks.lastOrNull { saved ->
            val text = saved.text
            saved.kind == ViewerMarkKind.TYPED && text != null &&
                state.isVisible(ViewerEditSession.SavedRef(page, saved.stream, saved.id)) &&
                ViewerMarkHit.within(saved.clip, point.x, point.y, tolerance) &&
                ViewerSignaturePlacement.inside(frameOf(text), point, tolerance)
        } ?: return null
        val text = requireNotNull(hit.text)
        val refs = marks.marks.filter { it.id == hit.id }
            .map { ViewerEditSession.SavedRef(page, it.stream, it.id) }
            .filter(state::isVisible)
            .toSet()
        val mark = ViewerMark(page, ViewerMarkKind.TYPED, hit.color, 0f, hit.path, text)
        return ViewerTypingDraft(mark, ViewerTypedLayout.caretAt(fonts.font(text.weight), text, point), pageWidth, pageHeight, saved = refs)
    }

    fun beginStroke(page: Int, point: ViewerTextPoint, minDistance: Float, textTolerance: Float) {
        if (!isActive || paused) return
        cancelGesture()
        val tool = settings.tool
        if (!tool.draws) return
        val wavy = tool == ViewerEditTool.UNDERLINE && settings.underlineStyle == ViewerUnderlineStyle.WAVY
        val started = Stroke(page, tool, settings.color(tool), settings.width(tool), wavy, ViewerStrokeBuilder(minDistance))
        started.builder.add(point.x, point.y)
        stroke = started
        if (tool == ViewerEditTool.PEN) {
            started.mode = Mode.FREE
            wet = started.freehand()
        } else {
            if (!tool.onlyOnText) wet = started.freehand()
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
            Mode.UNDECIDED -> if (!current.tool.onlyOnText) wet = current.freehand()
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
        signatureDrag = null
        movedSignature = null
        textDrag = null
        draggedText = null
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
        perform(ViewerEditSession.Action.Erase(marks, saved))
        pendingMarks = emptySet()
        pendingSaved = emptySet()
    }

    fun eraseVisible(pages: Set<Int>) {
        if (!isActive || pages.isEmpty()) return
        commitTyping()
        releaseSignature()
        cancelGesture()
        val anything = pages.any { page ->
            state.added[page].orEmpty().isNotEmpty() || (!state.isBlank(page) && loaded[page]?.streams?.isNotEmpty() == true)
        }
        if (!anything) return
        perform(ViewerEditSession.Action.Blank(pages))
        sync()
    }

    fun eraseAll() {
        if (!isActive || (state.blankAll && state.added.isEmpty())) return
        commitTyping()
        releaseSignature()
        cancelGesture()
        perform(ViewerEditSession.Action.Blank(null))
        sync()
    }

    fun undo() {
        commitTyping()
        if (!state.canUndo) return
        releaseSignature()
        cancelGesture()
        moved(session.undo())
        sync()
    }

    fun redo() {
        commitTyping()
        if (!state.canRedo) return
        releaseSignature()
        cancelGesture()
        moved(session.redo())
        sync()
    }

    fun plan(): ViewerEditPlan = state.plan()

    fun discard() {
        cancelGesture()
        typing = null
        framed = null
        signatures.close()
        paused = false
        rowOpen = false
        panel = null
        question = null
        state = session.clear()
        isActive = false
        forgetJournal()
        journal?.discard()
        sync()
    }

    fun saved() {
        cancelGesture()
        typing = null
        framed = null
        signatures.close()
        paused = false
        rowOpen = false
        panel = null
        question = null
        state = session.clear()
        isActive = false
    }

    private fun perform(action: ViewerEditSession.Action) {
        val refs = performed(action)
        val target = journal ?: return
        target.append(ViewerEditRecord.Performed(action, refs))
        compactJournal()
    }

    private fun performed(action: ViewerEditSession.Action): IntArray {
        if (journal == null) {
            state = session.perform(action)
            return IntArray(0)
        }
        val dropped = session.redoActions().sumOf(ViewerEditJournalFormat::weight)
        val refs = session.refsOf(action)
        state = session.perform(action)
        journalLive += ViewerEditJournalFormat.weight(action) - dropped
        journalDead += dropped
        return refs
    }

    private fun moved(next: ViewerEditSession.State) {
        state = next
        val target = journal ?: return
        journalDead += ViewerEditJournalFormat.MOVE_WEIGHT
        target.append(ViewerEditRecord.Moved(session.position))
        compactJournal()
    }

    private fun recordPad(record: ViewerEditRecord) {
        val target = journal ?: return
        val weight = ViewerEditJournalFormat.weight(record)
        when (record) {
            is ViewerEditRecord.PadOpened, is ViewerEditRecord.PadStroke -> {
                journalPad += weight
                journalLive += weight
            }
            ViewerEditRecord.PadCleared, ViewerEditRecord.PadClosed -> {
                journalLive -= journalPad
                journalDead += journalPad + weight
                journalPad = 0L
            }
            else -> journalDead += weight
        }
        target.append(record)
        compactJournal()
    }

    private fun compactJournal() {
        if (journalDead > journalLive && journalDead > COMPACT_BYTES) rewriteJournal()
    }

    private fun rewriteJournal() {
        val target = journal ?: return
        val records = session.records().toMutableList()
        val padRecord = signatures.pad?.let { ViewerEditRecord.PadOpened(it.slot, it.widthIndex, it.strokes) }
        padRecord?.let(records::add)
        val typingRecord = typing?.let { ViewerEditRecord.Typing(it, session.refsOf(it.editing)) }
        typingRecord?.let(records::add)
        target.rewrite(records)
        journalLive = records.sumOf(ViewerEditJournalFormat::weight)
        journalDead = 0L
        journalPad = padRecord?.let(ViewerEditJournalFormat::weight) ?: 0L
        journalTyping = typingRecord?.let(ViewerEditJournalFormat::weight) ?: 0L
    }

    private fun typingRecordFor(draft: ViewerTypingDraft?): ViewerEditRecord.Typing {
        val record = ViewerEditRecord.Typing(draft, draft?.let { session.refsOf(it.editing) } ?: IntArray(0))
        val weight = ViewerEditJournalFormat.weight(record)
        journalLive -= journalTyping
        journalDead += journalTyping
        if (draft != null) {
            journalTyping = weight
            journalLive += weight
        } else {
            journalTyping = 0L
            journalDead += weight
        }
        return record
    }

    private fun forgetJournal() {
        journalLive = 0L
        journalDead = 0L
        journalPad = 0L
        journalTyping = 0L
    }

    private fun update(next: ViewerEditSettings) {
        if (next == settings) return
        settings = next
        onSettingsChanged(next)
    }

    private fun decide(current: Stroke, start: ViewerTextPoint, tolerance: Float) {
        val doc = document
        if (doc == null) {
            if (current.tool.onlyOnText) stroke = null else current.mode = Mode.FREE
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
                if (current.tool.onlyOnText) {
                    stroke = null
                    return@launch
                }
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
                val found = try {
                    doc.caret(current.page, target)?.let { caret ->
                        if (current.tool.onlyOnText) doc.baselines(current.page, anchor, caret) else doc.highlight(current.page, anchor, caret).rects
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (stroke !== current) return@launch
                current.text = found
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
        if (mark != null) perform(ViewerEditSession.Action.Add(mark))
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
        val hidden = typing?.saved.orEmpty()
        val extra = (pendingSaved + hidden).filter { it.stream == stream }.map { it.id }
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
        signatureDrag = null
        movedSignature = null
        textDrag = null
        weightDrag = null
        draggedText = null
        typing = null
        framed = null
        signatures.close()
        state = session.clear()
        forgetJournal()
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
        val wavy: Boolean,
        val builder: ViewerStrokeBuilder
    ) {
        var mode = Mode.UNDECIDED
        var ending = false
        var last: ViewerTextPoint? = null
        var anchor: ViewerTextPoint? = null
        var target: ViewerTextPoint? = null
        var text: FloatArray? = null
        var job: Job? = null

        fun freehand(): ViewerMark {
            val kind = if (tool == ViewerEditTool.PEN) ViewerMarkKind.PEN else ViewerMarkKind.HIGHLIGHTER
            return ViewerMark(page, kind, color, width, builder.path())
        }

        fun textMark(): ViewerMark? {
            val shown = text ?: return null
            if (tool.onlyOnText) {
                if (shown.isEmpty()) return null
                val kind = if (tool == ViewerEditTool.UNDERLINE) ViewerMarkKind.UNDERLINE else ViewerMarkKind.STRIKETHROUGH
                return ViewerMark(page, kind, color, 0f, ViewerTextLines.path(shown, kind, wavy, ViewerTextLines.MARKS))
            }
            if (ViewerTextRects.count(shown) == 0) return null
            return ViewerMark(page, ViewerMarkKind.TEXT_HIGHLIGHT, color, 0f, ViewerMarkPath.rects(shown))
        }
    }

    private class Eraser(var page: Int, var last: ViewerTextPoint, val tolerance: Float)

    private class SignatureDrag(val framed: ViewerFramedSignature, val start: ViewerTextPoint, val resize: Boolean)

    private class TextDrag(val draft: ViewerTypingDraft, val start: ViewerTextPoint, val resize: Boolean)

    private companion object {
        const val COMPACT_BYTES = 64L * 1024
        const val NOT_IN_PALETTE = -1
    }
}

enum class ViewerEditPanel { COLORS, WIDTH, STYLE, SIGNATURES }

enum class ViewerEditQuestion { EXIT, ERASE_ALL }
