package hu.reelee81.reepdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

class TrailingIconSpan(private val icon: Drawable, private val gap: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val font = paint.fontMetricsInt
            val top = (font.ascent + font.descent - icon.bounds.height()) / 2
            val bottom = top + icon.bounds.height()
            fm.ascent = min(font.ascent, top)
            fm.top = min(font.top, top)
            fm.descent = max(font.descent, bottom)
            fm.bottom = max(font.bottom, bottom)
            fm.leading = font.leading
        }
        return gap + icon.bounds.width()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val font = paint.fontMetricsInt
        val iconTop = y + (font.ascent + font.descent - icon.bounds.height()) / 2f
        canvas.withTranslation(x + gap, iconTop) { icon.draw(this) }
    }
}
