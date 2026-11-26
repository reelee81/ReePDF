package hu.reelee81.reepdf.viewer

object ViewerEditJournalFormat {
    private val MAGIC = byteArrayOf(0x52, 0x45, 0x45, 0x4A)
    private const val VERSION = 2
    private const val OLDEST_VERSION = 1
    private const val HEAD = 9
    private const val FRAME = 5
    private const val CHECK = 4
    private const val MAX_IDENTITY = 64 * 1024
    private const val MAX_RECORD = 16 * 1024 * 1024

    private const val ADD = 1
    private const val ERASE = 2
    private const val BLANK = 3
    private const val REPLACE = 4
    private const val POSITION = 5
    private const val PAD_OPEN = 6
    private const val PAD_STROKE = 7
    private const val PAD_WIDTH = 8
    private const val PAD_CLEAR = 9
    private const val PAD_CLOSE = 10
    private const val TYPING = 11
    private const val TYPED_STEP = 12
    private const val MATRIX = 6

    internal const val MOVE_WEIGHT = 13L

    fun header(identity: String): ByteArray {
        val name = identity.encodeToByteArray()
        val out = JournalBytes(HEAD + name.size + CHECK)
        out.bytes(MAGIC)
        out.byte(VERSION)
        out.int(name.size)
        out.bytes(name)
        out.int(Crc32.update(0, out.data, 0, out.size))
        return out.toByteArray()
    }

    fun encode(record: ViewerEditRecord): ByteArray {
        val out = JournalBytes((weight(record) + FRAME + CHECK).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        out.byte(0)
        out.int(0)
        val type = when (record) {
            is ViewerEditRecord.Performed -> {
                val box = record.typing
                if (box == null) {
                    performed(out, record)
                } else {
                    val at = out.size
                    out.byte(0)
                    out.data[at] = performed(out, record).toByte()
                    typing(out, box)
                    TYPED_STEP
                }
            }
            is ViewerEditRecord.Moved -> {
                out.int(record.position)
                POSITION
            }
            is ViewerEditRecord.PadOpened -> {
                out.int(record.slot)
                out.int(record.widthIndex)
                out.int(record.strokes.size)
                record.strokes.forEach(out::floats)
                PAD_OPEN
            }
            is ViewerEditRecord.PadStroke -> {
                out.floats(record.points)
                PAD_STROKE
            }
            is ViewerEditRecord.PadWidth -> {
                out.int(record.index)
                PAD_WIDTH
            }
            ViewerEditRecord.PadCleared -> PAD_CLEAR
            ViewerEditRecord.PadClosed -> PAD_CLOSE
            is ViewerEditRecord.Typing -> {
                typing(out, record)
                TYPING
            }
        }
        out.data[0] = type.toByte()
        out.putInt(1, out.size - FRAME)
        out.int(Crc32.update(0, out.data, 0, out.size))
        return out.toByteArray()
    }

    fun read(input: ViewerByteInput, identity: String): ViewerEditJournalRead {
        val source = Source(input)
        val head = ByteArray(HEAD)
        if (source.fill(head, 0, HEAD) < HEAD) return ViewerEditJournalRead.Unreadable
        if (!MAGIC.indices.all { head[it] == MAGIC[it] } || head[MAGIC.size].toInt() !in OLDEST_VERSION..VERSION) return ViewerEditJournalRead.Unreadable
        val length = intAt(head, MAGIC.size + 1)
        if (length !in 0..MAX_IDENTITY) return ViewerEditJournalRead.Unreadable
        val name = ByteArray(length + CHECK)
        if (source.fill(name, 0, name.size) < name.size) return ViewerEditJournalRead.Unreadable
        if (Crc32.update(Crc32.update(0, head, 0, HEAD), name, 0, length) != intAt(name, length)) return ViewerEditJournalRead.Unreadable
        if (name.decodeToString(0, length) != identity) return ViewerEditJournalRead.None
        val builder = Builder()
        val complete = records(source, builder)
        return ViewerEditJournalRead.Found(builder.restore(complete))
    }

    internal fun weight(action: ViewerEditSession.Action): Long = when (action) {
        is ViewerEditSession.Action.Add -> 16L + weight(action.mark)
        is ViewerEditSession.Action.Erase -> 16L + action.marks.size * 4L + weight(action.saved)
        is ViewerEditSession.Action.Blank -> 16L + (action.pages?.size ?: 0) * 4L
        is ViewerEditSession.Action.Replace -> 16L + action.marks.size * 4L + weight(action.saved) + weight(action.mark)
    }

    internal fun weight(record: ViewerEditRecord): Long = when (record) {
        is ViewerEditRecord.Performed -> weight(record.action) + (record.typing?.let { weight(it) - FRAME - CHECK + 1L } ?: 0L)
        is ViewerEditRecord.Moved -> MOVE_WEIGHT
        is ViewerEditRecord.PadOpened -> 21L + record.strokes.sumOf { 4L + it.size * 4L }
        is ViewerEditRecord.PadStroke -> 13L + record.points.size * 4L
        is ViewerEditRecord.PadWidth -> 13L
        ViewerEditRecord.PadCleared, ViewerEditRecord.PadClosed -> 9L
        is ViewerEditRecord.Typing -> 10L + (record.draft?.let { weight(it.mark) + 17L + record.refs.size * 4L + weight(it.saved) } ?: 0L)
    }

    private fun weight(mark: ViewerMark): Long =
        25L + mark.path.verbs.size + mark.path.points.size * 4L + (mark.text?.let { 4L + it.text.length * 3L + 8L + MATRIX * 4L } ?: 0L)

    private fun weight(saved: Set<ViewerEditSession.SavedRef>): Long = 4L + saved.sumOf { 12L + it.id.length }

    private fun performed(out: JournalBytes, record: ViewerEditRecord.Performed): Int = when (val action = record.action) {
        is ViewerEditSession.Action.Add -> {
            mark(out, action.mark)
            ADD
        }
        is ViewerEditSession.Action.Erase -> {
            refs(out, record.refs)
            saved(out, action.saved)
            ERASE
        }
        is ViewerEditSession.Action.Blank -> {
            val pages = action.pages
            out.byte(if (pages == null) 1 else 0)
            if (pages != null) {
                out.int(pages.size)
                pages.forEach(out::int)
            }
            BLANK
        }
        is ViewerEditSession.Action.Replace -> {
            refs(out, record.refs)
            saved(out, action.saved)
            mark(out, action.mark)
            REPLACE
        }
    }

    private fun typing(out: JournalBytes, record: ViewerEditRecord.Typing) {
        val draft = record.draft
        out.byte(if (draft == null) 0 else 1)
        if (draft == null) return
        mark(out, draft.mark)
        out.int(draft.cursor)
        out.float(draft.pageWidth)
        out.float(draft.pageHeight)
        out.byte(if (draft.changed) 1 else 0)
        refs(out, record.refs)
        saved(out, draft.saved)
    }

    private fun mark(out: JournalBytes, mark: ViewerMark) {
        out.int(mark.page)
        out.byte(code(mark.kind))
        out.int(mark.color)
        out.float(mark.width)
        out.int(mark.path.verbs.size)
        out.bytes(mark.path.verbs)
        out.floats(mark.path.points)
        val text = mark.text ?: return
        out.string(text.text)
        out.int(text.weight)
        out.float(text.size)
        for (value in text.matrix) out.float(value)
    }

    private fun refs(out: JournalBytes, refs: IntArray) {
        out.int(refs.size)
        refs.forEach(out::int)
    }

    private fun saved(out: JournalBytes, saved: Set<ViewerEditSession.SavedRef>) {
        out.int(saved.size)
        for ((page, stream, id) in saved) {
            out.int(page)
            out.int(stream)
            out.string(id)
        }
    }

    private fun code(kind: ViewerMarkKind): Int = when (kind) {
        ViewerMarkKind.PEN -> 0
        ViewerMarkKind.HIGHLIGHTER -> 1
        ViewerMarkKind.TEXT_HIGHLIGHT -> 2
        ViewerMarkKind.UNDERLINE -> 3
        ViewerMarkKind.STRIKETHROUGH -> 4
        ViewerMarkKind.SIGNATURE -> 5
        ViewerMarkKind.TYPED -> 6
        ViewerMarkKind.BOX_X -> 7
        ViewerMarkKind.BOX_CHECK -> 8
        ViewerMarkKind.BOX_DASH -> 9
        ViewerMarkKind.BOX_DOT -> 10
    }

    private fun kind(code: Int): ViewerMarkKind = when (code) {
        0 -> ViewerMarkKind.PEN
        1 -> ViewerMarkKind.HIGHLIGHTER
        2 -> ViewerMarkKind.TEXT_HIGHLIGHT
        3 -> ViewerMarkKind.UNDERLINE
        4 -> ViewerMarkKind.STRIKETHROUGH
        5 -> ViewerMarkKind.SIGNATURE
        6 -> ViewerMarkKind.TYPED
        7 -> ViewerMarkKind.BOX_X
        8 -> ViewerMarkKind.BOX_CHECK
        9 -> ViewerMarkKind.BOX_DASH
        10 -> ViewerMarkKind.BOX_DOT
        else -> throw IllegalArgumentException("Unknown mark kind $code")
    }

    private fun records(source: Source, builder: Builder): Boolean {
        val frame = ByteArray(FRAME)
        while (true) {
            val got = source.fill(frame, 0, FRAME)
            if (got == 0) return true
            if (got < FRAME) return false
            val length = intAt(frame, 1)
            if (length !in 0..MAX_RECORD) return false
            val body = ByteArray(length + CHECK)
            if (source.fill(body, 0, body.size) < body.size) return false
            if (Crc32.update(Crc32.update(0, frame, 0, FRAME), body, 0, length) != intAt(body, length)) return false
            try {
                apply(frame[0].toInt(), Cursor(body, length), builder)
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
    }

    private fun apply(type: Int, cursor: Cursor, builder: Builder) {
        when (type) {
            ADD, ERASE, BLANK, REPLACE -> {
                val action = cursor.action(type, builder)
                cursor.done()
                builder.perform(action)
            }
            TYPED_STEP -> {
                val action = cursor.action(cursor.byte(), builder)
                val open = cursor.typing(builder, action)
                cursor.done()
                builder.perform(action)
                builder.typing = open
            }
            POSITION -> {
                val position = cursor.int()
                cursor.done()
                require(position in 0..builder.actions.size) { "Invalid position $position" }
                builder.position = position
            }
            PAD_OPEN -> {
                val slot = cursor.int()
                val width = cursor.int()
                val strokes = List(cursor.count(ViewerSignature.MAX_STROKES, 4)) { cursor.floats() }
                cursor.done()
                require(slot in 0 until ViewerSignature.SLOTS && width in ViewerSignature.WIDTHS.indices) { "Invalid pad" }
                builder.pad = PadBuilder(slot, width).also { pad -> strokes.forEach(pad::add) }
            }
            PAD_STROKE -> {
                val points = cursor.floats()
                cursor.done()
                requireNotNull(builder.pad) { "No open pad" }.add(points)
            }
            PAD_WIDTH -> {
                val width = cursor.int()
                cursor.done()
                require(width in ViewerSignature.WIDTHS.indices) { "Invalid pad width $width" }
                requireNotNull(builder.pad) { "No open pad" }.width = width
            }
            PAD_CLEAR -> {
                cursor.done()
                requireNotNull(builder.pad) { "No open pad" }.clear()
            }
            PAD_CLOSE -> {
                cursor.done()
                builder.pad = null
            }
            TYPING -> {
                val open = cursor.typing(builder, null)
                cursor.done()
                builder.typing = open
            }
            else -> throw IllegalArgumentException("Unknown record $type")
        }
    }

    private fun intAt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)

    private class Builder {
        val actions = ArrayList<ViewerEditSession.Action>()
        var position = 0
        var pad: PadBuilder? = null
        var typing: ViewerTypingDraft? = null

        fun perform(action: ViewerEditSession.Action) {
            while (actions.size > position) actions.removeAt(actions.lastIndex)
            actions += action
            position++
        }

        fun mark(ref: Int, pending: ViewerEditSession.Action?): ViewerMark {
            val action = if (pending != null && ref == position) {
                pending
            } else {
                require(ref in 0 until position) { "Invalid mark reference $ref" }
                actions[ref]
            }
            return when (action) {
                is ViewerEditSession.Action.Add -> action.mark
                is ViewerEditSession.Action.Replace -> action.mark
                else -> throw IllegalArgumentException("Step $ref adds no mark")
            }
        }

        fun restore(complete: Boolean) = ViewerEditRestore(
            actions.toList(),
            position,
            pad?.let { ViewerPadRestore(it.slot, it.width, it.strokes.toList()) },
            complete,
            typing
        )
    }

    private class PadBuilder(val slot: Int, var width: Int) {
        val strokes = ArrayList<FloatArray>()
        private var points = 0

        fun add(stroke: FloatArray) {
            require(stroke.size >= 2 && stroke.size % 2 == 0) { "A pad stroke needs whole points" }
            for (index in stroke.indices) {
                val limit = if (index % 2 == 0) 1f else ViewerSignature.HEIGHT
                require(stroke[index] in 0f..limit) { "A pad point lies outside the pad" }
            }
            val total = points + stroke.size / 2
            require(strokes.size < ViewerSignature.MAX_STROKES && total <= ViewerSignature.MAX_POINTS) { "The pad is full" }
            points = total
            strokes += stroke
        }

        fun clear() {
            strokes.clear()
            points = 0
        }
    }

    private class Cursor(private val data: ByteArray, private val end: Int) {
        private var at = 0

        private fun need(count: Int) {
            require(count >= 0 && count <= end - at) { "The record ends early" }
        }

        fun byte(): Int {
            need(1)
            return data[at++].toInt() and 0xFF
        }

        fun int(): Int {
            need(4)
            val value = intAt(data, at)
            at += 4
            return value
        }

        fun float(): Float = Float.fromBits(int())

        fun count(max: Int, unit: Int): Int {
            val count = int()
            require(count in 0..max && count.toLong() * unit <= end - at) { "Invalid count $count" }
            return count
        }

        fun ints(): IntArray = IntArray(count(Int.MAX_VALUE, 4)) { int() }

        fun floats(): FloatArray = FloatArray(count(Int.MAX_VALUE, 4)) { float() }

        fun string(): String {
            val length = count(Int.MAX_VALUE, 1)
            val text = data.decodeToString(at, at + length)
            at += length
            return text
        }

        fun mark(): ViewerMark {
            val page = int()
            val kind = kind(byte())
            val color = int()
            val width = float()
            val verbs = ByteArray(count(Int.MAX_VALUE, 1)) { data[at + it] }
            at += verbs.size
            val points = floats()
            require(width.isFinite() && width >= 0f && points.all { it.isFinite() }) { "Invalid mark values" }
            val text = if (kind == ViewerMarkKind.TYPED) ViewerMarkText(string(), int(), float(), FloatArray(MATRIX) { float() }) else null
            return ViewerMark(page, kind, color, width, ViewerMarkPath(verbs, points), text)
        }

        fun refs(builder: Builder, pending: ViewerEditSession.Action? = null): Set<ViewerMark> {
            val refs = ints()
            return refs.mapTo(LinkedHashSet()) { builder.mark(it, pending) }
        }

        fun action(type: Int, builder: Builder): ViewerEditSession.Action = when (type) {
            ADD -> ViewerEditSession.Action.Add(mark())
            ERASE -> {
                val marks = refs(builder)
                ViewerEditSession.Action.Erase(marks, saved())
            }
            BLANK -> {
                val all = byte() == 1
                ViewerEditSession.Action.Blank(if (all) null else ints().toSet())
            }
            REPLACE -> {
                val marks = refs(builder)
                val saved = saved()
                ViewerEditSession.Action.Replace(marks, saved, mark())
            }
            else -> throw IllegalArgumentException("Unknown step $type")
        }

        fun typing(builder: Builder, pending: ViewerEditSession.Action?): ViewerTypingDraft? {
            if (byte() != 1) return null
            val mark = mark()
            val caret = int()
            val pageWidth = float()
            val pageHeight = float()
            val changed = byte() == 1
            val marks = refs(builder, pending)
            val saved = saved()
            val text = requireNotNull(mark.text) { "An open text box needs a typed mark" }
            require(caret in 0..text.text.length) { "Invalid caret $caret" }
            require(pageWidth > 0f && pageHeight > 0f && pageWidth.isFinite() && pageHeight.isFinite()) { "Invalid page size" }
            return ViewerTypingDraft(mark, caret, pageWidth, pageHeight, marks, saved, changed)
        }

        fun saved(): Set<ViewerEditSession.SavedRef> {
            val count = count(Int.MAX_VALUE, 12)
            val refs = LinkedHashSet<ViewerEditSession.SavedRef>()
            repeat(count) { refs += ViewerEditSession.SavedRef(int(), int(), string()) }
            return refs
        }

        fun done() {
            require(at == end) { "The record has extra bytes" }
        }
    }

    private class Source(private val input: ViewerByteInput) {
        fun fill(buffer: ByteArray, offset: Int, length: Int): Int {
            var done = 0
            while (done < length) {
                val read = input.read(buffer, offset + done, length - done)
                if (read <= 0) break
                done += read
            }
            return done
        }
    }

    private class JournalBytes(capacity: Int) {
        var data = ByteArray(capacity.coerceAtLeast(16))
            private set
        var size = 0
            private set

        private fun room(count: Int) {
            if (count <= data.size - size) return
            val wanted = size.toLong() + count
            require(wanted <= Int.MAX_VALUE) { "The record is too large" }
            data = data.copyOf(maxOf(wanted, minOf(data.size * 2L, Int.MAX_VALUE.toLong())).toInt())
        }

        fun byte(value: Int) {
            room(1)
            data[size++] = value.toByte()
        }

        fun int(value: Int) {
            room(4)
            putInt(size, value)
            size += 4
        }

        fun putInt(at: Int, value: Int) {
            data[at] = (value ushr 24).toByte()
            data[at + 1] = (value ushr 16).toByte()
            data[at + 2] = (value ushr 8).toByte()
            data[at + 3] = value.toByte()
        }

        fun float(value: Float) = int(value.toRawBits())

        fun bytes(values: ByteArray) {
            room(values.size)
            values.copyInto(data, size)
            size += values.size
        }

        fun floats(values: FloatArray) {
            int(values.size)
            room(values.size * 4)
            for (value in values) {
                putInt(size, value.toRawBits())
                size += 4
            }
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            int(bytes.size)
            bytes(bytes)
        }

        fun toByteArray(): ByteArray = if (size == data.size) data else data.copyOf(size)
    }

    private object Crc32 {
        private val TABLE = IntArray(256) { index ->
            var value = index
            repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor POLYNOMIAL else value ushr 1 }
            value
        }

        fun update(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
            var value = crc.inv()
            for (index in offset until offset + length) value = TABLE[(value xor data[index].toInt()) and 0xFF] xor (value ushr 8)
            return value.inv()
        }
    }

    private const val POLYNOMIAL = 0xEDB88320.toInt()
}
