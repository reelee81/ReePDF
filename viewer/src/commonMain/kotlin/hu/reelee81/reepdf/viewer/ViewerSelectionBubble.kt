package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.selection_copy
import hu.reelee81.reepdf.viewer.resources.selection_select_all
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerSelectionBubble(
    state: ViewerState,
    colors: ReeColors,
    fontFamily: FontFamily,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val page = state.currentPage
    val showSelectAll = !state.selection.isWholePage(page)
    Layout(
        content = {
            Row(
                Modifier.height(HEIGHT)
                    .shadow(ELEVATION, CircleShape)
                    .background(colors.alertdialogBackgroundColor.default, CircleShape)
                    .padding(horizontal = ROW_PADDING),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BubbleButton(stringResource(Res.string.selection_copy), colors, fontFamily, onCopy)
                if (showSelectAll) {
                    BubbleButton(stringResource(Res.string.selection_select_all), colors, fontFamily) {
                        state.selection.selectAll(page)
                    }
                }
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val bubble = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val position = state.selectionBounds()?.let { bounds ->
            ViewerBubblePlacement.place(
                selection = bounds,
                bubble = Size(bubble.width.toFloat(), bubble.height.toFloat()),
                view = Size(width.toFloat(), height.toFloat()),
                gap = GAP.toPx(),
                handleHeight = HANDLE_HEIGHT.toPx(),
                margin = MARGIN.toPx()
            )
        }
        layout(width, height) {
            if (position != null) bubble.place(position.x.roundToInt(), position.y.roundToInt())
        }
    }
}

@Composable
private fun BubbleButton(text: String, colors: ReeColors, fontFamily: FontFamily, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val textColor = colors.materialTextbuttonTextColor.let { if (pressed) it.of(ReeColorState.PRESSED) else it.default }
    Box(
        Modifier.fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = textColor, fontSize = TEXT_SIZE, fontFamily = fontFamily, fontWeight = FontWeight.Normal),
            maxLines = 1
        )
    }
}

private val HEIGHT = 35.dp
private val ELEVATION = 8.dp
private val ROW_PADDING = 8.dp
private val BUTTON_PADDING = 8.dp
private val TEXT_SIZE = 14.sp
private val GAP = 8.dp
private val HANDLE_HEIGHT = 24.dp
private val MARGIN = 8.dp
