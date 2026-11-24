package hu.reelee81.reepdf.viewer

class ViewerEditSession {
    data class SavedRef(val page: Int, val stream: Int, val id: String)

    sealed interface Action {
        class Add(val mark: ViewerMark) : Action

        class Erase(val marks: Set<ViewerMark>, val saved: Set<SavedRef>) : Action

        class Blank(val pages: Set<Int>?) : Action

        class Replace(val marks: Set<ViewerMark>, val saved: Set<SavedRef>, val mark: ViewerMark) : Action
    }

    class State internal constructor(
        val added: Map<Int, List<ViewerMark>>,
        private val erased: Map<Int, Set<String>>,
        private val erasedPages: Map<Int, Int>,
        val blankPages: Set<Int>,
        val blankAll: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean
    ) {
        fun isBlank(page: Int): Boolean = blankAll || page in blankPages

        fun isVisible(ref: SavedRef): Boolean = !isBlank(ref.page) && ref.id !in erased[ref.stream].orEmpty()

        fun streamEdit(page: Int, stream: Int): ViewerStreamEdit = when {
            isBlank(page) -> ViewerStreamEdit.BLANK
            else -> erased[stream]?.let { ViewerStreamEdit(false, it) } ?: ViewerStreamEdit.ORIGINAL
        }

        fun plan(): ViewerEditPlan = ViewerEditPlan(
            added = added,
            erased = erased.filterKeys { stream -> erasedPages[stream]?.let { !isBlank(it) } ?: true },
            blankPages = if (blankAll) emptySet() else blankPages,
            blankAll = blankAll
        )

        val hasChanges: Boolean
            get() = !plan().isEmpty

        companion object {
            val EMPTY = State(emptyMap(), emptyMap(), emptyMap(), emptySet(), false, canUndo = false, canRedo = false)
        }
    }

    private val actions = ArrayList<Action>()
    private var done = 0

    var state: State = State.EMPTY
        private set

    val position: Int
        get() = done

    internal fun redoActions(): List<Action> = actions.subList(done, actions.size).toList()

    internal fun refsOf(action: Action): IntArray = refsOf(actions, done, action)

    internal fun records(): List<ViewerEditRecord> {
        val records = ArrayList<ViewerEditRecord>(actions.size + 1)
        for ((index, action) in actions.withIndex()) records += ViewerEditRecord.Performed(action, refsOf(actions, index, action))
        if (done < actions.size) records += ViewerEditRecord.Moved(done)
        return records
    }

    fun restore(restored: List<Action>, position: Int): State {
        actions.clear()
        actions.addAll(restored)
        done = position.coerceIn(0, actions.size)
        return replay()
    }

    fun perform(action: Action): State {
        while (actions.size > done) actions.removeAt(actions.lastIndex)
        actions += action
        done++
        return replay()
    }

    fun undo(): State {
        if (done > 0) done--
        return replay()
    }

    fun redo(): State {
        if (done < actions.size) done++
        return replay()
    }

    fun clear(): State {
        actions.clear()
        done = 0
        return replay()
    }

    private fun replay(): State {
        val added = LinkedHashMap<Int, MutableList<ViewerMark>>()
        val erased = HashMap<Int, MutableSet<String>>()
        val erasedPages = HashMap<Int, Int>()
        val blankPages = HashSet<Int>()
        var blankAll = false
        for (index in 0 until done) {
            when (val action = actions[index]) {
                is Action.Add -> added.getOrPut(action.mark.page) { ArrayList() } += action.mark
                is Action.Erase -> {
                    for (mark in action.marks) added[mark.page]?.remove(mark)
                    for ((page, stream, id) in action.saved) {
                        erased.getOrPut(stream) { LinkedHashSet() } += id
                        erasedPages[stream] = page
                    }
                }
                is Action.Replace -> {
                    for (mark in action.marks) added[mark.page]?.remove(mark)
                    for ((page, stream, id) in action.saved) {
                        erased.getOrPut(stream) { LinkedHashSet() } += id
                        erasedPages[stream] = page
                    }
                    added.getOrPut(action.mark.page) { ArrayList() } += action.mark
                }
                is Action.Blank -> {
                    val pages = action.pages
                    if (pages == null) {
                        blankAll = true
                        added.clear()
                    } else {
                        blankPages += pages
                        pages.forEach(added::remove)
                    }
                }
            }
        }
        state = State(
            added.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() },
            erased.mapValues { it.value.toSet() },
            erasedPages,
            blankPages,
            blankAll,
            canUndo = done > 0,
            canRedo = done < actions.size
        )
        return state
    }

    private companion object {
        fun refsOf(actions: List<Action>, upTo: Int, action: Action): IntArray {
            val marks = when (action) {
                is Action.Erase -> action.marks
                is Action.Replace -> action.marks
                else -> return IntArray(0)
            }
            val refs = ArrayList<Int>(marks.size)
            for (mark in marks) {
                for (index in upTo - 1 downTo 0) {
                    val added = when (val earlier = actions[index]) {
                        is Action.Add -> earlier.mark
                        is Action.Replace -> earlier.mark
                        else -> null
                    }
                    if (added === mark) {
                        refs += index
                        break
                    }
                }
            }
            return refs.toIntArray()
        }
    }
}
