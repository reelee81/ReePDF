package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

internal class ViewerHandleGrab(val handle: ViewerSelection.Handle, val anchor: Offset, val lineHeight: Float)

internal object ViewerSelectionHandles {
    val RADIUS = 12.dp
    val TOUCH_SLOP = 12.dp

    fun grab(state: ViewerState, position: Offset, radius: Float, slop: Float): ViewerHandleGrab? {
        val view = state.viewport ?: return null
        var best: ViewerHandleGrab? = null
        var bestDistance = Float.MAX_VALUE
        for (kind in ViewerSelection.Handle.entries) {
            val handle = handleOf(state, kind) ?: continue
            val anchor = state.handlePoint(handle) ?: continue
            val distance = (position - center(anchor, kind, radius)).getDistance()
            if (distance <= radius + slop && distance < bestDistance) {
                best = ViewerHandleGrab(kind, anchor, handle.lineHeight * view.zoom)
                bestDistance = distance
            }
        }
        return best
    }

    fun DrawScope.drawHandles(state: ViewerState, radius: Float, color: Color) {
        for (kind in ViewerSelection.Handle.entries) {
            val handle = handleOf(state, kind) ?: continue
            val anchor = state.handlePoint(handle) ?: continue
            drawCircle(color, radius, center(anchor, kind, radius))
            val left = if (kind == ViewerSelection.Handle.START) anchor.x - radius else anchor.x
            drawRect(color, Offset(left, anchor.y), Size(radius, radius))
        }
    }

    private fun handleOf(state: ViewerState, kind: ViewerSelection.Handle): ViewerTextHandle? =
        if (kind == ViewerSelection.Handle.START) state.selection.startHandle() else state.selection.endHandle()

    private fun center(anchor: Offset, kind: ViewerSelection.Handle, radius: Float): Offset =
        Offset(if (kind == ViewerSelection.Handle.START) anchor.x - radius else anchor.x + radius, anchor.y + radius)
}
