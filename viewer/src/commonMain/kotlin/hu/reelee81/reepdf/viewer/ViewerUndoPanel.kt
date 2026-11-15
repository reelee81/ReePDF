package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.edit_redo
import hu.reelee81.reepdf.viewer.resources.edit_undo
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BoxScope.ViewerUndoPanel(editor: ViewerEditor, colors: ReeColors, viewerColors: ViewerColors) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(Offset.Zero) }
    val top = editor.settings.undoPanelTop
    BoxWithConstraints(Modifier.matchParentSize()) {
        val margin = with(density) { PANEL_MARGIN.toPx() }
        val height = with(density) { PANEL_HEIGHT.toPx() }
        val bottomY = (constraints.maxHeight - margin - height).coerceAtLeast(margin)
        val homeY = if (top) margin else bottomY
        val currentY = homeY + moved.y
        val nearTop = abs(currentY - margin) <= abs(currentY - bottomY)
        if (dragging) {
            for (anchorTop in listOf(true, false)) {
                val y = if (anchorTop) margin else bottomY
                val shape = RoundedCornerShape(PANEL_RADIUS)
                val look = if (anchorTop == nearTop) {
                    Modifier.background(viewerColors.dropTarget.copy(alpha = NEAR_ALPHA), shape)
                } else {
                    Modifier.border(ANCHOR_OUTLINE, viewerColors.dropTarget, shape)
                }
                Box(
                    Modifier.offset { IntOffset(margin.roundToInt(), y.roundToInt()) }
                        .size(PANEL_WIDTH, PANEL_HEIGHT)
                        .then(look)
                )
            }
        }
        Column(
            Modifier.offset { IntOffset((margin + moved.x).roundToInt(), currentY.roundToInt()) }
                .size(PANEL_WIDTH, PANEL_HEIGHT)
                .shadow(PANEL_SHADOW, RoundedCornerShape(PANEL_RADIUS))
                .background(colors.alertdialogBackgroundColor.default, RoundedCornerShape(PANEL_RADIUS))
                .pointerInput(editor, homeY, bottomY) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragging = true
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            moved += amount
                        },
                        onDragEnd = {
                            val y = homeY + moved.y
                            editor.placeUndoPanel(abs(y - margin) <= abs(y - bottomY))
                            moved = Offset.Zero
                            dragging = false
                        },
                        onDragCancel = {
                            moved = Offset.Zero
                            dragging = false
                        }
                    )
                }
        ) {
            val tint = colors.normalTextColor.default
            UndoButton(ViewerIcons.undo, stringResource(Res.string.edit_undo), editor.state.canUndo, tint, editor::undo)
            UndoButton(ViewerIcons.redo, stringResource(Res.string.edit_redo), editor.state.canRedo, tint, editor::redo)
        }
    }
}

@Composable
private fun UndoButton(icon: ImageVector, label: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val press = if (enabled) {
        Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        Modifier.size(BUTTON_SIZE)
            .then(press)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center
    ) {
        Image(rememberVectorPainter(icon), null, Modifier.size(ICON_SIZE), colorFilter = ColorFilter.tint(tint))
    }
}

private const val NEAR_ALPHA = 0.5f
private val ANCHOR_OUTLINE = 2.dp
private val PANEL_SHADOW = 8.dp
private val PANEL_MARGIN = 16.dp
private val PANEL_WIDTH = 48.dp
private val PANEL_HEIGHT = 96.dp
private val PANEL_RADIUS = 12.dp
private val BUTTON_SIZE = 48.dp
private val ICON_SIZE = 24.dp
