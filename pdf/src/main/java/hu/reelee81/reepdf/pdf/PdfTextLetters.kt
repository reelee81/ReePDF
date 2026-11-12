package hu.reelee81.reepdf.pdf

import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class PdfTextLetters private constructor(
    private val codes: IntArray,
    private val corners: FloatArray,
    private val sizes: FloatArray,
    private val rightToLeft: BooleanArray,
    private val lineStarts: IntArray,
    private val directions: FloatArray,
    private val halfSizes: FloatArray
) {
    val size: Int
        get() = codes.size

    fun word(x: Float, y: Float, tolerance: Float): PdfTextWord? {
        var found = -1
        var inside = false
        var best = Float.MAX_VALUE
        for (letter in codes.indices) {
            if (codes[letter] == SPACE) continue
            val outside = boxDistance(letter, x, y)
            if (outside == 0f) {
                val centre = centreDistance(letter, x, y)
                if (!inside || centre < best) {
                    found = letter
                    inside = true
                    best = centre
                }
            } else if (!inside && outside <= tolerance && outside < best) {
                found = letter
                best = outside
            }
        }
        if (found < 0) return null
        val start = wordStart(found)
        val end = wordEnd(found)
        var first = start
        var last = end
        while (first < last && trimmed(codes[first])) first++
        while (last > first && trimmed(codes[last])) last--
        if (trimmed(codes[first]) || found < first || found > last) return PdfTextWord(this, found, found, found, found)
        return PdfTextWord(this, first, last, start, end)
    }

    fun spanTouches(first: Int, last: Int, quad: Quad, tolerance: Float): Boolean {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (offset in first * STRIDE until (last + 1) * STRIDE step 2) {
            left = minOf(left, corners[offset])
            right = maxOf(right, corners[offset])
            top = minOf(top, corners[offset + 1])
            bottom = maxOf(bottom, corners[offset + 1])
        }
        val quadLeft = minOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)
        val quadRight = maxOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)
        val quadTop = minOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
        val quadBottom = maxOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
        return quadLeft <= right + tolerance && quadRight >= left - tolerance &&
            quadTop <= bottom + tolerance && quadBottom >= top - tolerance
    }

    fun caret(x: Float, y: Float): PdfTextCaret? {
        var found = -1
        var best = Float.MAX_VALUE
        var bestCentre = Float.MAX_VALUE
        for (letter in codes.indices) {
            val outside = boxDistance(letter, x, y)
            if (outside > best) continue
            val centre = centreDistance(letter, x, y)
            if (outside < best || centre < bestCentre) {
                found = letter
                best = outside
                bestCentre = centre
            }
        }
        if (found < 0) return null
        val line = lineOf(found)
        val forward = directions[line * 2] * (x - centre(found, X)) + directions[line * 2 + 1] * (y - centre(found, Y)) > 0
        val ahead = forward != rightToLeft[found]
        val midline = if (ahead) after(found) else before(found)
        return PdfTextCaret(if (ahead) found + 1 else found, clearOfLaterLines(line, midline), midline)
    }

    fun snapsTo(index: Int, handles: Quad, point: Point): Boolean {
        if (index < codes.size) {
            val offset = index * STRIDE
            return handles.ul_x == corners[offset + UL_X] && handles.ul_y == corners[offset + UL_Y] &&
                handles.lr_x == corners[offset + LL_X] && handles.lr_y == corners[offset + LL_Y]
        }
        val offset = codes.lastIndex * STRIDE
        return codes.isNotEmpty() && handles.ul_x == corners[offset + UL_X] && handles.ul_y == corners[offset + UL_Y] &&
            handles.lr_x == point.x && handles.lr_y == point.y
    }

    fun before(letter: Int): Point = onMidline(lineOf(letter), leading(letter, X), leading(letter, Y))

    fun after(letter: Int): Point = onMidline(lineOf(letter), trailing(letter, X), trailing(letter, Y))

    fun ends(): Pair<Point, Point>? = if (codes.isEmpty()) null else before(0) to after(codes.lastIndex)

    fun text(first: Int, last: Int): String {
        val text = StringBuilder()
        for (letter in first..last) {
            val code = codes[letter]
            text.appendCodePoint(if (code < FIRST_PRINTABLE || code > Character.MAX_CODE_POINT) REPLACEMENT else code)
        }
        return text.toString()
    }

    private fun wordStart(letter: Int): Int {
        val lineStart = lineStarts[lineOf(letter)]
        var start = letter
        while (start > lineStart && !separates(start - 1, start)) start--
        return start
    }

    private fun wordEnd(letter: Int): Int {
        val lineEnd = lineStarts[lineOf(letter) + 1] - 1
        var end = letter
        while (end < lineEnd && !separates(end, end + 1)) end++
        return end
    }

    private fun trimmed(code: Int): Boolean = code !in KEPT_SIGNS && Character.getType(code) in PUNCTUATION

    private fun separates(left: Int, right: Int): Boolean {
        if (codes[left] == SPACE || codes[right] == SPACE) return true
        val line = lineOf(left)
        val end = along(line, trailing(left, X), trailing(left, Y))
        val start = along(line, leading(right, X), leading(right, Y))
        val gap = if (rightToLeft[left] && rightToLeft[right]) end - start else start - end
        return gap > SPACE_GAP * sizes[right]
    }

    private fun clearOfLaterLines(line: Int, midline: Point): Point {
        val half = halfSizes[line]
        val acrossX = -directions[line * 2 + 1]
        val acrossY = directions[line * 2]
        var shift = 0f
        var bestScore = -1f
        for (step in 0..CLEAR_STEPS) {
            val offset = half * CLEAR_SPAN * (2f * step / CLEAR_STEPS - 1f)
            val margin = marginToLaterLines(line, midline, midline.x + acrossX * offset, midline.y + acrossY * offset)
            if (margin < 0f) continue
            val score = min(margin, half * CLEAR_MARGIN) - CLEAR_PULL * abs(offset)
            if (score > bestScore) {
                bestScore = score
                shift = offset
            }
        }
        return if (shift == 0f) midline else Point(midline.x + acrossX * shift, midline.y + acrossY * shift)
    }

    private fun marginToLaterLines(line: Int, midline: Point, x: Float, y: Float): Float {
        var margin = Float.MAX_VALUE
        for (later in line + 1 until lineStarts.size - 1) {
            val first = lineStarts[later] * STRIDE
            val last = (lineStarts[later + 1] - 1) * STRIDE
            val startX = (corners[first + LL_X] + corners[first + UL_X]) / 2
            val startY = (corners[first + LL_Y] + corners[first + UL_Y]) / 2
            val endX = (corners[last + LR_X] + corners[last + UR_X]) / 2
            val endY = (corners[last + LR_Y] + corners[last + UR_Y]) / 2
            val dirX = directions[later * 2]
            val dirY = directions[later * 2 + 1]
            val fromStart = dirX * (midline.x - startX) + dirY * (midline.y - startY)
            val fromEnd = dirX * (midline.x - endX) + dirY * (midline.y - endY)
            if ((fromStart > 0f) == (fromEnd > 0f)) continue
            val off = abs(dirX * (y - startY) - dirY * (x - startX)) - halfSizes[later]
            if (off <= 0f) return -1f
            margin = min(margin, off)
        }
        return margin
    }

    private fun leading(letter: Int, axis: Int): Float =
        corners[letter * STRIDE + (if (rightToLeft[letter]) LR_X else LL_X) + axis]

    private fun trailing(letter: Int, axis: Int): Float =
        corners[letter * STRIDE + (if (rightToLeft[letter]) LL_X else LR_X) + axis]

    private fun along(line: Int, x: Float, y: Float): Float = directions[line * 2] * x + directions[line * 2 + 1] * y

    private fun onMidline(line: Int, x: Float, y: Float): Point {
        val first = lineStarts[line] * STRIDE
        val originX = (corners[first + LL_X] + corners[first + UL_X]) / 2
        val originY = (corners[first + LL_Y] + corners[first + UL_Y]) / 2
        val dirX = directions[line * 2]
        val dirY = directions[line * 2 + 1]
        val distance = dirX * (x - originX) + dirY * (y - originY)
        return Point(originX + dirX * distance, originY + dirY * distance)
    }

    private fun lineOf(letter: Int): Int {
        var low = 0
        var high = lineStarts.size - 2
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lineStarts[middle] <= letter) low = middle else high = middle - 1
        }
        return low
    }

    private fun boxDistance(letter: Int, x: Float, y: Float): Float {
        val offset = letter * STRIDE
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (corner in 0 until STRIDE step 2) {
            left = minOf(left, corners[offset + corner])
            right = maxOf(right, corners[offset + corner])
            top = minOf(top, corners[offset + corner + 1])
            bottom = maxOf(bottom, corners[offset + corner + 1])
        }
        return hypot(max(max(left - x, x - right), 0f), max(max(top - y, y - bottom), 0f))
    }

    private fun centreDistance(letter: Int, x: Float, y: Float): Float = hypot(centre(letter, X) - x, centre(letter, Y) - y)

    private fun centre(letter: Int, axis: Int): Float {
        val offset = letter * STRIDE + axis
        return (corners[offset + UL_X] + corners[offset + UR_X] + corners[offset + LL_X] + corners[offset + LR_X]) / POINTS
    }

    class Builder {
        private var codes = IntArray(INITIAL_LETTERS)
        private var corners = FloatArray(INITIAL_LETTERS * STRIDE)
        private var sizes = FloatArray(INITIAL_LETTERS)
        private var rightToLeft = BooleanArray(INITIAL_LETTERS)
        private var count = 0
        private var lineStarts = IntArray(INITIAL_LINES)
        private var directions = FloatArray(INITIAL_LINES * 2)
        private var halfSizes = FloatArray(INITIAL_LINES)
        private var lines = 0
        private var lineStart = 0
        private var dirX = 1f
        private var dirY = 0f

        fun startLine(dirX: Float, dirY: Float) {
            lineStart = count
            this.dirX = dirX
            this.dirY = dirY
        }

        fun add(code: Int, quad: Quad, size: Float, rightToLeft: Boolean) {
            if (count == codes.size) growLetters()
            codes[count] = code
            val offset = count * STRIDE
            corners[offset + UL_X] = quad.ul_x
            corners[offset + UL_Y] = quad.ul_y
            corners[offset + UR_X] = quad.ur_x
            corners[offset + UR_Y] = quad.ur_y
            corners[offset + LL_X] = quad.ll_x
            corners[offset + LL_Y] = quad.ll_y
            corners[offset + LR_X] = quad.lr_x
            corners[offset + LR_Y] = quad.lr_y
            sizes[count] = size
            this.rightToLeft[count] = rightToLeft
            count++
        }

        fun endLine() {
            if (count == lineStart) return
            if (lines + 1 >= lineStarts.size) {
                lineStarts = lineStarts.copyOf(lineStarts.size * 2)
                directions = directions.copyOf(directions.size * 2)
                halfSizes = halfSizes.copyOf(halfSizes.size * 2)
            }
            lineStarts[lines] = lineStart
            directions[lines * 2] = dirX
            directions[lines * 2 + 1] = dirY
            var largest = 0f
            for (letter in lineStart until count) largest = max(largest, sizes[letter])
            halfSizes[lines] = largest / 2
            lines++
        }

        fun build(): PdfTextLetters {
            val starts = lineStarts.copyOf(lines + 1)
            starts[lines] = count
            return PdfTextLetters(
                codes.copyOf(count),
                corners.copyOf(count * STRIDE),
                sizes.copyOf(count),
                rightToLeft.copyOf(count),
                starts,
                directions.copyOf(lines * 2),
                halfSizes.copyOf(lines)
            )
        }

        private fun growLetters() {
            val capacity = codes.size * 2
            codes = codes.copyOf(capacity)
            corners = corners.copyOf(capacity * STRIDE)
            sizes = sizes.copyOf(capacity)
            rightToLeft = rightToLeft.copyOf(capacity)
        }
    }

    private companion object {
        const val STRIDE = 8
        const val POINTS = 4
        const val UL_X = 0
        const val UL_Y = 1
        const val UR_X = 2
        const val UR_Y = 3
        const val LL_X = 4
        const val LL_Y = 5
        const val LR_X = 6
        const val LR_Y = 7
        const val X = 0
        const val Y = 1
        const val SPACE = 0x20
        const val FIRST_PRINTABLE = 0x20
        const val REPLACEMENT = 0xFFFD
        const val SPACE_GAP = 0.15f
        const val CLEAR_STEPS = 40
        const val CLEAR_SPAN = 0.9f
        const val CLEAR_MARGIN = 0.25f
        const val CLEAR_PULL = 0.001f
        const val INITIAL_LETTERS = 256
        const val INITIAL_LINES = 32
        val PUNCTUATION = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt()
        )
        val KEPT_SIGNS = setOf('%'.code, '‰'.code, '‱'.code, '٪'.code, '﹪'.code, '％'.code)
    }
}
