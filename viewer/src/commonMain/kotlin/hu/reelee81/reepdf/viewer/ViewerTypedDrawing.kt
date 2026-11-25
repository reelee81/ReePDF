package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal class ViewerTypedGlyphs(private val measurer: TextMeasurer, private val fontFamily: FontFamily) {
    private val glyphs = ViewerRecentCache<Long, TextLayoutResult>(GLYPH_LIMIT)
    private val placement = Matrix()

    fun DrawScope.drawTyped(mark: ViewerMark, font: ViewerTextFont, left: Float, top: Float, zoom: Float) {
        val text = mark.text ?: return
        val m = text.matrix
        val color = Color(OPAQUE or mark.color)
        val scale = text.size / REFERENCE * zoom
        val lineHeight = ViewerMarkText.LINE_HEIGHT * text.size
        for ((row, line) in ViewerTypedLayout.lines(font, text.text).withIndex()) {
            if (line.text.isEmpty() || !lineShown(text, row, line.width, left, top, zoom)) continue
            val baseline = -(ViewerMarkText.ASCENT * text.size + row * lineHeight)
            var index = 0
            while (index < line.text.length) {
                val pair = line.text[index].isHighSurrogate() && index + 1 < line.text.length && line.text[index + 1].isLowSurrogate()
                val codePoint = if (pair) 0x10000 + ((line.text[index].code - 0xD800) shl 10) + (line.text[index + 1].code - 0xDC00) else line.text[index].code
                if (pair || !line.text[index].isWhitespace()) {
                    val glyph = glyph(text.weight, codePoint)
                    val origin = text.point(line.offsets[index] * text.size, baseline)
                    placement.reset()
                    placement.values.also { values ->
                        values[Matrix.ScaleX] = m[0] * scale
                        values[Matrix.SkewY] = m[1] * scale
                        values[Matrix.SkewX] = -m[2] * scale
                        values[Matrix.ScaleY] = -m[3] * scale
                        values[Matrix.TranslateX] = left + origin.x * zoom
                        values[Matrix.TranslateY] = top + origin.y * zoom
                    }
                    withTransform({ transform(placement) }) {
                        drawText(glyph, color = color, topLeft = Offset(0f, -glyph.firstBaseline))
                    }
                }
                index += if (pair) 2 else 1
            }
        }
    }

    private fun DrawScope.lineShown(text: ViewerMarkText, row: Int, width: Float, left: Float, top: Float, zoom: Float): Boolean {
        val lineHeight = ViewerMarkText.LINE_HEIGHT * text.size
        val upper = -row * lineHeight
        val lower = upper - lineHeight
        val right = width * text.size + text.size
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((x, y) in listOf(-text.size to upper, right to upper, right to lower, -text.size to lower)) {
            val point = text.point(x, y)
            val screenX = left + point.x * zoom
            val screenY = top + point.y * zoom
            minX = min(minX, screenX)
            minY = min(minY, screenY)
            maxX = max(maxX, screenX)
            maxY = max(maxY, screenY)
        }
        return maxX >= 0f && maxY >= 0f && minX <= size.width && minY <= size.height
    }

    private fun glyph(weight: Int, codePoint: Int): TextLayoutResult = glyphs.getOrPut((weight.toLong() shl 32) or codePoint.toLong()) {
        val text = if (codePoint >= 0x10000) {
            val shifted = codePoint - 0x10000
            charArrayOf(Char(0xD800 + (shifted shr 10)), Char(0xDC00 + (shifted and 0x3FF))).concatToString()
        } else {
            Char(codePoint).toString()
        }
        measurer.measure(
            text = text,
            style = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(weight), fontSize = REFERENCE.sp, letterSpacing = 0.sp),
            softWrap = false,
            maxLines = 1,
            density = UNIT_DENSITY,
            skipCache = true
        )
    }

    private companion object {
        const val GLYPH_LIMIT = 512
        const val REFERENCE = 64f
        val UNIT_DENSITY = Density(1f, 1f)
    }
}

internal object ViewerTypedFrame {
    fun screenFrame(box: FloatArray, left: Float, top: Float, zoom: Float, margin: Float): Rect {
        return Rect(left + box[0] * zoom - margin, top + box[1] * zoom - margin, left + box[2] * zoom + margin, top + box[3] * zoom + margin)
    }

    fun caretBox(text: ViewerMarkText, font: ViewerTextFont, cursor: Int): Rect {
        val (from, to) = ViewerTypedLayout.caretLine(font, text, cursor)
        return Rect(min(from.x, to.x), min(from.y, to.y), max(from.x, to.x), max(from.y, to.y))
    }

    fun DrawScope.drawCaret(text: ViewerMarkText, font: ViewerTextFont, cursor: Int, left: Float, top: Float, zoom: Float, color: Color, width: Float) {
        val (from, to) = ViewerTypedLayout.caretLine(font, text, cursor)
        drawLine(color, Offset(left + from.x * zoom, top + from.y * zoom), Offset(left + to.x * zoom, top + to.y * zoom), width, StrokeCap.Butt)
    }
}

internal class ViewerSizeLabel(private val measurer: TextMeasurer, private val fontFamily: FontFamily, private val separator: String) {
    fun DrawScope.drawSize(textSize: Float, frame: Rect, zoom: Float, color: Color, background: Color, pageTop: Float) {
        val fontPx = ViewerTypedLabel.fontSize(textSize, zoom, LABEL_SMALLEST.toPx(), LABEL_LARGEST.toPx())
        val layout = measurer.measure(
            text = ViewerTypedLabel.text(textSize, separator),
            style = TextStyle(color = color, fontSize = fontPx.toSp(), fontFamily = fontFamily),
            softWrap = false,
            maxLines = 1
        )
        val padX = LABEL_PADDING_X.toPx()
        val padY = LABEL_PADDING_Y.toPx()
        val width = layout.size.width + padX * 2
        val height = layout.size.height + padY * 2
        val y = ViewerTypedLabel.top(frame.top, frame.bottom, height, LABEL_GAP.toPx(), max(pageTop, 0f))
        drawRoundRect(background.copy(alpha = LABEL_BACKGROUND_ALPHA), Offset(frame.left, y), Size(width, height), CornerRadius(LABEL_RADIUS.toPx()))
        drawText(layout, topLeft = Offset(frame.left + padX, y + padY))
    }

    private companion object {
        val LABEL_SMALLEST = 10.dp
        val LABEL_LARGEST = 24.dp
        val LABEL_PADDING_X = 4.dp
        val LABEL_PADDING_Y = 1.dp
        val LABEL_GAP = 2.dp
        val LABEL_RADIUS = 4.dp
        const val LABEL_BACKGROUND_ALPHA = 0.85f
    }
}

internal object ViewerTypedLabel {
    fun text(size: Float, separator: String): String {
        val tenths = (size.toDouble() * TENTHS).roundToLong()
        return "${tenths / TENTHS}$separator${tenths % TENTHS}"
    }

    fun fontSize(textSize: Float, zoom: Float, smallest: Float, largest: Float): Float = (textSize * zoom * SCALE).coerceIn(smallest, largest)

    fun top(frameTop: Float, frameBottom: Float, height: Float, gap: Float, limit: Float): Float {
        val above = frameTop - gap - height
        return if (above >= limit) above else frameBottom + gap
    }

    private const val TENTHS = 10
    private const val SCALE = 0.75f
}
