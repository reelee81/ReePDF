package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.cancel_text
import hu.reelee81.reepdf.viewer.resources.clear_text
import hu.reelee81.reepdf.viewer.resources.edit_signature_1
import hu.reelee81.reepdf.viewer.resources.edit_signature_2
import hu.reelee81.reepdf.viewer.resources.ok_text
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerSignaturePadDialog(
    editor: ViewerEditor,
    pad: ViewerSignaturePad,
    colors: ReeColors,
    viewerColors: ViewerColors,
    overlayColor: Color,
    fontFamily: FontFamily,
    mainPanelWidth: Dp
) {
    val density = LocalDensity.current
    val title = stringResource(if (pad.slot == 0) Res.string.edit_signature_1 else Res.string.edit_signature_2)
    val drawing = remember { mutableStateOf<PointerId?>(null) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(overlayColor)
            .pointerInput(drawing) { blockOthersWhileDrawing(drawing) }
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        val chrome = TOP_PADDING + TITLE_HEIGHT + WIDTH_SLIDER_HEIGHT + PAD_GAP + BUTTONS_HEIGHT + BOTTOM_PADDING
        val panelWidth = dialogWidth(maxWidth, mainPanelWidth)
        val byHeight = (maxHeight - DIALOG_EDGE * 2 - chrome) / ViewerSignature.HEIGHT
        val padWidth = minOf(panelWidth - SIDE_PADDING * 2, byHeight).coerceAtLeast(MIN_PAD_WIDTH)
        val padPixels = with(density) { padWidth.toPx() }
        Column(
            Modifier.padding(vertical = DIALOG_EDGE)
                .width(maxOf(panelWidth, padWidth + SIDE_PADDING * 2))
                .background(colors.alertdialogBackgroundColor.default, RoundedCornerShape(PANEL_RADIUS))
                .verticalScroll(rememberScrollState())
                .padding(start = SIDE_PADDING, top = TOP_PADDING, end = SIDE_PADDING, bottom = BOTTOM_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(TITLE_HEIGHT), contentAlignment = Alignment.Center) {
                BasicText(
                    text = title,
                    style = TextStyle(
                        color = colors.normalTextColor.default,
                        fontSize = with(density) { TITLE_SIZE.toSp() },
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1
                )
            }
            ViewerWidthSlider(
                values = ViewerSignature.WIDTHS,
                index = pad.widthIndex,
                thickness = { it * padPixels / ViewerSignature.PAD_WIDTH_ON_PAGE },
                colors = colors,
                fontFamily = fontFamily,
                onChoose = editor::chooseSignatureWidth,
                modifier = Modifier.fillMaxWidth()
            )
            SignaturePad(
                pad = pad,
                drawing = drawing,
                description = title,
                ink = colors.normalTextColor.default,
                viewerColors = viewerColors,
                modifier = Modifier.size(padWidth, padWidth * ViewerSignature.HEIGHT)
            )
            Spacer(Modifier.height(PAD_GAP))
            Row(Modifier.fillMaxWidth()) {
                ViewerTextButton(stringResource(Res.string.cancel_text), colors, fontFamily, editor.signatures::cancelPad, Modifier.weight(1f))
                ViewerTextButton(stringResource(Res.string.clear_text), colors, fontFamily, pad::clear, Modifier.weight(1f), enabled = !pad.isEmpty)
                ViewerTextButton(stringResource(Res.string.ok_text), colors, fontFamily, editor::confirmSignaturePad, Modifier.weight(1f), enabled = !pad.isEmpty)
            }
        }
    }
}

@Composable
private fun SignaturePad(
    pad: ViewerSignaturePad,
    drawing: MutableState<PointerId?>,
    description: String,
    ink: Color,
    viewerColors: ViewerColors,
    modifier: Modifier = Modifier
) {
    val step = with(LocalDensity.current) { STROKE_STEP.toPx() }
    Box(
        modifier.border(OUTLINE_WIDTH, viewerColors.colorOutline)
            .semantics { contentDescription = description }
            .pointerInput(pad) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    drawing.value = down.id
                    down.consume()
                    val scale = size.width.toFloat()
                    pad.begin(down.position.x / scale, down.position.y / scale, step / scale)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                pad.end(change.position.x / scale, change.position.y / scale)
                                break
                            }
                            pad.extend(change.position.x / scale, change.position.y / scale)
                        }
                    } finally {
                        drawing.value = null
                        pad.finish()
                    }
                }
            }
            .drawWithCache {
                val scale = size.width
                val drawn = ViewerSignature.pathOf(pad.strokes, scale, 0f, 0f).toPath(0f, 0f, 1f)
                onDrawBehind {
                    val style = Stroke(maxOf(pad.width * scale / ViewerSignature.PAD_WIDTH_ON_PAGE, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(drawn, ink, style = style)
                    pad.current?.let { stroke ->
                        drawPath(ViewerSignature.pathOf(listOf(stroke), scale, 0f, 0f).toPath(0f, 0f, 1f), ink, style = style)
                    }
                }
            }
    )
}

private suspend fun PointerInputScope.blockOthersWhileDrawing(drawing: State<PointerId?>) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val active = drawing.value ?: continue
            for (change in event.changes) if (change.id != active) change.consume()
        }
    }
}

private val SIDE_PADDING = 16.dp
private val TOP_PADDING = 8.dp
private val BOTTOM_PADDING = 4.dp
private val TITLE_HEIGHT = 48.dp
private val TITLE_SIZE = 20.dp
private val PAD_GAP = 8.dp
private val BUTTONS_HEIGHT = 48.dp
private val PANEL_RADIUS = 12.dp
private val MIN_PAD_WIDTH = 120.dp
private val STROKE_STEP = 1.dp
