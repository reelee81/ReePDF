package hu.reelee81.reepdf.viewer

interface ViewerEditJournal {
    fun append(record: ViewerEditRecord)

    fun rewrite(records: List<ViewerEditRecord>)

    fun discard()
}

sealed interface ViewerEditRecord {
    class Performed(val action: ViewerEditSession.Action, val refs: IntArray, val typing: Typing? = null) : ViewerEditRecord

    class Moved(val position: Int) : ViewerEditRecord

    class PadOpened(val slot: Int, val widthIndex: Int, val strokes: List<FloatArray>) : ViewerEditRecord

    class PadStroke(val points: FloatArray) : ViewerEditRecord

    class PadWidth(val index: Int) : ViewerEditRecord

    data object PadCleared : ViewerEditRecord

    data object PadClosed : ViewerEditRecord

    class Typing(val draft: ViewerTypingDraft?, val refs: IntArray = IntArray(0)) : ViewerEditRecord
}

class ViewerPadRestore internal constructor(val slot: Int, val widthIndex: Int, val strokes: List<FloatArray>)

class ViewerEditRestore internal constructor(
    val actions: List<ViewerEditSession.Action>,
    val position: Int,
    val pad: ViewerPadRestore?,
    val complete: Boolean,
    val typing: ViewerTypingDraft? = null
) {
    internal fun fits(pageCount: Int): Boolean {
        fun page(value: Int) = value in 0 until pageCount
        val draft = typing
        val typingFits = draft == null || (page(draft.mark.page) && draft.saved.all { page(it.page) })
        return typingFits && actions.all { action ->
            when (action) {
                is ViewerEditSession.Action.Add -> page(action.mark.page)
                is ViewerEditSession.Action.Erase -> action.saved.all { page(it.page) }
                is ViewerEditSession.Action.Blank -> action.pages?.all(::page) ?: true
                is ViewerEditSession.Action.Replace -> page(action.mark.page) && action.saved.all { page(it.page) }
            }
        }
    }
}

sealed interface ViewerEditJournalRead {
    data object None : ViewerEditJournalRead

    data object Unreadable : ViewerEditJournalRead

    class Found(val restore: ViewerEditRestore) : ViewerEditJournalRead
}

fun interface ViewerByteInput {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

class ViewerResume(
    val spot: ViewerSpot?,
    val editing: Boolean,
    val paused: Boolean,
    val panel: ViewerEditPanel?,
    val rowOpen: Boolean,
    val question: ViewerEditQuestion?,
    val deleting: Int?,
    val saving: Boolean
)
