package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.edit_line_width
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerEditPanels(
    state: ViewerState,
    colors: ReeColors,
    viewerColors: ViewerColors,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val editor = state.editor
    val panel = editor.panel
    val row = editor.rowOpen
    if (panel == null && !row) return
    val tool = editor.settings.swatchTool
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pointerInput(editor) { closeOnTouch(editor) })
        if (row) ViewerToolRow(editor, colors, viewerColors, maxWidth)
        if (panel == null) return@BoxWithConstraints
        val palette = tool.palette ?: return@BoxWithConstraints
        val cell = minOf(CELL, (maxWidth - PANEL_EDGE * 2 - PANEL_PADDING * 2) / COLUMNS)
        val underRow = row && panel == ViewerEditPanel.WIDTH
        val iconCenter = when {
            underRow -> ViewerEditBarLayout.rowCenter(tool, maxWidth.value)
            panel == ViewerEditPanel.COLORS -> ViewerEditBarLayout.center(ViewerEditBarLayout.SWATCH, maxWidth.value)
            else -> ViewerEditBarLayout.center(ViewerEditBarLayout.GROUP, maxWidth.value)
        }
        val top = if (underRow) ViewerEditBarLayout.ROW_HEIGHT.dp + PANEL_MARGIN else PANEL_MARGIN
        val place = ViewerEditPanelPlace.of(
            width = maxWidth.value,
            panelWidth = (cell * COLUMNS + PANEL_PADDING * 2).value,
            iconCenter = iconCenter,
            margin = PANEL_EDGE.value,
            corner = PANEL_RADIUS.value + TAIL_HEIGHT.value
        )
        val shape = BubbleShape(with(LocalDensity.current) { place.tail.dp.toPx() }, TAIL_HEIGHT, PANEL_RADIUS)
        Box(
            Modifier.offset(x = place.left.dp, y = top)
                .shadow(PANEL_SHADOW, shape)
                .background(colors.alertdialogBackgroundColor.default, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = PANEL_PADDING, top = PANEL_PADDING + TAIL_HEIGHT, end = PANEL_PADDING, bottom = PANEL_PADDING)
        ) {
            when (panel) {
                ViewerEditPanel.COLORS -> Palette(editor, tool, palette, colors, viewerColors, cell)
                ViewerEditPanel.WIDTH -> Widths(state, tool, palette, colors, fontFamily, cell * COLUMNS)
            }
        }
    }
}

internal class ViewerEditPanelPlace(val left: Float, val tail: Float) {
    companion object {
        const val WIDE = 600f

        fun of(width: Float, panelWidth: Float, iconCenter: Float, margin: Float, corner: Float): ViewerEditPanelPlace {
            val left = if (width < WIDE) {
                ((width - panelWidth) / 2).coerceAtLeast(margin)
            } else {
                (iconCenter - panelWidth / 2).coerceIn(margin, (width - margin - panelWidth).coerceAtLeast(margin))
            }
            val tail = (iconCenter - left).coerceIn(corner, (panelWidth - corner).coerceAtLeast(corner))
            return ViewerEditPanelPlace(left, tail)
        }
    }
}

private class BubbleShape(private val tail: Float, private val tailHeight: Dp, private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val height = with(density) { tailHeight.toPx() }
        val corner = with(density) { radius.toPx() }
        val body = Path().apply { addRoundRect(RoundRect(0f, height, size.width, size.height, CornerRadius(corner))) }
        val point = Path().apply {
            moveTo(tail - height, height + 1f)
            lineTo(tail, 0f)
            lineTo(tail + height, height + 1f)
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, point))
    }
}

private suspend fun PointerInputScope.closeOnTouch(editor: ViewerEditor) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        editor.closePanel()
    }
}

@Composable
private fun Palette(
    editor: ViewerEditor,
    tool: ViewerEditTool,
    palette: ViewerEditPalette,
    colors: ReeColors,
    viewerColors: ViewerColors,
    cell: Dp
) {
    val chosen = editor.settings.colorIndex(tool)
    val ring = colors.normalTextColor.default
    Column {
        for (row in 0 until (palette.size + COLUMNS - 1) / COLUMNS) {
            Row {
                for (column in 0 until COLUMNS) {
                    val index = row * COLUMNS + column
                    if (index >= palette.size) {
                        Spacer(Modifier.size(cell))
                        continue
                    }
                    val name = stringResource(palette.name(index))
                    val selected = index == chosen
                    val color = Color(OPAQUE or palette.color(index))
                    Canvas(
                        Modifier.size(cell)
                            .clickable(interactionSource = null, indication = null, role = Role.RadioButton) { editor.chooseColor(index) }
                            .semantics {
                                contentDescription = name
                                this.selected = selected
                            }
                    ) {
                        val radius = CIRCLE.toPx() / 2
                        val outline = OUTLINE_WIDTH.toPx()
                        drawCircle(color, radius)
                        drawCircle(viewerColors.colorOutline, radius - outline / 2, style = Stroke(outline))
                        if (selected) {
                            val width = RING_WIDTH.toPx()
                            drawCircle(ring, radius + RING_GAP.toPx() + width / 2, style = Stroke(width))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Widths(
    state: ViewerState,
    tool: ViewerEditTool,
    palette: ViewerEditPalette,
    colors: ReeColors,
    fontFamily: FontFamily,
    width: Dp
) {
    val editor = state.editor
    val density = LocalDensity.current
    val index = editor.settings.widthIndex(tool)
    val value = palette.widths[index]
    val text = value.roundToInt().toString()
    val label = stringResource(Res.string.edit_line_width)
    val sample = colors.normalTextColor.default
    val window = LocalWindowInfo.current.containerSize
    val fitZoom = fitZoom(state, minOf(window.width, window.height))
    val thumb = colors.switchThumbColor.of(ReeColorState.CHECKED)
    val filled = colors.switchTrackColor.of(ReeColorState.CHECKED)
    val empty = colors.switchTrackColor.default
    val stops = palette.widths.size
    val valueStyle = TextStyle(
        color = colors.normalTextColor.default,
        fontSize = with(density) { VALUE_TEXT_SIZE.toSp() },
        fontFamily = fontFamily,
        textAlign = TextAlign.End
    )
    val measurer = rememberTextMeasurer()
    val valueWidth = remember(palette, valueStyle, density, measurer) {
        with(density) { palette.widths.maxOf { measurer.measure(it.roundToInt().toString(), valueStyle).size.width }.toDp() }
    }
    Row(Modifier.width(width).height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(SAMPLE_WIDTH, ROW_HEIGHT)) {
            val thickness = (value * fitZoom).coerceIn(1f, SAMPLE_MAX.toPx())
            val inset = SIDE_INSET.toPx()
            drawLine(sample, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), thickness, StrokeCap.Butt)
        }
        Canvas(
            Modifier.weight(1f)
                .height(ROW_HEIGHT)
                .pointerInput(stops) {
                    detectTapGestures { editor.chooseWidth(stopAt(it.x, size.width, stops, STOP_INSET.toPx())) }
                }
                .pointerInput(stops) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        editor.chooseWidth(stopAt(change.position.x, size.width, stops, STOP_INSET.toPx()))
                    }
                }
                .semantics {
                    contentDescription = label
                    stateDescription = text
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..(stops - 1).toFloat(), stops - 2)
                    setProgress { target ->
                        editor.chooseWidth(target.roundToInt().coerceIn(0, stops - 1))
                        true
                    }
                }
        ) {
            val inset = STOP_INSET.toPx()
            val step = (size.width - inset * 2) / (stops - 1)
            val y = size.height / 2
            val track = TRACK_WIDTH.toPx()
            val thumbX = inset + step * index
            drawLine(empty, Offset(inset, y), Offset(size.width - inset, y), track, StrokeCap.Round)
            drawLine(filled, Offset(inset, y), Offset(thumbX, y), track, StrokeCap.Round)
            for (stop in 0 until stops) drawCircle(if (stop <= index) filled else empty, STOP_RADIUS.toPx(), Offset(inset + step * stop, y))
            drawCircle(thumb, THUMB_RADIUS.toPx(), Offset(thumbX, y))
        }
        BasicText(
            text = text,
            modifier = Modifier.padding(horizontal = SIDE_INSET).width(valueWidth),
            style = valueStyle,
            maxLines = 1
        )
    }
}

private fun stopAt(x: Float, width: Int, stops: Int, inset: Float): Int {
    val step = (width - inset * 2) / (stops - 1)
    return ((x - inset) / step).roundToInt().coerceIn(0, stops - 1)
}

private fun fitZoom(state: ViewerState, shortSide: Int): Float {
    val pages = state.layout ?: return 1f
    val side = if (shortSide > 0) shortSide else state.viewport?.width ?: return 1f
    val width = pages.width(state.currentPage)
    return if (width > 0) (side / width).toFloat() else 1f
}

internal const val MARK_ALPHA = 0.4f
private const val COLUMNS = 7
private val CELL = 48.dp
private val CIRCLE = 32.dp
private val RING_GAP = 4.dp
private val RING_WIDTH = 2.dp
private val PANEL_MARGIN = 8.dp
private val PANEL_EDGE = 16.dp
private val PANEL_PADDING = 8.dp
private val PANEL_RADIUS = 12.dp
private val PANEL_SHADOW = 8.dp
private val TAIL_HEIGHT = 8.dp
private val ROW_HEIGHT = 56.dp
private val SAMPLE_WIDTH = 56.dp
private val SIDE_INSET = 8.dp
private val SAMPLE_MAX = ROW_HEIGHT - SIDE_INSET * 2
private val SLIDER_GAP = 16.dp
private val STOP_RADIUS = 4.dp
private val STOP_INSET = SLIDER_GAP - SIDE_INSET + STOP_RADIUS
private val THUMB_RADIUS = 10.dp
private val TRACK_WIDTH = 4.dp
private val VALUE_TEXT_SIZE = 16.dp
