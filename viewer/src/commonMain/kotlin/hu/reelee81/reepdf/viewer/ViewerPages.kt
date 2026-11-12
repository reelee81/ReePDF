package hu.reelee81.reepdf.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.reelee81.reepdf.viewer.ViewerSelectionHandles.drawHandles
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.fast_scroller_thumb
import hu.reelee81.reepdf.viewer.resources.page_description
import hu.reelee81.reepdf.viewer.resources.page_description_text
import hu.reelee81.reepdf.viewer.resources.page_indicator_range
import hu.reelee81.reepdf.viewer.resources.page_indicator_single
import hu.reelee81.reepdf.viewer.resources.page_render_failed
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val spacing = with(density) { PAGE_SPACING.toPx() }.toDouble()
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
                edgeStep = AUTO_SCROLL_STEP.toPx()
            )
        }
    }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    val motion = remember { ViewerMotion() }
    val scrollerAlpha = remember { Animatable(0f) }
    val tap by rememberUpdatedState(onTap)
    var frame by remember { mutableStateOf(PagesFrame(0f, 0)) }
    val keyboardCover = state.keyboardCover

    LaunchedEffect(state.scrollCount) {
        if (state.scrollCount == 0) return@LaunchedEffect
        scrollerAlpha.snapTo(1f)
        delay(ViewerFastScroll.HIDE_DELAY_MS.milliseconds)
        scrollerAlpha.animateTo(0f, tween(ViewerFastScroll.HIDE_ANIMATION_MS.toInt()))
    }

    LaunchedEffect(frame, keyboardCover) {
        state.setUncoveredHeight((frame.rootHeight - keyboardCover - frame.top).roundToInt())
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.onSize(it.width, it.height, spacing) }
            .onGloballyPositioned { coordinates ->
                val next = PagesFrame(coordinates.positionInRoot().y, coordinates.findRootCoordinates().size.height)
                if (next != frame) frame = next
            }
            .pointerInput(state) {
                detectTapGestures(
                    onDoubleTap = { position -> motion.doubleTap(state, scope, position) },
                    onLongPress = { position -> selectWord(state, position, touch.wordTolerance) },
                    onTap = { tap() }
                )
            }
            .pointerInput(state, fastScroll, touch) {
                viewerGestures(
                    state = state,
                    thumbTop = { position -> thumbHit(state, fastScroll, scrollerAlpha, position, thumbWidth, thumbHeight) },
                    fastScroll = fastScroll,
                    touch = touch,
                    motion = motion,
                    onFling = { velocity -> motion.fling(state, scope, decay, velocity) }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) { drawPages(state, colors, touch.handleRadius) }
        FailedPages(state, colors, fontFamily)
        PageDescriptions(state)
        FastScroller(state, fastScroll, colors, fontFamily) { scrollerAlpha.value }
        if (state.selection.isActive && state.selection.dragged == null && !motion.busy) {
            ViewerSelectionBubble(state, reeColors, fontFamily, onCopy, Modifier.matchParentSize())
        }
    }
}

private data class PagesFrame(val top: Float, val rootHeight: Int)

private class ViewerTouch(
    val handleRadius: Float,
    val handleSlop: Float,
    val wordTolerance: Float,
    val edge: Float,
    val edgeStep: Float
)

private class ViewerMotion {
    private var job: Job? = null
    private var animating by mutableStateOf(false)
    var touching by mutableStateOf(false)

    val busy: Boolean
        get() = touching || animating

    fun stop() {
        job?.cancel()
        job = null
        animating = false
    }

    fun fling(state: ViewerState, scope: CoroutineScope, decay: DecayAnimationSpec<Offset>, velocity: Velocity) {
        start(scope) {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(Offset(-velocity.x, -velocity.y), decay) {
                val delta = value - last
                last = value
                state.scrollBy(delta.x.toDouble(), delta.y.toDouble())
            }
        }
    }

    fun doubleTap(state: ViewerState, scope: CoroutineScope, tap: Offset) {
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

    private fun start(scope: CoroutineScope, block: suspend () -> Unit) {
        stop()
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    animating = false
                }
            }
        }
        job = launched
        animating = true
        launched.start()
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

private suspend fun PointerInputScope.viewerGestures(
    state: ViewerState,
    thumbTop: (Offset) -> Double?,
    fastScroll: ViewerFastScroll,
    touch: ViewerTouch,
    motion: ViewerMotion,
    onFling: (Velocity) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        motion.stop()
        try {
            val thumbStart = thumbTop(down.position)
            if (thumbStart != null) {
                down.consume()
                motion.touching = true
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val pages = state.layout ?: continue
                    val view = state.viewport ?: continue
                    val top = thumbStart + (change.position.y - down.position.y)
                    state.scrollToY(fastScroll.scrollFor(top, view.height, pages.totalHeight * view.zoom))
                }
                return@awaitEachGesture
            }
            val grab = ViewerSelectionHandles.grab(state, down.position, touch.handleRadius, touch.handleSlop)
            if (grab != null && state.selection.beginDrag(grab.handle)) {
                down.consume()
                motion.touching = true
                try {
                    dragHandle(state, down, grab, touch)
                } finally {
                    state.selection.endDrag()
                }
                return@awaitEachGesture
            }
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var zoomTotal = 1f
            var panTotal = Offset.Zero
            var moving = false
            var zoomed = false
            var cancelled: Boolean
            do {
                val event = awaitPointerEvent()
                cancelled = event.changes.any { it.isConsumed }
                if (!cancelled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (!moving) {
                        zoomTotal *= zoomChange
                        panTotal += panChange
                        val zoomMotion = abs(1 - zoomTotal) * event.calculateCentroidSize(useCurrent = false)
                        if (zoomMotion > viewConfiguration.touchSlop || panTotal.getDistance() > viewConfiguration.touchSlop) {
                            moving = true
                            motion.touching = true
                        }
                    }
                    if (moving) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (zoomChange != 1f) {
                            zoomed = true
                            state.zoomBy(zoomChange, centroid.x.toDouble(), centroid.y.toDouble())
                        }
                        if (panChange != Offset.Zero) state.scrollBy(-panChange.x.toDouble(), -panChange.y.toDouble())
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                }
            } while (!cancelled && event.changes.any { it.pressed })
            if (zoomed) {
                state.endZoom()
            } else if (moving && !cancelled) {
                val limit = viewConfiguration.maximumFlingVelocity
                onFling(tracker.calculateVelocity(Velocity(limit, limit)))
            }
        } finally {
            motion.touching = false
        }
    }
}

private suspend fun AwaitPointerEventScope.dragHandle(
    state: ViewerState,
    down: PointerInputChange,
    grab: ViewerHandleGrab,
    touch: ViewerTouch
) {
    val offset = grab.anchor - down.position
    var finger = down.position
    while (true) {
        val event = withTimeoutOrNull(AUTO_SCROLL_FRAME_MS) { awaitPointerEvent() }
        if (event != null) {
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            finger = change.position
        }
        val step = Offset(
            edgeStep(finger.x, size.width, touch.edge, touch.edgeStep),
            edgeStep(finger.y, size.height, touch.edge, touch.edgeStep)
        )
        if (step != Offset.Zero) {
            state.scrollBy(step.x.toDouble(), step.y.toDouble())
        } else if (event == null) {
            continue
        }
        val anchor = finger + offset
        val (page, point) = state.pagePoint(anchor.x, anchor.y - grab.lineHeight / 2) ?: continue
        state.selection.dragTo(page, point)
    }
}

private fun edgeStep(position: Float, length: Int, edge: Float, maxStep: Float): Float = when {
    position < edge -> -maxStep * min(1f, (edge - position) / edge)
    position > length - edge -> maxStep * min(1f, (position - (length - edge)) / edge)
    else -> 0f
}

private fun DrawScope.drawPages(state: ViewerState, colors: ViewerColors, handleRadius: Float) {
    val pages = state.layout ?: return
    val view = state.viewport ?: return
    val zoom = view.zoom
    val top = view.contentTop
    val search = state.search
    val selection = state.selection
    val current = search.current
    for (page in pages.visiblePages(top, top + view.contentHeight)) {
        val x = pages.left(page, view.contentLeft, view.contentWidth) * zoom - view.scrollX
        val y = pages.top(page) * zoom - view.scrollY
        val width = pages.width(page) * zoom
        val height = pages.height(page) * zoom
        drawRect(colors.page, Offset(x.toFloat(), y.toFloat()), Size(width.toFloat(), height.toFloat()))
        state.pageImage(page)?.let { image ->
            drawPiece(image, x, y, x + width, y + height)
        }
        for ((tile, image) in state.tilesOf(page)) {
            val scale = zoom / tile.zoom.toDouble()
            drawPiece(
                image,
                x + tile.left * scale,
                y + tile.top * scale,
                x + (tile.left + tile.width) * scale,
                y + (tile.top + tile.height) * scale
            )
        }
        search.hitsOf(page)?.let { hits ->
            val chosen = if (current?.page == page) current.index else -1
            for (hit in 0 until hits.count) {
                val color = if (hit == chosen) colors.searchCurrentHit else colors.searchHit
                drawTextRects(hits.rects, hits.starts[hit], hits.starts[hit + 1], x, y, zoom, color)
            }
        }
        selection.rectsOf(page)?.let { rects ->
            drawTextRects(rects, 0, ViewerTextRects.count(rects), x, y, zoom, colors.selection)
        }
    }
    drawHandles(state, handleRadius, colors.selectionHandle)
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
    for (index in from until until) {
        val offset = index * ViewerTextHits.RECT_SIZE
        val left = (x + rects[offset] * zoom).toFloat()
        val top = (y + rects[offset + 1] * zoom).toFloat()
        val right = (x + rects[offset + 2] * zoom).toFloat()
        val bottom = (y + rects[offset + 3] * zoom).toFloat()
        drawRect(color, Offset(left, top), Size(right - left, bottom - top), blendMode = BlendMode.Multiply)
    }
}

private fun DrawScope.drawPiece(image: ViewerImage, left: Double, top: Double, right: Double, bottom: Double) {
    val x0 = left.roundToInt()
    val y0 = top.roundToInt()
    drawImage(
        image = image.bitmap,
        dstOffset = IntOffset(x0, y0),
        dstSize = IntSize(right.roundToInt() - x0, bottom.roundToInt() - y0)
    )
}

@Composable
private fun FailedPages(state: ViewerState, colors: ViewerColors, fontFamily: FontFamily) {
    val failed = state.failedPages
    if (failed.isEmpty()) return
    val pages = state.layout ?: return
    val view = state.viewport ?: return
    val density = LocalDensity.current
    val text = stringResource(Res.string.page_render_failed)
    val top = view.contentTop
    for (page in pages.visiblePages(top, top + view.contentHeight)) {
        if (page !in failed) continue
        val x = (pages.left(page, view.contentLeft, view.contentWidth) * view.zoom - view.scrollX).roundToInt()
        val y = (pages.top(page) * view.zoom - view.scrollY).roundToInt()
        val width = with(density) { (pages.width(page) * view.zoom).toFloat().toDp() }
        val height = with(density) { (pages.height(page) * view.zoom).toFloat().toDp() }
        Box(
            Modifier.offset { IntOffset(x, y) }.size(width, height).padding(FAILED_PADDING),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    color = colors.pageText,
                    fontSize = with(density) { FAILED_TEXT_SIZE.toSp() },
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
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
        BasicText(text, style = TextStyle(color = colors.scrollerText, fontSize = INDICATOR_TEXT_SIZE, fontFamily = fontFamily))
    }
}

private val PAGE_SPACING = 8.dp
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
private val INDICATOR_TEXT_SIZE = 12.sp
private val FAILED_PADDING = 16.dp
private val FAILED_TEXT_SIZE = 14.dp
private val WORD_TOLERANCE = 8.dp
private val AUTO_SCROLL_EDGE = 40.dp
private val AUTO_SCROLL_STEP = 10.dp
private const val AUTO_SCROLL_FRAME_MS = 16L
