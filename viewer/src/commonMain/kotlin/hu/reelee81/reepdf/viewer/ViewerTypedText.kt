package hu.reelee81.reepdf.viewer

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal expect fun composedText(text: String): String

class ViewerMarkText(val text: String, val weight: Int, val size: Float, val matrix: FloatArray) {
    init {
        require(weight in WEIGHTS) { "Invalid text weight $weight" }
        require(size > 0f && size.isFinite()) { "Invalid text size $size" }
        require(matrix.size == 6 && matrix.all { it.isFinite() }) { "Invalid text matrix" }
        require(ViewerTypedLayout.codePoints(text) <= MAX_CHARS) { "The text is longer than $MAX_CHARS characters" }
    }

    val lines: List<String>
        get() = text.split('\n')

    fun point(x: Float, y: Float): ViewerTextPoint =
        ViewerTextPoint(matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5])

    fun local(point: ViewerTextPoint): ViewerTextPoint? {
        val det = matrix[0] * matrix[3] - matrix[1] * matrix[2]
        if (abs(det) < 1e-9f) return null
        val x = point.x - matrix[4]
        val y = point.y - matrix[5]
        return ViewerTextPoint((matrix[3] * x - matrix[2] * y) / det, (matrix[0] * y - matrix[1] * x) / det)
    }

    internal fun copy(text: String = this.text, weight: Int = this.weight, size: Float = this.size, matrix: FloatArray = this.matrix) =
        ViewerMarkText(text, weight, size, matrix)

    companion object {
        val WEIGHTS = intArrayOf(400, 500, 600, 700, 900)
        const val DEFAULT_WEIGHT = 400
        const val UNITS_PER_EM = 2048f
        const val ASCENT = 1900f / UNITS_PER_EM
        const val LINE_HEIGHT = 2400f / UNITS_PER_EM
        const val MAX_CHARS = 5000
        const val MIN_SIZE = 4f
        const val MAX_SIZE = 2000f
        const val DEFAULT_SIZE = 12f

        fun upright(text: String, weight: Int, size: Float, left: Float, top: Float) =
            ViewerMarkText(text, weight, size, floatArrayOf(1f, 0f, 0f, -1f, left, top))
    }
}

data class ViewerTypingDraft(
    val mark: ViewerMark,
    val cursor: Int,
    val pageWidth: Float,
    val pageHeight: Float,
    val editing: Set<ViewerMark> = emptySet(),
    val saved: Set<ViewerEditSession.SavedRef> = emptySet(),
    val changed: Boolean = false
) {
    val isNew: Boolean
        get() = editing.isEmpty() && saved.isEmpty()
}

class ViewerTypedLine(val start: Int, val text: String, val offsets: FloatArray) {
    val width: Float
        get() = offsets.last()
}

internal object ViewerTypedLayout {
    fun codePoints(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            index += if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    fun accepted(font: ViewerTextFont, text: String): String {
        val composed = composedText(text.replace("\r\n", "\n").replace('\r', '\n'))
        val kept = StringBuilder(composed.length)
        var index = 0
        while (index < composed.length) {
            val step = if (composed[index].isHighSurrogate() && index + 1 < composed.length && composed[index + 1].isLowSurrogate()) 2 else 1
            val codePoint = if (step == 2) 0x10000 + ((composed[index].code - 0xD800) shl 10) + (composed[index + 1].code - 0xDC00) else composed[index].code
            if (codePoint == '\n'.code || font.has(codePoint)) kept.append(composed, index, index + step)
            index += step
        }
        return kept.toString()
    }

    fun limited(current: String, next: String, caret: Int): Pair<String, Int> {
        if (codePoints(next) <= ViewerMarkText.MAX_CHARS) return next to caret
        var prefix = 0
        val shorter = min(current.length, next.length)
        while (prefix < shorter && current[prefix] == next[prefix]) prefix++
        if (prefix > 0 && next[prefix - 1].isHighSurrogate()) prefix--
        var suffix = 0
        while (suffix < shorter - prefix && current[current.length - 1 - suffix] == next[next.length - 1 - suffix]) suffix++
        if (suffix > 0 && next[next.length - suffix].isLowSurrogate()) suffix--
        val inserted = next.substring(prefix, next.length - suffix)
        val room = ViewerMarkText.MAX_CHARS - codePoints(next.substring(0, prefix)) - codePoints(next.substring(next.length - suffix))
        var kept = 0
        var count = 0
        while (kept < inserted.length && count < room) {
            kept += if (inserted[kept].isHighSurrogate() && kept + 1 < inserted.length && inserted[kept + 1].isLowSurrogate()) 2 else 1
            count++
        }
        val text = next.substring(0, prefix) + inserted.substring(0, kept) + next.substring(next.length - suffix)
        val at = when {
            caret <= prefix -> caret
            caret >= next.length - suffix -> caret - (inserted.length - kept)
            else -> prefix + min(caret - prefix, kept)
        }
        return text to at.coerceIn(0, text.length)
    }

    fun lines(font: ViewerTextFont, text: String): List<ViewerTypedLine> {
        val result = ArrayList<ViewerTypedLine>()
        var start = 0
        for (line in text.split('\n')) {
            val offsets = FloatArray(line.length + 1)
            var total = 0L
            var index = 0
            while (index < line.length) {
                val pair = line[index].isHighSurrogate() && index + 1 < line.length && line[index + 1].isLowSurrogate()
                val codePoint = if (pair) 0x10000 + ((line[index].code - 0xD800) shl 10) + (line[index + 1].code - 0xDC00) else line[index].code
                total += font.advance(codePoint)
                val next = index + if (pair) 2 else 1
                for (at in index + 1..next) offsets[at] = total / 1000f
                index = next
            }
            result += ViewerTypedLine(start, line, offsets)
            start += line.length + 1
        }
        return result
    }

    fun widths(font: ViewerTextFont, text: String): FloatArray = lines(font, text).map { it.width }.toFloatArray()

    fun caretAt(font: ViewerTextFont, mark: ViewerMarkText, point: ViewerTextPoint): Int {
        val local = mark.local(point) ?: return mark.text.length
        val lines = lines(font, mark.text)
        val row = floor(-local.y / (ViewerMarkText.LINE_HEIGHT * mark.size)).toInt().coerceIn(0, lines.lastIndex)
        val line = lines[row]
        val x = local.x / mark.size
        var best = 0
        for (at in line.offsets.indices) {
            if (at > 0 && at < line.text.length && line.text[at].isLowSurrogate()) continue
            if (abs(line.offsets[at] - x) < abs(line.offsets[best] - x)) best = at
        }
        return line.start + best
    }

    fun caret(font: ViewerTextFont, text: String, index: Int): Pair<Int, Float> {
        val lines = lines(font, text)
        val at = index.coerceIn(0, text.length)
        for ((row, line) in lines.withIndex()) {
            if (at <= line.start + line.text.length) return row to line.offsets[at - line.start]
        }
        val last = lines.last()
        return lines.lastIndex to last.width
    }

    fun caretLine(font: ViewerTextFont, mark: ViewerMarkText, index: Int): Pair<ViewerTextPoint, ViewerTextPoint> {
        val (row, x) = caret(font, mark.text, index)
        val line = ViewerMarkText.LINE_HEIGHT * mark.size
        return mark.point(x * mark.size, -row * line) to mark.point(x * mark.size, -(row + 1) * line)
    }
}

internal object ViewerTypedPlacement {
    fun mark(page: Int, color: Int, text: ViewerMarkText, font: ViewerTextFont): ViewerMark =
        ViewerMark(page, ViewerMarkKind.TYPED, color, 0f, boxes(text, ViewerTypedLayout.widths(font, text.text)), text)

    fun boxes(text: ViewerMarkText, widths: FloatArray): ViewerMarkPath {
        val verbs = ArrayList<Byte>()
        val points = ArrayList<Float>()
        val line = ViewerMarkText.LINE_HEIGHT * text.size
        for ((index, width) in widths.withIndex()) {
            if (width <= 0f) continue
            val top = -index * line
            val bottom = top - line
            val right = width * text.size
            val corners = floatArrayOf(0f, top, right, top, right, bottom, 0f, bottom)
            for (corner in 0 until 4) {
                val p = text.point(corners[corner * 2], corners[corner * 2 + 1])
                verbs += if (corner == 0) ViewerMarkPath.MOVE else ViewerMarkPath.LINE
                points += p.x
                points += p.y
            }
            verbs += ViewerMarkPath.CLOSE
        }
        return ViewerMarkPath(verbs.toByteArray(), points.toFloatArray())
    }

    fun frame(text: ViewerMarkText, font: ViewerTextFont): FloatArray = outline(text, ViewerTypedLayout.widths(font, text.text), 0f)

    fun shown(text: ViewerMarkText, font: ViewerTextFont, pageWidth: Float, pageHeight: Float): FloatArray {
        val widths = ViewerTypedLayout.widths(font, text.text)
        val box = outline(text, widths, 0f)
        val wide = outline(text, widths, SHOWN_WIDTH)
        return floatArrayOf(
            min(box[0], max(wide[0], 0f)),
            min(box[1], max(wide[1], 0f)),
            max(box[2], min(wide[2], pageWidth)),
            max(box[3], min(wide[3], pageHeight))
        )
    }

    private fun outline(text: ViewerMarkText, widths: FloatArray, least: Float): FloatArray {
        val width = max(widths.maxOrNull() ?: 0f, least) * text.size
        val height = widths.size * ViewerMarkText.LINE_HEIGHT * text.size
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for ((x, y) in listOf(0f to 0f, width to 0f, width to -height, 0f to -height)) {
            val p = text.point(x, y)
            box[0] = min(box[0], p.x)
            box[1] = min(box[1], p.y)
            box[2] = max(box[2], p.x)
            box[3] = max(box[3], p.y)
        }
        return box
    }

    fun placed(
        page: Int,
        color: Int,
        text: String,
        weight: Int,
        size: Float,
        point: ViewerTextPoint,
        font: ViewerTextFont,
        pageWidth: Float,
        pageHeight: Float
    ): ViewerMark {
        val start = ViewerMarkText.upright(text, weight, size, point.x, point.y - ViewerMarkText.LINE_HEIGHT * size / 2)
        return mark(page, color, fitted(start, font, pageWidth, pageHeight), font)
    }

    fun fitted(text: ViewerMarkText, font: ViewerTextFont, pageWidth: Float, pageHeight: Float): ViewerMarkText {
        var current = text
        val box = frame(current, font)
        val width = box[2] - box[0]
        val height = box[3] - box[1]
        val shrink = min(if (width > pageWidth) pageWidth / width else 1f, if (height > pageHeight) pageHeight / height else 1f)
        if (shrink < 1f) current = scaled(current, box[0], box[1], max(shrink, ViewerMarkText.MIN_SIZE / current.size))
        val fitted = frame(current, font)
        val dx = within(fitted[0], 0f, pageWidth - (fitted[2] - fitted[0])) - fitted[0]
        val dy = within(fitted[1], 0f, pageHeight - (fitted[3] - fitted[1])) - fitted[1]
        return if (dx == 0f && dy == 0f) current else shifted(current, dx, dy)
    }

    fun moved(mark: ViewerMark, font: ViewerTextFont, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): ViewerMark {
        val text = mark.text ?: return mark
        val box = frame(text, font)
        val x = within(dx, -box[0], pageWidth - box[2])
        val y = within(dy, -box[1], pageHeight - box[3])
        return mark(mark.page, mark.color, shifted(text, x, y), font)
    }

    fun resized(mark: ViewerMark, font: ViewerTextFont, corner: ViewerTextPoint, pageWidth: Float, pageHeight: Float): ViewerMark {
        val text = mark.text ?: return mark
        val box = frame(text, font)
        val view = shown(text, font, pageWidth, pageHeight)
        val left = view[0]
        val top = view[1]
        val width = view[2] - left
        val height = view[3] - top
        if (height <= 0f) return mark
        val wanted = ((corner.x - left) * width + (corner.y - top) * height) / (width * width + height * height)
        val right = box[2] - left
        val bottom = box[3] - top
        val largest = min(
            min(if (right > 0f) (pageWidth - left) / right else Float.MAX_VALUE, if (bottom > 0f) (pageHeight - top) / bottom else Float.MAX_VALUE),
            ViewerMarkText.MAX_SIZE / text.size
        )
        val factor = within(wanted, ViewerMarkText.MIN_SIZE / text.size, largest)
        return mark(mark.page, mark.color, scaled(text, left, top, factor), font)
    }

    fun edited(mark: ViewerMark, font: ViewerTextFont, text: String, pageWidth: Float, pageHeight: Float): ViewerMark {
        val current = mark.text ?: return mark
        return mark(mark.page, mark.color, fitted(current.copy(text = text), font, pageWidth, pageHeight), font)
    }

    fun reweighted(mark: ViewerMark, font: ViewerTextFont, weight: Int, pageWidth: Float, pageHeight: Float): ViewerMark {
        val current = mark.text ?: return mark
        return mark(mark.page, mark.color, fitted(current.copy(weight = weight), font, pageWidth, pageHeight), font)
    }

    fun recolored(mark: ViewerMark, color: Int): ViewerMark = ViewerMark(mark.page, mark.kind, color, mark.width, mark.path, mark.text)

    private fun scaled(text: ViewerMarkText, x: Float, y: Float, factor: Float): ViewerMarkText {
        val m = text.matrix
        return text.copy(size = text.size * factor, matrix = floatArrayOf(m[0], m[1], m[2], m[3], x + (m[4] - x) * factor, y + (m[5] - y) * factor))
    }

    private fun shifted(text: ViewerMarkText, dx: Float, dy: Float): ViewerMarkText {
        val m = text.matrix
        return text.copy(matrix = floatArrayOf(m[0], m[1], m[2], m[3], m[4] + dx, m[5] + dy))
    }

    private fun within(value: Float, low: Float, high: Float): Float = if (high < low) low else value.coerceIn(low, high)

    private const val SHOWN_WIDTH = 2.5f
}
