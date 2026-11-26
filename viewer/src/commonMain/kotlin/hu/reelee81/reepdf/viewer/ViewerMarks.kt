package hu.reelee81.reepdf.viewer

enum class ViewerMarkKind {
    PEN,
    HIGHLIGHTER,
    TEXT_HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH,
    SIGNATURE,
    TYPED,
    BOX_X,
    BOX_CHECK,
    BOX_DASH,
    BOX_DOT;

    internal val fills: Boolean
        get() = this == TEXT_HIGHLIGHT || this == UNDERLINE || this == STRIKETHROUGH || this == TYPED || this == BOX_DOT

    internal val formMark: Boolean
        get() = this == BOX_X || this == BOX_CHECK || this == BOX_DASH || this == BOX_DOT

    internal val translucent: Boolean
        get() = this == HIGHLIGHTER || this == TEXT_HIGHLIGHT
}

class ViewerMarkPath(val verbs: ByteArray, val points: FloatArray) {
    init {
        require(points.size == verbs.sumOf { pointsOf(it) }) { "The path has ${points.size} coordinates for its verbs" }
    }

    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CUBIC: Byte = 2
        const val CLOSE: Byte = 3

        fun pointsOf(verb: Byte): Int = when (verb) {
            MOVE, LINE -> 2
            CUBIC -> 6
            CLOSE -> 0
            else -> throw IllegalArgumentException("Unknown path verb $verb")
        }

        fun rects(rects: FloatArray): ViewerMarkPath {
            val count = ViewerTextRects.count(rects)
            val verbs = ByteArray(count * 5)
            val points = FloatArray(count * 8)
            for (index in 0 until count) {
                val rect = ViewerTextRects.rect(rects, index)
                for (verb in 0 until 5) verbs[index * 5 + verb] = if (verb == 0) MOVE else if (verb == 4) CLOSE else LINE
                val corners = floatArrayOf(rect.left, rect.top, rect.right, rect.top, rect.right, rect.bottom, rect.left, rect.bottom)
                corners.copyInto(points, index * 8)
            }
            return ViewerMarkPath(verbs, points)
        }
    }
}

class ViewerMark(
    val page: Int,
    val kind: ViewerMarkKind,
    val color: Int,
    val width: Float,
    val path: ViewerMarkPath,
    val text: ViewerMarkText? = null
) {
    init {
        require((kind == ViewerMarkKind.TYPED) == (text != null)) { "Only a typed mark carries text" }
    }
}

class ViewerSavedMark(
    val id: String,
    val stream: Int,
    val kind: ViewerMarkKind,
    val color: Int,
    val width: Float,
    val path: ViewerMarkPath,
    val clip: FloatArray? = null,
    val text: ViewerMarkText? = null
)

class ViewerPageMarks(val marks: List<ViewerSavedMark>, val streams: IntArray) {
    companion object {
        val NONE = ViewerPageMarks(emptyList(), IntArray(0))
    }
}

class ViewerStreamEdit(val blank: Boolean, val erased: Set<String>) {
    val isOriginal: Boolean
        get() = !blank && erased.isEmpty()

    override fun equals(other: Any?): Boolean = other is ViewerStreamEdit && other.blank == blank && other.erased == erased

    override fun hashCode(): Int = 31 * blank.hashCode() + erased.hashCode()

    companion object {
        val ORIGINAL = ViewerStreamEdit(false, emptySet())
        val BLANK = ViewerStreamEdit(true, emptySet())
    }
}

interface ViewerMarkSource {
    suspend fun pageMarks(page: Int, blank: Boolean): ViewerPageMarks

    suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>)
}

class ViewerEditPlan(
    val added: Map<Int, List<ViewerMark>>,
    val erased: Map<Int, Set<String>>,
    val blankPages: Set<Int>,
    val blankAll: Boolean,
    val erasedPages: Set<Int> = emptySet()
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && erased.isEmpty() && blankPages.isEmpty() && !blankAll
}
