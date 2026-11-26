package hu.reelee81.reepdf.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.ViewerSelectionHandles.drawHandles
import hu.reelee81.reepdf.viewer.ViewerTypedFrame.drawCaret
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.decimal_separator
import hu.reelee81.reepdf.viewer.resources.fast_scroller_thumb
import hu.reelee81.reepdf.viewer.resources.page_description
import hu.reelee81.reepdf.viewer.resources.page_description_text
import hu.reelee81.reepdf.viewer.resources.page_indicator_range
import hu.reelee81.reepdf.viewer.resources.page_indicator_single
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerPages(
    state: ViewerState,
    colors: ViewerColors,
    reeColors: ReeColors,
    fontFamily: FontFamily,
    onTap: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fastScroll = remember(density) {
        with(density) { ViewerFastScroll(SCROLLER_MARGIN.roundToPx(), SCROLLER_MARGIN.roundToPx(), THUMB_HEIGHT.roundToPx()) }
    }
    val thumbWidth = with(density) { THUMB_WIDTH.toPx() }
    val thumbHeight = with(density) { THUMB_HEIGHT.roundToPx() }
    val touch = remember(density) {
        with(density) {
            ViewerTouch(
                handleRadius = ViewerSelectionHandles.RADIUS.toPx(),
                handleSlop = ViewerSelectionHandles.TOUCH_SLOP.toPx(),
                wordTolerance = WORD_TOLERANCE.toPx(),
                edge = AUTO_SCROLL_EDGE.toPx(),
                edgeStep = AUTO_SCROLL_STEP.toPx(),
                strokeStep = STROKE_STEP.toPx(),
                eraserTolerance = ERASER_TOLERANCE.toPx(),
                signatureSlop = SIGNATURE_SLOP.toPx(),
                signatureReach = SIGNATURE_HANDLE_SLOP.toPx(),
                signatureMargin = SIGNATURE_FRAME_MARGIN.toPx(),
                readableText = READABLE_TEXT.toPx()
            )
        }
    }
    val scope = rememberCoroutineScope()
    val motion = remember { ViewerMotion() }
    val scrollerAlpha = remember { Animatable(0f) }
    val tap by rememberUpdatedState(onTap)
    var frame by remember { mutableStateOf(PagesFrame(0f, 0)) }
    val keyboardCover = state.keyboardCover
    val resizes = state.resizeCount
    val shownCover = remember { mutableIntStateOf(0) }
    val editing = state.editor.isActive
    val scrolling = state.editor.paused
    val measurer = rememberTextMeasurer(0)
    val glyphs = remember(measurer, fontFamily, editing) { ViewerTypedGlyphs(measurer, fontFamily) }
    val separator = stringResource(Res.string.decimal_separator)
    val sizeLabel = remember(measurer, fontFamily, separator) { ViewerSizeLabel(measurer, fontFamily, separator) }
    val caretColor = reeColors.appControlActivatedOtherColor.default
    val typing = state.editor.typing
    var caretShown by remember { mutableStateOf(true) }
    var focusRequests by remember { mutableIntStateOf(0) }
    val hooks = EditHooks(
        askFocus = { focusRequests += 1 },
        opened = { position -> motion.zoomToText(state, scope, position, touch.readableText) },
        placed = { position -> motion.zoomToMark(state, scope, position, touch.readableText) }
    )

    LaunchedEffect(state.scrollCount) {
        if (state.scrollCount == 0) return@LaunchedEffect
        scrollerAlpha.snapTo(1f)
        delay(ViewerFastScroll.HIDE_DELAY_MS.milliseconds)
        scrollerAlpha.animateTo(0f, tween(ViewerFastScroll.HIDE_ANIMATION_MS.toInt()))
    }

    LaunchedEffect(frame, keyboardCover, resizes) {
        state.setUncoveredHeight((frame.rootHeight - keyboardCover - frame.top).roundToInt())
        if (keyboardCover > 0 && shownCover.intValue <= 0) state.revealSearchHit() else state.keepSearchHitUncovered()
        shownCover.intValue = keyboardCover
    }

    LaunchedEffect(typing?.mark, typing?.cursor, keyboardCover, frame, resizes) {
        revealCaret(state)
    }

    LaunchedEffect(typing != null, typing?.mark, typing?.cursor) {
        caretShown = true
        if (typing == null) return@LaunchedEffect
        while (true) {
            delay(CARET_BLINK_MS.milliseconds)
            caretShown = !caretShown
        }
    }

    ViewerSurfaceView(
        surface = state.surface,
        colors = colors,
        fontFamily = fontFamily,
        motion = motion,
        modifier = modifier,
        inputs = Modifier
            .onGloballyPositioned { coordinates ->
                val next = PagesFrame(coordinates.positionInRoot().y, coordinates.findRootCoordinates().size.height)
                if (next != frame) frame = next
            }
            .pointerInput(state, editing, scrolling) {
                if (editing) {
                    if (scrolling) detectTapGestures(onDoubleTap = { position -> motion.doubleTap(state, scope, position) })
                    return@pointerInput
                }
                detectTapGestures(
                    onDoubleTap = { position -> motion.doubleTap(state, scope, position) },
                    onLongPress = { position -> selectWord(state, position, touch.wordTolerance) },
                    onTap = { tap() }
                )
            },
        grab = { position -> grabAt(state, fastScroll, scrollerAlpha, position, thumbWidth, thumbHeight, touch, hooks, scope) },
        drawPage = { page, x, y, zoom ->
            drawTexts(state, colors, page, x, y, zoom)
            if (state.editor.isActive) drawEditMarks(state, colors, glyphs, sizeLabel, page, x, y, zoom)
        },
        drawOver = { drawHandles(state, touch.handleRadius, colors.selectionHandle) }
    ) {
        if (editing && typing != null) TypingCaret(state, caretColor) { caretShown }
        PageDescriptions(state)
        FastScroller(state, fastScroll, colors, fontFamily) { scrollerAlpha.value }
        if (state.selection.isActive && state.selection.dragged == null && !motion.busy) {
            ViewerSelectionBubble(state, reeColors, fontFamily, onCopy, Modifier.matchParentSize())
        }
        if (editing && typing != null) ViewerTypingField(state.editor, focusRequests, fontFamily)
    }
}

@Composable
private fun BoxScope.TypingCaret(state: ViewerState, color: Color, shown: () -> Boolean) {
    Canvas(Modifier.matchParentSize().graphicsLayer()) {
        val editor = state.editor
        val typing = editor.typing ?: return@Canvas
        val dragged = editor.draggedText
        if (!editor.isActive || (dragged != null && dragged !== typing.mark) || !shown()) return@Canvas
        val text = typing.mark.text ?: return@Canvas
        val fonts = editor.textFonts ?: return@Canvas
        val zoom = state.viewport?.zoom ?: return@Canvas
        val origin = state.screenRect(typing.mark.page, Rect.Zero) ?: return@Canvas
        drawCaret(text, fonts.font(text.weight), typing.cursor, origin.left, origin.top, zoom, color, CARET_WIDTH.toPx())
    }
}

private fun revealCaret(state: ViewerState) {
    val open = state.editor.typing ?: return
    val fonts = state.editor.textFonts ?: return
    val text = open.mark.text ?: return
    state.reveal(open.mark.page, ViewerTypedFrame.caretBox(text, fonts.font(text.weight), open.cursor))
}

private data class PagesFrame(val top: Float, val rootHeight: Int)

private class EditHooks(val askFocus: () -> Unit, val opened: (Offset) -> Unit, val placed: (Offset) -> Unit)

private class ViewerTouch(
    val handleRadius: Float,
    val handleSlop: Float,
    val wordTolerance: Float,
    val edge: Float,
    val edgeStep: Float,
    val strokeStep: Float,
    val eraserTolerance: Float,
    val signatureSlop: Float,
    val signatureReach: Float,
    val signatureMargin: Float,
    val readableText: Float
)

private fun ViewerMotion.doubleTap(state: ViewerState, scope: CoroutineScope, tap: Offset) {
    val start = state.viewport ?: return
    val pages = state.layout ?: return
    if (state.isWaiting) return
    val target = start.doubleTapZoom(pages) ?: return
    start(scope) {
        try {
            animate(start.zoom, target, animationSpec = tween(ViewerViewport.DOUBLE_TAP_ANIMATION_MS.toInt())) { value, _ ->
                state.zoomFrom(start, value, tap.x.toDouble(), tap.y.toDouble())
            }
        } finally {
            state.endZoom()
        }
    }
}

private fun ViewerMotion.zoomToText(state: ViewerState, scope: CoroutineScope, tap: Offset, readable: Float) {
    val text = state.editor.typing?.mark?.text ?: return
    zoomToReadable(state, scope, tap, text.size, readable) { revealCaret(state) }
}

private fun ViewerMotion.zoomToMark(state: ViewerState, scope: CoroutineScope, tap: Offset, readable: Float) {
    val mark = state.editor.framed?.mark?.takeIf { it.kind.formMark } ?: return
    zoomToReadable(state, scope, tap, ViewerFormMarks.size(mark), readable) {}
}

private fun ViewerMotion.zoomToReadable(state: ViewerState, scope: CoroutineScope, tap: Offset, size: Float, readable: Float, after: () -> Unit) {
    val start = state.viewport ?: return
    val pages = state.layout ?: return
    val target = start.readableZoom(size, readable, pages) ?: return
    start(scope) {
        try {
            animate(start.zoom, target, animationSpec = tween(ViewerViewport.DOUBLE_TAP_ANIMATION_MS.toInt())) { value, _ ->
                state.zoomFrom(start, value, tap.x.toDouble(), tap.y.toDouble())
            }
        } finally {
            state.endZoom()
        }
        after()
    }
}

private fun selectWord(state: ViewerState, position: Offset, tolerance: Float) {
    val view = state.viewport ?: return
    val (page, point) = state.pagePoint(position.x, position.y) ?: return
    state.selection.selectWord(page, point, tolerance / view.zoom)
}

private fun thumbHit(
    state: ViewerState,
    fastScroll: ViewerFastScroll,
    alpha: Animatable<Float, AnimationVector1D>,
    position: Offset,
    thumbWidth: Float,
    thumbHeight: Int
): Double? {
    if (alpha.value <= 0f) return null
    val pages = state.layout ?: return null
    val view = state.viewport ?: return null
    val contentHeight = pages.totalHeight * view.zoom
    if (!fastScroll.canScroll(view.height, contentHeight)) return null
    val top = fastScroll.thumbTop(view.scrollY, view.height, contentHeight)
    val inside = position.x >= view.width - thumbWidth && position.y >= top && position.y <= top + thumbHeight
    return if (inside) top.toDouble() else null
}

private fun grabAt(
    state: ViewerState,
    fastScroll: ViewerFastScroll,
    alpha: Animatable<Float, AnimationVector1D>,
    position: Offset,
    thumbWidth: Float,
    thumbHeight: Int,
    touch: ViewerTouch,
    hooks: EditHooks,
    clock: CoroutineScope
): ViewerDrag? {
    val thumbStart = thumbHit(state, fastScroll, alpha, position, thumbWidth, thumbHeight)
    if (thumbStart != null) {
        return { down ->
            dragThumb(state, fastScroll, down, thumbStart)
            ViewerDragEnd.DONE
        }
    }
    if (state.editor.isActive) return editGrab(state, position, touch, hooks, clock)
    val grab = ViewerSelectionHandles.grab(state, position, touch.handleRadius, touch.handleSlop)
    if (grab != null && state.selection.beginDrag(grab.handle)) {
        return { down ->
            try {
                dragHandle(state, down, grab, touch, clock)
            } finally {
                state.selection.endDrag()
            }
            ViewerDragEnd.DONE
        }
    }
    return null
}

private suspend fun AwaitPointerEventScope.dragThumb(
    state: ViewerState,
    fastScroll: ViewerFastScroll,
    down: PointerInputChange,
    thumbStart: Double
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
        val pages = state.layout ?: continue
        val view = state.viewport ?: continue
        val top = thumbStart + (change.position.y - down.position.y)
        state.scrollToY(fastScroll.scrollFor(top, view.height, pages.totalHeight * view.zoom))
    }
}

private suspend fun AwaitPointerEventScope.dragHandle(
    state: ViewerState,
    down: PointerInputChange,
    grab: ViewerHandleGrab,
    touch: ViewerTouch,
    clock: CoroutineScope
) {
    val offset = grab.anchor - down.position
    edgeScrollDrag(
        down = down,
        clock = clock,
        secondFingerEnds = false,
        step = { finger, scale ->
            Offset(
                edgeStep(finger.x, size.width, touch.edge, touch.edgeStep * scale),
                edgeStep(finger.y, size.height, touch.edge, touch.edgeStep * scale)
            )
        },
        scroll = { step -> state.scrollBy(step.x.toDouble(), step.y.toDouble()) },
        moveTo = { finger ->
            val anchor = finger + offset
            state.pagePoint(anchor.x, anchor.y - grab.lineHeight / 2)?.let { (page, point) -> state.selection.dragTo(page, point) }
        }
    )
}

private fun editGrab(state: ViewerState, position: Offset, touch: ViewerTouch, hooks: EditHooks, clock: CoroutineScope): ViewerDrag? {
    val editor = state.editor
    if (state.isWaiting || editor.paused) return null
    val tool = editor.settings.tool
    if (tool == ViewerEditTool.SIGNATURE || tool == ViewerEditTool.FORM_MARK) return frameGrab(state, position, touch, hooks, clock)
    if (tool == ViewerEditTool.TEXT) return textGrab(state, position, touch, hooks, clock)
    val erasing = tool == ViewerEditTool.ERASER
    if (!erasing && !tool.draws) return null
    val (page, point) = state.pageHit(position.x, position.y) ?: return null
    if (page in state.failedPages) return null
    val zoom = state.viewport?.zoom ?: return null
    return { down ->
        if (erasing) {
            editor.beginErase(page, point, touch.eraserTolerance / zoom)
        } else {
            editor.beginStroke(page, point, touch.strokeStep / zoom, touch.wordTolerance / zoom)
        }
        var end = ViewerDragEnd.DONE
        var crossed: Offset? = null
        var crossedAt = 0L
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) {
                    end = ViewerDragEnd.GESTURE
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                if (erasing) {
                    state.pagePoint(change.position.x, change.position.y)?.let { (hitPage, hitPoint) -> editor.extendErase(hitPage, hitPoint) }
                    continue
                }
                state.pointOn(page, change.position.x, change.position.y)?.let(editor::extendStroke)
                if (!tool.onlyOnText) continue
                if (crossed == null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    crossed = change.position
                    crossedAt = change.uptimeMillis
                }
                val waitedTooLong = crossed != null && editor.isDeciding && change.uptimeMillis - crossedAt >= WORD_WAIT_MS
                if (waitedTooLong) editor.abandonStroke()
                if (editor.isStroking) continue
                val from = crossed
                end = if (from != null) {
                    val step = change.position - from
                    state.scrollBy(-step.x.toDouble(), -step.y.toDouble())
                    ViewerDragEnd.PAN
                } else {
                    pressEnd(awaitPress(state.surface, down))
                }
                break
            }
        } catch (e: CancellationException) {
            editor.cancelGesture()
            throw e
        }
        when {
            end != ViewerDragEnd.DONE -> editor.cancelGesture()
            erasing -> editor.endErase()
            else -> editor.endStroke()
        }
        end
    }
}

private fun pressEnd(press: ViewerPress): ViewerDragEnd = when (press) {
    ViewerPress.TAP -> ViewerDragEnd.DONE
    ViewerPress.PAN -> ViewerDragEnd.PAN
    ViewerPress.PINCH -> ViewerDragEnd.GESTURE
}

private enum class FrameDrag { PINCH, MOVED, STILL }

private suspend fun AwaitPointerEventScope.dragFrame(
    state: ViewerState,
    page: Int,
    down: PointerInputChange,
    touch: ViewerTouch,
    clock: CoroutineScope,
    dragTo: (ViewerTextPoint) -> Unit
): FrameDrag {
    val slop = viewConfiguration.touchSlop
    var moved = false
    val pinch = edgeScrollDrag(
        down = down,
        clock = clock,
        secondFingerEnds = true,
        step = { finger, scale -> if (moved) frameEdgeStep(state, page, finger, size.width, size.height, touch, scale) else Offset.Zero },
        scroll = { step -> state.scrollBy(step.x.toDouble(), step.y.toDouble()) },
        moveTo = { finger ->
            if ((finger - down.position).getDistance() > slop) moved = true
            state.pointOn(page, finger.x, finger.y)?.let(dragTo)
        }
    )
    return when {
        pinch -> FrameDrag.PINCH
        moved -> FrameDrag.MOVED
        else -> FrameDrag.STILL
    }
}

private fun frameEdgeStep(state: ViewerState, page: Int, finger: Offset, width: Int, height: Int, touch: ViewerTouch, scale: Float): Offset {
    val pages = state.layout ?: return Offset.Zero
    val shown = state.screenRect(page, Rect(0f, 0f, pages.width(page).toFloat(), pages.height(page).toFloat())) ?: return Offset.Zero
    val bottom = min(height, state.uncoveredHeight)
    val maxStep = touch.edgeStep * scale
    return Offset(
        pageEdgeStep(finger.x, width, shown.left, shown.right, touch.edge, maxStep),
        pageEdgeStep(finger.y, bottom, shown.top, shown.bottom, touch.edge, maxStep)
    )
}

private fun frameGrab(state: ViewerState, position: Offset, touch: ViewerTouch, hooks: EditHooks, clock: CoroutineScope): ViewerDrag? {
    val editor = state.editor
    val (page, point) = state.pageHit(position.x, position.y) ?: return null
    if (page in state.failedPages) return null
    val pages = state.layout ?: return null
    val zoom = state.viewport?.zoom ?: return null
    val pageWidth = pages.width(page).toFloat()
    val pageHeight = pages.height(page).toFloat()
    val slop = touch.signatureSlop / zoom
    val form = editor.settings.tool == ViewerEditTool.FORM_MARK
    if (!editor.grabFramed(page, point, touch.signatureReach / zoom, slop, touch.signatureMargin / zoom)) {
        return { down ->
            val press = awaitPress(state.surface, down)
            if (press == ViewerPress.TAP) {
                if (!form) {
                    editor.tapSignature(page, point, pageWidth, pageHeight, slop)
                } else if (editor.tapFormMark(page, point, pageWidth, pageHeight, slop)) {
                    hooks.placed(position)
                }
            }
            pressEnd(press)
        }
    }
    return { down ->
        val drag = try {
            dragFrame(state, page, down, touch, clock) { editor.dragFramed(it, pageWidth, pageHeight) }
        } catch (e: CancellationException) {
            editor.cancelGesture()
            throw e
        }
        when (drag) {
            FrameDrag.MOVED -> editor.endFramedDrag()
            FrameDrag.PINCH, FrameDrag.STILL -> editor.cancelGesture()
        }
        if (drag == FrameDrag.PINCH) ViewerDragEnd.GESTURE else ViewerDragEnd.DONE
    }
}

private fun DrawScope.drawEditMarks(
    state: ViewerState,
    colors: ViewerColors,
    glyphs: ViewerTypedGlyphs,
    sizeLabel: ViewerSizeLabel,
    page: Int,
    x: Double,
    y: Double,
    zoom: Float
) {
    val editor = state.editor
    val pages = state.layout ?: return
    val marks = editor.addedMarks(page)
    val wet = editor.wet?.takeIf { it.page == page }
    val moved = editor.movedFramed?.takeIf { it.page == page }
    val framed = editor.framed?.takeIf { it.mark.page == page }
    val typing = editor.typing?.takeIf { it.mark.page == page }
    val dragged = editor.draggedText?.takeIf { it.page == page }
    val open = dragged ?: typing?.mark
    if (marks.isEmpty() && wet == null && moved == null && framed == null && open == null) return
    val fonts = editor.textFonts
    val left = x.toFloat()
    val top = y.toFloat()
    clipRect(left, top, left + (pages.width(page) * zoom).toFloat(), top + (pages.height(page) * zoom).toFloat()) {
        for (mark in marks) drawMark(mark, left, top, zoom, glyphs, fonts)
        if (wet != null) drawMark(wet, left, top, zoom, glyphs, fonts)
        if (moved != null) drawMark(moved, left, top, zoom, glyphs, fonts)
        if (open != null) drawMark(open, left, top, zoom, glyphs, fonts)
    }
    val margin = SIGNATURE_FRAME_MARGIN.toPx()
    val text = open?.text
    if (text != null && fonts != null) {
        val box = ViewerTypedPlacement.shown(text, fonts.font(text.weight), pages.width(page).toFloat(), pages.height(page).toFloat())
        val frame = ViewerTypedFrame.screenFrame(box, left, top, zoom, margin)
        drawSignatureFrame(colors.cropFrame, frame)
        with(sizeLabel) { drawSize(text.size, frame, zoom, colors.cropFrame, colors.page, top) }
    }
    val box = moved?.let(ViewerFramedMark::boxOf) ?: framed?.box ?: return
    drawSignatureFrame(
        colors.cropFrame,
        Rect(left + box[0] * zoom - margin, top + box[1] * zoom - margin, left + box[2] * zoom + margin, top + box[3] * zoom + margin)
    )
}

private fun textGrab(state: ViewerState, position: Offset, touch: ViewerTouch, hooks: EditHooks, clock: CoroutineScope): ViewerDrag? {
    val editor = state.editor
    val hit = state.pageHit(position.x, position.y)
    if (hit == null) {
        if (editor.typing == null) return null
        return { down ->
            val press = awaitPress(state.surface, down)
            if (press == ViewerPress.TAP) editor.commitTyping()
            pressEnd(press)
        }
    }
    val (page, point) = hit
    if (page in state.failedPages) return null
    val pages = state.layout ?: return null
    val zoom = state.viewport?.zoom ?: return null
    val pageWidth = pages.width(page).toFloat()
    val pageHeight = pages.height(page).toFloat()
    val slop = touch.signatureSlop / zoom
    if (!editor.grabText(page, point, touch.signatureReach / zoom, slop, touch.signatureMargin / zoom)) {
        return { down ->
            val press = awaitPress(state.surface, down)
            if (press == ViewerPress.TAP) {
                val closed = editor.typing == null
                editor.tapText(page, point, pageWidth, pageHeight, slop)
                val open = editor.typing
                if (open != null) hooks.askFocus()
                if (closed && open?.isNew == true) hooks.opened(position)
            }
            pressEnd(press)
        }
    }
    return { down ->
        val drag = try {
            dragFrame(state, page, down, touch, clock, editor::dragText)
        } catch (e: CancellationException) {
            editor.cancelGesture()
            throw e
        }
        when (drag) {
            FrameDrag.PINCH -> editor.cancelGesture()
            FrameDrag.MOVED -> editor.endTextDrag()
            FrameDrag.STILL -> {
                editor.cancelGesture()
                editor.placeCaret(page, point)
                hooks.askFocus()
            }
        }
        if (drag == FrameDrag.PINCH) ViewerDragEnd.GESTURE else ViewerDragEnd.DONE
    }
}

private fun DrawScope.drawSignatureFrame(color: Color, shown: Rect) {
    drawRect(color, shown.topLeft, shown.size, style = Stroke(SIGNATURE_FRAME_WIDTH.toPx()))
    val length = PageCropperState.markLength(shown, SIGNATURE_MARK_LENGTH.toPx())
    val width = SIGNATURE_MARK_WIDTH.toPx()
    val corner = shown.bottomRight
    drawLine(color, corner, Offset(corner.x - length, corner.y), width, StrokeCap.Square)
    drawLine(color, corner, Offset(corner.x, corner.y - length), width, StrokeCap.Square)
}

private fun DrawScope.drawMark(mark: ViewerMark, left: Float, top: Float, zoom: Float, glyphs: ViewerTypedGlyphs, fonts: ViewerTextFonts?) {
    val text = mark.text
    if (text != null) {
        if (fonts != null) with(glyphs) { drawTyped(mark, fonts.font(text.weight), left, top, zoom) }
        return
    }
    val path = mark.path.toPath(left, top, zoom)
    val color = Color(OPAQUE or mark.color).copy(alpha = if (mark.kind.translucent) MARK_ALPHA else 1f)
    if (mark.kind.fills) {
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = maxOf(mark.width * zoom, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

internal fun ViewerMarkPath.toPath(left: Float, top: Float, zoom: Float): Path {
    val path = Path()
    val p = points
    var index = 0
    for (verb in verbs) {
        when (verb) {
            ViewerMarkPath.MOVE -> path.moveTo(left + p[index] * zoom, top + p[index + 1] * zoom)
            ViewerMarkPath.LINE -> path.lineTo(left + p[index] * zoom, top + p[index + 1] * zoom)
            ViewerMarkPath.CUBIC -> path.cubicTo(
                left + p[index] * zoom, top + p[index + 1] * zoom,
                left + p[index + 2] * zoom, top + p[index + 3] * zoom,
                left + p[index + 4] * zoom, top + p[index + 5] * zoom
            )
            ViewerMarkPath.CLOSE -> path.close()
        }
        index += ViewerMarkPath.pointsOf(verb)
    }
    return path
}

private fun DrawScope.drawTexts(state: ViewerState, colors: ViewerColors, page: Int, x: Double, y: Double, zoom: Float) {
    val search = state.search
    val current = search.current
    search.hitsOf(page)?.let { hits ->
        val chosen = if (current?.page == page) current.index else -1
        for (hit in 0 until hits.count) {
            val color = if (hit == chosen) colors.searchCurrentHit else colors.searchHit
            drawTextRects(hits.rects, hits.starts[hit], hits.starts[hit + 1], x, y, zoom, color)
        }
    }
    state.selection.rectsOf(page)?.let { rects ->
        drawTextRects(rects, 0, ViewerTextRects.count(rects), x, y, zoom, colors.selection)
    }
}

private fun DrawScope.drawTextRects(
    rects: FloatArray,
    from: Int,
    until: Int,
    x: Double,
    y: Double,
    zoom: Float,
    color: Color
) {
    if (from >= until) return
    val area = Path()
    for (index in from until until) {
        val offset = index * ViewerTextHits.RECT_SIZE
        val left = (x + rects[offset] * zoom).toFloat()
        val top = (y + rects[offset + 1] * zoom).toFloat()
        val right = (x + rects[offset + 2] * zoom).toFloat()
        val bottom = (y + rects[offset + 3] * zoom).toFloat()
        area.addRect(Rect(left, top, right, bottom))
    }
    drawPath(area, color, blendMode = BlendMode.Multiply)
}

@Composable
private fun PageDescriptions(state: ViewerState) {
    if (!state.readsPageTexts) return
    val pages = state.layout ?: return
    val view = state.viewport ?: return
    val density = LocalDensity.current
    val top = view.contentTop
    for (page in pages.visiblePages(top, top + view.contentHeight)) {
        key(page) {
            val text = state.pageText(page)
            val description = if (text.isNullOrBlank()) {
                stringResource(Res.string.page_description, page + 1)
            } else {
                stringResource(Res.string.page_description_text, page + 1, text)
            }
            val x = (pages.left(page, view.contentLeft, view.contentWidth) * view.zoom - view.scrollX).roundToInt()
            val y = (pages.top(page) * view.zoom - view.scrollY).roundToInt()
            val width = with(density) { (pages.width(page) * view.zoom).toFloat().toDp() }
            val height = with(density) { (pages.height(page) * view.zoom).toFloat().toDp() }
            Box(Modifier.offset { IntOffset(x, y) }.size(width, height).semantics { contentDescription = description })
        }
    }
}

@Composable
private fun BoxScope.FastScroller(
    state: ViewerState,
    fastScroll: ViewerFastScroll,
    colors: ViewerColors,
    fontFamily: FontFamily,
    alpha: () -> Float
) {
    val pages = state.layout ?: return
    val view = state.viewport ?: return
    val density = LocalDensity.current
    val contentHeight = pages.totalHeight * view.zoom
    if (!fastScroll.canScroll(view.height, contentHeight)) return
    val thumbTop = fastScroll.thumbTop(view.scrollY, view.height, contentHeight)
    val label = ViewerPageLabel.of(pages.labelPages(view.contentTop, view.contentTop + view.contentHeight), pages.pageCount)
    val text = when (label) {
        is ViewerPageLabel.Single -> stringResource(Res.string.page_indicator_single, label.page, label.total)
        is ViewerPageLabel.Range -> stringResource(Res.string.page_indicator_range, label.first, label.last, label.total)
    }
    val description = stringResource(Res.string.fast_scroller_thumb)
    Box(
        Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(0, thumbTop) }
            .size(THUMB_WIDTH, THUMB_HEIGHT)
            .graphicsLayer { this.alpha = alpha() }
            .drawBehind {
                drawCircle(colors.scroller, THUMB_RADIUS.toPx(), Offset(THUMB_RADIUS.toPx(), size.height / 2))
            }
            .semantics { contentDescription = description }
    ) {
        Image(
            rememberVectorPainter(ViewerIcons.fastScroll),
            null,
            Modifier.align(Alignment.CenterStart).padding(start = THUMB_ICON_START).size(THUMB_ICON_SIZE),
            colorFilter = ColorFilter.tint(colors.scrollerIcon)
        )
    }
    Box(
        Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(-INDICATOR_END.roundToPx(), thumbTop + ((THUMB_HEIGHT - INDICATOR_HEIGHT) / 2).roundToPx()) }
            .height(INDICATOR_HEIGHT)
            .graphicsLayer { this.alpha = alpha() }
            .background(colors.scroller, RoundedCornerShape(INDICATOR_RADIUS))
            .padding(horizontal = INDICATOR_PADDING),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(color = colors.scrollerText, fontSize = with(density) { INDICATOR_TEXT_SIZE.toSp() }, fontFamily = fontFamily))
    }
}

private val SCROLLER_MARGIN = 2.dp
private val THUMB_WIDTH = 36.dp
private val THUMB_HEIGHT = 48.dp
private val THUMB_RADIUS = 24.dp
private val THUMB_ICON_START = 10.dp
private val THUMB_ICON_SIZE = 24.dp
private val INDICATOR_END = 42.dp
private val INDICATOR_HEIGHT = 24.dp
private val INDICATOR_RADIUS = 16.dp
private val INDICATOR_PADDING = 12.dp
private val INDICATOR_TEXT_SIZE = 12.dp
private val WORD_TOLERANCE = 8.dp
private val STROKE_STEP = 1.dp
private val ERASER_TOLERANCE = 10.dp
private val SIGNATURE_SLOP = 8.dp
private val SIGNATURE_HANDLE_SLOP = 24.dp
private val SIGNATURE_FRAME_WIDTH = 1.5.dp
private val SIGNATURE_FRAME_MARGIN = 4.dp
private val SIGNATURE_MARK_WIDTH = 4.dp
private val SIGNATURE_MARK_LENGTH = 22.dp
private val CARET_WIDTH = 2.dp
private val READABLE_TEXT = 16.dp
private const val WORD_WAIT_MS = 100L
private const val CARET_BLINK_MS = 500L
