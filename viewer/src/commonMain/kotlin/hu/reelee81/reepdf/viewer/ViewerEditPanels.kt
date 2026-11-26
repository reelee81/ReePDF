package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.decimal_separator
import hu.reelee81.reepdf.viewer.resources.edit_form_check
import hu.reelee81.reepdf.viewer.resources.edit_form_dash
import hu.reelee81.reepdf.viewer.resources.edit_form_dot
import hu.reelee81.reepdf.viewer.resources.edit_form_x
import hu.reelee81.reepdf.viewer.resources.edit_line_width
import hu.reelee81.reepdf.viewer.resources.edit_signature_1
import hu.reelee81.reepdf.viewer.resources.edit_signature_2
import hu.reelee81.reepdf.viewer.resources.edit_signature_add
import hu.reelee81.reepdf.viewer.resources.edit_signature_delete
import hu.reelee81.reepdf.viewer.resources.edit_signature_edit
import hu.reelee81.reepdf.viewer.resources.edit_signature_empty
import hu.reelee81.reepdf.viewer.resources.edit_text_weight
import hu.reelee81.reepdf.viewer.resources.edit_underline_straight
import hu.reelee81.reepdf.viewer.resources.edit_underline_wavy
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
        val contentWidth = if (panel == ViewerEditPanel.STYLE) STYLE_WIDTH else cell * COLUMNS
        val underRow = row && (panel == ViewerEditPanel.WIDTH || panel == ViewerEditPanel.STYLE || panel == ViewerEditPanel.SIGNATURES || panel == ViewerEditPanel.MARKS)
        val iconCenter = when {
            underRow -> ViewerEditBarLayout.rowCenter(tool, maxWidth.value)
            panel == ViewerEditPanel.COLORS -> ViewerEditBarLayout.center(ViewerEditBarLayout.SWATCH, maxWidth.value)
            else -> ViewerEditBarLayout.center(ViewerEditBarLayout.GROUP, maxWidth.value)
        }
        val top = if (underRow) ViewerEditBarLayout.ROW_HEIGHT.dp + PANEL_MARGIN else PANEL_MARGIN
        val place = ViewerEditPanelPlace.of(
            width = maxWidth.value,
            panelWidth = (contentWidth + PANEL_PADDING * 2).value,
            iconCenter = iconCenter,
            margin = PANEL_EDGE.value,
            corner = PANEL_RADIUS.value + TAIL_HEIGHT.value,
            underIcon = panel == ViewerEditPanel.STYLE
        )
        val shape = BubbleShape(with(LocalDensity.current) { place.tail.dp.toPx() })
        Box(
            Modifier.offset(x = place.left.dp, y = top)
                .shadow(PANEL_SHADOW, shape)
                .background(colors.alertdialogBackgroundColor.default, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = PANEL_PADDING, top = PANEL_PADDING + TAIL_HEIGHT, end = PANEL_PADDING, bottom = PANEL_PADDING)
        ) {
            when (panel) {
                ViewerEditPanel.COLORS -> Palette(editor, tool, palette, colors, viewerColors, cell)
                ViewerEditPanel.WIDTH -> if (tool == ViewerEditTool.TEXT) {
                    Weights(editor, tool, palette, colors, fontFamily, cell * COLUMNS)
                } else {
                    Widths(state, tool, palette, colors, fontFamily, cell * COLUMNS)
                }
                ViewerEditPanel.STYLE -> Styles(editor, colors)
                ViewerEditPanel.SIGNATURES -> Signatures(editor, colors, viewerColors, cell * COLUMNS)
                ViewerEditPanel.MARKS -> FormMarks(state, palette, colors, viewerColors, fontFamily, cell * COLUMNS)
            }
        }
    }
}

internal class ViewerEditPanelPlace(val left: Float, val tail: Float) {
    companion object {
        const val WIDE = 600f

        fun of(width: Float, panelWidth: Float, iconCenter: Float, margin: Float, corner: Float, underIcon: Boolean): ViewerEditPanelPlace {
            val left = if (width < WIDE && !underIcon) {
                ((width - panelWidth) / 2).coerceAtLeast(margin)
            } else {
                (iconCenter - panelWidth / 2).coerceIn(margin, (width - margin - panelWidth).coerceAtLeast(margin))
            }
            val tail = (iconCenter - left).coerceIn(corner, (panelWidth - corner).coerceAtLeast(corner))
            return ViewerEditPanelPlace(left, tail)
        }
    }
}

private class BubbleShape(private val tail: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val height = with(density) { TAIL_HEIGHT.toPx() }
        val corner = with(density) { PANEL_RADIUS.toPx() }
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
    val chosen = editor.shownColorIndex(tool)
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
    val window = LocalWindowInfo.current.containerSize
    val fitZoom = fitZoom(state, minOf(window.width, window.height))
    ViewerWidthSlider(palette.widths, editor.settings.widthIndex(tool), { it * fitZoom }, colors, fontFamily, editor::chooseWidth, Modifier.width(width))
}

@Composable
private fun Weights(editor: ViewerEditor, tool: ViewerEditTool, palette: ViewerEditPalette, colors: ReeColors, fontFamily: FontFamily, width: Dp) {
    val density = LocalDensity.current
    val index = editor.shownWidthIndex(tool)
    val weight = palette.widths[index].roundToInt()
    val name = ViewerMarkText.weightName(weight)
    val style = TextStyle(
        color = colors.normalTextColor.default,
        fontSize = with(density) { WEIGHT_NAME_SIZE.toSp() },
        fontFamily = fontFamily,
        fontWeight = FontWeight(weight),
        textAlign = TextAlign.Center
    )
    Column(Modifier.width(width).padding(horizontal = WEIGHT_EDGE)) {
        BasicText(name, Modifier.fillMaxWidth().padding(top = WEIGHT_NAME_TOP), style = style, maxLines = 1)
        StopSlider(
            palette.widths.size,
            index,
            stringResource(Res.string.edit_text_weight),
            name,
            colors,
            editor::chooseWidth,
            Modifier.fillMaxWidth(),
            onStart = editor::beginWidthChoice,
            onEnd = editor::endWidthChoice
        )
    }
}

@Composable
private fun FormMarks(
    state: ViewerState,
    palette: ViewerEditPalette,
    colors: ReeColors,
    viewerColors: ViewerColors,
    fontFamily: FontFamily,
    width: Dp
) {
    val editor = state.editor
    val chosen = editor.shownFormSymbol
    val ink = colors.normalTextColor.default
    val ring = viewerColors.signatureCheck
    val window = LocalWindowInfo.current.containerSize
    val fitZoom = fitZoom(state, minOf(window.width, window.height))
    Column(Modifier.width(width)) {
        Row(Modifier.fillMaxWidth().height(CELL)) {
            for (symbol in ViewerFormSymbol.entries) {
                val name = stringResource(FORM_SYMBOL_NAMES.getValue(symbol))
                val selected = symbol == chosen
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, role = Role.RadioButton) { editor.chooseFormSymbol(symbol) }
                        .semantics {
                            contentDescription = name
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = RING_WIDTH.toPx()
                            drawCircle(ring, FORM_ICON_SIZE.toPx() / 2 + RING_GAP.toPx() + stroke / 2, style = Stroke(stroke))
                        }
                    }
                    Image(rememberVectorPainter(ViewerFormIcons.of(symbol)), null, Modifier.size(FORM_ICON_SIZE), colorFilter = ColorFilter.tint(ink))
                }
            }
        }
        ViewerWidthSlider(
            values = palette.widths,
            index = editor.shownWidthIndex(ViewerEditTool.FORM_MARK),
            thickness = { it * fitZoom },
            colors = colors,
            fontFamily = fontFamily,
            onChoose = editor::chooseWidth,
            modifier = Modifier.fillMaxWidth(),
            onStart = editor::beginWidthChoice,
            onEnd = editor::endWidthChoice
        )
    }
}

@Composable
internal fun ViewerWidthSlider(
    values: FloatArray,
    index: Int,
    thickness: (Float) -> Float,
    colors: ReeColors,
    fontFamily: FontFamily,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {}
) {
    val density = LocalDensity.current
    val value = values[index]
    val separator = stringResource(Res.string.decimal_separator)
    val text = widthText(value, separator)
    val label = stringResource(Res.string.edit_line_width)
    val sample = colors.normalTextColor.default
    val valueStyle = TextStyle(
        color = colors.normalTextColor.default,
        fontSize = with(density) { VALUE_TEXT_SIZE.toSp() },
        fontFamily = fontFamily,
        textAlign = TextAlign.End
    )
    val measurer = rememberTextMeasurer()
    val valueWidth = remember(values, valueStyle, density, measurer, separator) {
        with(density) { values.maxOf { measurer.measure(widthText(it, separator), valueStyle).size.width }.toDp() }
    }
    Row(modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(SAMPLE_WIDTH, ROW_HEIGHT)) {
            val line = thickness(value).coerceIn(1f, SAMPLE_MAX.toPx())
            val inset = SIDE_INSET.toPx()
            drawLine(sample, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), line, StrokeCap.Butt)
        }
        StopSlider(values.size, index, label, text, colors, onChoose, Modifier.weight(1f), onStart = onStart, onEnd = onEnd)
        BasicText(
            text = text,
            modifier = Modifier.padding(horizontal = SIDE_INSET).width(valueWidth),
            style = valueStyle,
            maxLines = 1
        )
    }
}

@Composable
private fun StopSlider(
    stops: Int,
    index: Int,
    label: String,
    stateText: String?,
    colors: ReeColors,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {}
) {
    val thumb = colors.switchThumbColor.of(ReeColorState.CHECKED)
    val filled = colors.switchTrackColor.of(ReeColorState.CHECKED)
    val empty = colors.switchTrackColor.default
    Canvas(
        modifier
            .height(ROW_HEIGHT)
            .pointerInput(stops) {
                detectTapGestures { onChoose(stopAt(it.x, size.width, stops, STOP_INSET.toPx())) }
            }
            .pointerInput(stops) {
                detectHorizontalDragGestures(onDragStart = { onStart() }, onDragEnd = onEnd, onDragCancel = onEnd) { change, _ ->
                    change.consume()
                    onChoose(stopAt(change.position.x, size.width, stops, STOP_INSET.toPx()))
                }
            }
            .semantics {
                contentDescription = label
                if (stateText != null) stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..(stops - 1).toFloat(), stops - 2)
                setProgress { target ->
                    onChoose(target.roundToInt().coerceIn(0, stops - 1))
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
}

@Composable
private fun Styles(editor: ViewerEditor, colors: ReeColors) {
    val chosen = editor.settings.underlineStyle
    val sample = colors.normalTextColor.default
    val check = colors.icCheckboxPasswordColor
    Row(Modifier.height(ROW_HEIGHT).padding(horizontal = SIDE_INSET), verticalAlignment = Alignment.CenterVertically) {
        for ((index, entry) in UNDERLINE_STYLES.withIndex()) {
            val (option, label) = entry
            val checked = option == chosen
            val name = stringResource(label)
            if (index > 0) Spacer(Modifier.width(STYLE_GAP))
            Row(
                Modifier.fillMaxHeight()
                    .clickable(interactionSource = null, indication = null, role = Role.RadioButton) { editor.chooseUnderlineStyle(option) }
                    .semantics {
                        contentDescription = name
                        selected = checked
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(Modifier.size(STYLE_SAMPLE_WIDTH, ROW_HEIGHT)) {
                    val letter = STYLE_SAMPLE_LETTER.toPx()
                    val run = floatArrayOf(0f, 0f, size.width, 0f, 0f, -1f, letter, 0f)
                    drawPath(centeredPath(ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, option == ViewerUnderlineStyle.WAVY, ViewerTextLines.SAMPLES), size.height), sample)
                }
                Spacer(Modifier.width(CHECK_GAP))
                Image(
                    rememberVectorPainter(if (checked) ViewerIcons.checkOn else ViewerIcons.checkOff),
                    null,
                    Modifier.size(CHECK_SIZE),
                    colorFilter = ColorFilter.tint(if (checked) check.of(ReeColorState.CHECKED) else check.default)
                )
            }
        }
    }
}

@Composable
private fun Signatures(editor: ViewerEditor, colors: ReeColors, viewerColors: ViewerColors, width: Dp) {
    val saved = editor.signatures.saved
    val chosen = editor.chosenSignature
    val frameWidth = width - SIGNATURE_ICON_WIDTH * 3 - SIGNATURE_GAP * 2
    Column(Modifier.width(width).padding(vertical = SIGNATURE_EDGE)) {
        for (slot in 0 until ViewerSignature.SLOTS) {
            if (slot > 0) Spacer(Modifier.height(SIGNATURE_ROW_GAP))
            val signature = saved[slot]
            val name = stringResource(SIGNATURE_NAMES[slot])
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignatureIcon(ViewerIcons.add, stringResource(Res.string.edit_signature_add, name), signature == null, colors) {
                    editor.openSignaturePad(slot)
                }
                Spacer(Modifier.width(SIGNATURE_GAP))
                SignatureFrame(
                    signature = signature,
                    selected = slot == chosen,
                    name = name,
                    colors = colors,
                    viewerColors = viewerColors,
                    onClick = { editor.chooseSignature(slot) },
                    modifier = Modifier.size(frameWidth, CELL)
                )
                Spacer(Modifier.width(SIGNATURE_GAP))
                SignatureIcon(ViewerIcons.edit, stringResource(Res.string.edit_signature_edit, name), signature != null, colors) {
                    editor.editSignaturePad(slot)
                }
                SignatureIcon(ViewerIcons.remove, stringResource(Res.string.edit_signature_delete, name), signature != null, colors) {
                    editor.signatures.askDelete(slot)
                }
            }
        }
    }
}

@Composable
private fun SignatureIcon(icon: ImageVector, description: String, enabled: Boolean, colors: ReeColors, onClick: () -> Unit) {
    Box(
        Modifier.size(SIGNATURE_ICON_WIDTH, CELL)
            .clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center
    ) {
        Image(rememberVectorPainter(icon), null, Modifier.size(CHECK_SIZE), colorFilter = ColorFilter.tint(colors.normalTextColor.default))
    }
}

@Composable
private fun SignatureFrame(
    signature: ViewerSignature?,
    selected: Boolean,
    name: String,
    colors: ReeColors,
    viewerColors: ViewerColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val empty = stringResource(Res.string.edit_signature_empty)
    val ink = colors.normalTextColor.default
    Box(
        modifier.border(OUTLINE_WIDTH, viewerColors.colorOutline)
            .clickable(enabled = signature != null, interactionSource = null, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
                if (signature == null) stateDescription = empty
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        if (signature != null) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = SIGNATURE_INSET.toPx()
                val right = size.width - (CHECK_SIZE + SIGNATURE_INSET * 2).toPx()
                drawSignature(signature, Rect(inset, inset, right, size.height - inset), ink)
            }
        }
        if (selected) {
            Image(
                rememberVectorPainter(ViewerIcons.check),
                null,
                Modifier.padding(end = SIGNATURE_INSET).size(CHECK_SIZE),
                colorFilter = ColorFilter.tint(viewerColors.signatureCheck)
            )
        }
    }
}

internal fun DrawScope.drawSignature(signature: ViewerSignature, area: Rect, ink: Color) {
    if (area.width <= 0f || area.height <= 0f) return
    val box = signature.bounds()
    val scale = minOf(area.width / (box[2] - box[0]), area.height / (box[3] - box[1]))
    val dx = area.left + (area.width - (box[2] - box[0]) * scale) / 2 - box[0] * scale
    val dy = area.top + (area.height - (box[3] - box[1]) * scale) / 2 - box[1] * scale
    val line = maxOf(signature.width * scale / ViewerSignature.PAD_WIDTH_ON_PAGE, 1f)
    drawPath(signature.path(scale, dx, dy).toPath(0f, 0f, 1f), ink, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun centeredPath(shape: ViewerMarkPath, height: Float): Path {
    val points = shape.points
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (index in 1 until points.size step 2) {
        top = minOf(top, points[index])
        bottom = maxOf(bottom, points[index])
    }
    val shift = height / 2 - (top + bottom) / 2
    val path = Path()
    var index = 0
    for (verb in shape.verbs) {
        when (verb) {
            ViewerMarkPath.MOVE -> path.moveTo(points[index], points[index + 1] + shift)
            ViewerMarkPath.LINE -> path.lineTo(points[index], points[index + 1] + shift)
            ViewerMarkPath.CLOSE -> path.close()
        }
        index += ViewerMarkPath.pointsOf(verb)
    }
    return path
}

internal fun widthText(value: Float, separator: String): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}$separator${tenths % 10}"
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
internal val WIDTH_SLIDER_HEIGHT = ROW_HEIGHT
private val SAMPLE_WIDTH = 56.dp
private val SIDE_INSET = 8.dp
private val SAMPLE_MAX = ROW_HEIGHT - SIDE_INSET * 2
private val SLIDER_GAP = 16.dp
private val STOP_RADIUS = 4.dp
private val STOP_INSET = SLIDER_GAP - SIDE_INSET + STOP_RADIUS
private val THUMB_RADIUS = 10.dp
private val WEIGHT_EDGE = SIDE_INSET - (STOP_INSET - THUMB_RADIUS)
private val TRACK_WIDTH = 4.dp
private val VALUE_TEXT_SIZE = 16.dp
private val WEIGHT_NAME_SIZE = 20.dp
private val WEIGHT_NAME_TOP = 8.dp
private val CHECK_GAP = 8.dp
private val CHECK_SIZE = 24.dp
private val STYLE_GAP = 32.dp
private val SIGNATURE_GAP = 8.dp
private val SIGNATURE_ICON_WIDTH = 40.dp
private val SIGNATURE_ROW_GAP = 16.dp
private val SIGNATURE_EDGE = 8.dp
private val SIGNATURE_INSET = 6.dp
private val SIGNATURE_NAMES = listOf(Res.string.edit_signature_1, Res.string.edit_signature_2)
private val FORM_ICON_SIZE = 24.dp
private val FORM_SYMBOL_NAMES = mapOf(
    ViewerFormSymbol.X to Res.string.edit_form_x,
    ViewerFormSymbol.CHECK to Res.string.edit_form_check,
    ViewerFormSymbol.DASH to Res.string.edit_form_dash,
    ViewerFormSymbol.DOT to Res.string.edit_form_dot
)
private val STYLE_SAMPLE_WIDTH = 40.dp
private val STYLE_SAMPLE_LETTER = 32.dp
private val STYLE_WIDTH = SIDE_INSET * 2 + (STYLE_SAMPLE_WIDTH + CHECK_GAP + CHECK_SIZE) * 2 + STYLE_GAP
private val UNDERLINE_STYLES = listOf(
    ViewerUnderlineStyle.STRAIGHT to Res.string.edit_underline_straight,
    ViewerUnderlineStyle.WAVY to Res.string.edit_underline_wavy
)
