package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

object ViewerBubblePlacement {
    fun place(selection: Rect, bubble: Size, view: Size, gap: Float, handleHeight: Float, margin: Float): Offset? {
        if (selection.right < 0f || selection.left > view.width || selection.bottom < 0f || selection.top > view.height) return null
        val shown = selection.intersect(Rect(Offset.Zero, view))
        val x = (shown.center.x - bubble.width / 2).coerceIn(margin, max(margin, view.width - margin - bubble.width))
        val above = shown.top - gap - bubble.height
        val below = shown.bottom + handleHeight + gap
        val y = when {
            above >= margin -> above
            below + bubble.height <= view.height - margin -> below
            else -> (view.height - bubble.height) / 2
        }
        return Offset(x, y)
    }
}
