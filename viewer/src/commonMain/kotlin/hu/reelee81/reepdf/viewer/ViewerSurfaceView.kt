package hu.reelee81.reepdf.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.page_render_failed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal enum class ViewerDragEnd { DONE, GESTURE, PAN }

internal enum class ViewerPress { TAP, PAN, PINCH }

internal typealias ViewerDrag = suspend AwaitPointerEventScope.(PointerInputChange) -> ViewerDragEnd

internal typealias ViewerPageDrawing = DrawScope.(page: Int, x: Double, y: Double, zoom: Float) -> Unit

@Composable
internal fun ViewerSurfaceView(
    surface: ViewerSurface,
    colors: ViewerColors,
    fontFamily: FontFamily,
    motion: ViewerMotion,
    modifier: Modifier = Modifier,
    gutter: Dp = 0.dp,
    inputs: Modifier = Modifier,
    grab: (Offset) -> ViewerDrag? = { null },
    drawPage: ViewerPageDrawing = { _, _, _, _ -> },
    drawOver: DrawScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val spacing = with(density) { PAGE_SPACING.toPx() }.toDouble()
    val gutterPx = with(density) { gutter.toPx() }.toDouble()
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    val currentGrab by rememberUpdatedState(grab)
    val fling = remember { ViewerFlingTrack() }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { surface.onSize(it.width, it.height, spacing, gutterPx) }
            .then(inputs)
            .pointerInput(fling) { trackFling(fling) }
            .pointerInput(surface) {
                surfaceGestures(
                    surface = surface,
                    grab = { position -> currentGrab(position) },
                    motion = motion,
                    fling = fling,
                    onFling = { velocity -> motion.fling(surface, scope, decay, velocity) }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) { drawSurface(surface, colors, drawPage, drawOver) }
        FailedPages(surface, colors, fontFamily)
        content()
    }
}

internal class ViewerMotion {
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

    fun fling(surface: ViewerSurface, scope: CoroutineScope, decay: DecayAnimationSpec<Offset>, velocity: Velocity) {
        start(scope) {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(Offset(-velocity.x, -velocity.y), decay) {
                val delta = value - last
                last = value
                surface.scrollBy(delta.x.toDouble(), delta.y.toDouble())
            }
        }
    }

    fun start(scope: CoroutineScope, block: suspend () -> Unit) {
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

internal class ViewerFlingTrack {
    private val tracker = VelocityTracker()

    fun start(timeMillis: Long, position: Offset) {
        tracker.resetTracking()
        tracker.addPosition(timeMillis, position)
    }

    fun add(timeMillis: Long, position: Offset) = tracker.addPosition(timeMillis, position)

    fun velocity(limit: Float): Velocity = tracker.calculateVelocity(Velocity(limit, limit))
}

private suspend fun PointerInputScope.trackFling(fling: ViewerFlingTrack) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fling.start(down.uptimeMillis, down.position)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.id == down.id }?.let { fling.add(it.uptimeMillis, it.position) }
        } while (event.changes.any { it.pressed })
    }
}

private suspend fun PointerInputScope.surfaceGestures(
    surface: ViewerSurface,
    grab: (Offset) -> ViewerDrag?,
    motion: ViewerMotion,
    fling: ViewerFlingTrack,
    onFling: (Velocity) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        motion.stop()
        try {
            var moving = false
            val drag = grab(down.position)
            if (drag != null) {
                down.consume()
                motion.touching = true
                when (drag(down)) {
                    ViewerDragEnd.DONE -> return@awaitEachGesture
                    ViewerDragEnd.GESTURE -> Unit
                    ViewerDragEnd.PAN -> moving = true
                }
            }
            var zoomTotal = 1f
            var panTotal = Offset.Zero
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
                            surface.zoomBy(zoomChange, centroid.x.toDouble(), centroid.y.toDouble())
                        }
                        if (panChange != Offset.Zero) surface.scrollBy(-panChange.x.toDouble(), -panChange.y.toDouble())
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (!cancelled && event.changes.any { it.pressed })
            if (zoomed) {
                surface.endZoom()
            } else if (moving && !cancelled) {
                val limit = viewConfiguration.maximumFlingVelocity
                onFling(fling.velocity(limit))
            }
        } finally {
            motion.touching = false
        }
    }
}

private fun DrawScope.drawSurface(
    surface: ViewerSurface,
    colors: ViewerColors,
    drawPage: ViewerPageDrawing,
    drawOver: DrawScope.() -> Unit
) {
    val pages = surface.layout ?: return
    val view = surface.viewport ?: return
    val zoom = view.zoom
    val top = view.contentTop
    for (page in pages.visiblePages(top, top + view.contentHeight)) {
        val x = pages.left(page, view.contentLeft, view.contentWidth) * zoom - view.scrollX
        val y = pages.top(page) * zoom - view.scrollY
        val width = pages.width(page) * zoom
        val height = pages.height(page) * zoom
        drawRect(colors.page, Offset(x.toFloat(), y.toFloat()), Size(width.toFloat(), height.toFloat()))
        surface.pageImage(page)?.let { image ->
            drawPiece(image, x, y, x + width, y + height)
        }
        for ((tile, image) in surface.tilesOf(page)) {
            val scale = zoom / tile.zoom.toDouble()
            drawPiece(
                image,
                x + tile.left * scale,
                y + tile.top * scale,
                x + (tile.left + tile.width) * scale,
                y + (tile.top + tile.height) * scale
            )
        }
        drawPage(page, x, y, zoom)
    }
    drawOver()
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
private fun FailedPages(surface: ViewerSurface, colors: ViewerColors, fontFamily: FontFamily) {
    val failed = surface.failedPages
    if (failed.isEmpty()) return
    val pages = surface.layout ?: return
    val view = surface.viewport ?: return
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

internal fun autoScrollDrag(
    surface: ViewerSurface,
    clock: CoroutineScope,
    edge: Float,
    maxStep: Float,
    moveTo: (x: Float, y: Float) -> Unit,
    end: () -> Unit
): ViewerDrag = { down ->
    val zoomNext = try {
        edgeScrollDrag(
            down = down,
            clock = clock,
            secondFingerEnds = true,
            step = { finger, scale ->
                Offset(edgeStep(finger.x, size.width, edge, maxStep * scale), edgeStep(finger.y, size.height, edge, maxStep * scale))
            },
            scroll = { step -> surface.scrollBy(step.x.toDouble(), step.y.toDouble()) },
            moveTo = { finger -> moveTo(finger.x, finger.y) }
        )
    } finally {
        end()
    }
    if (zoomNext) ViewerDragEnd.GESTURE else ViewerDragEnd.DONE
}

internal suspend fun AwaitPointerEventScope.edgeScrollDrag(
    down: PointerInputChange,
    clock: CoroutineScope,
    secondFingerEnds: Boolean,
    step: (finger: Offset, scale: Float) -> Offset,
    scroll: (Offset) -> Unit,
    moveTo: (Offset) -> Unit
): Boolean {
    val finger = EdgeFinger(down.position)
    val ticker = clock.launch {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0L) {
                    val delta = step(finger.position, autoScrollScale(now - last))
                    if (delta != Offset.Zero) {
                        scroll(delta)
                        moveTo(finger.position)
                    }
                }
                last = now
            }
        }
    }
    try {
        while (true) {
            val event = awaitPointerEvent()
            if (secondFingerEnds && event.changes.count { it.pressed } > 1) return true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            finger.position = change.position
            moveTo(finger.position)
        }
    } finally {
        ticker.cancel()
    }
    return false
}

private class EdgeFinger(var position: Offset)

internal fun autoScrollScale(elapsedNanos: Long): Float =
    elapsedNanos.coerceIn(0L, AUTO_SCROLL_LONGEST_FRAMES * AUTO_SCROLL_FRAME_NANOS) / AUTO_SCROLL_FRAME_NANOS.toFloat()

internal suspend fun AwaitPointerEventScope.awaitPress(surface: ViewerSurface, down: PointerInputChange): ViewerPress {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) return ViewerPress.PINCH
        val change = event.changes.firstOrNull { it.id == down.id } ?: return ViewerPress.TAP
        change.consume()
        if (!change.pressed) return ViewerPress.TAP
        if (startsPan(surface, down, change)) return ViewerPress.PAN
    }
}

internal fun AwaitPointerEventScope.startsPan(surface: ViewerSurface, down: PointerInputChange, change: PointerInputChange): Boolean {
    if ((change.position - down.position).getDistance() <= viewConfiguration.touchSlop) return false
    val step = change.position - change.previousPosition
    surface.scrollBy(-step.x.toDouble(), -step.y.toDouble())
    return true
}

internal fun edgeStep(position: Float, length: Int, edge: Float, maxStep: Float): Float = when {
    position < edge -> -maxStep * min(1f, (edge - position) / edge)
    position > length - edge -> maxStep * min(1f, (position - (length - edge)) / edge)
    else -> 0f
}

internal fun pageEdgeStep(position: Float, length: Int, pageStart: Float, pageEnd: Float, edge: Float, maxStep: Float): Float {
    val step = edgeStep(position, length, edge, maxStep)
    return when {
        step < 0f -> max(step, min(0f, pageStart))
        step > 0f -> min(step, max(0f, pageEnd - length))
        else -> 0f
    }
}

internal val AUTO_SCROLL_EDGE = 40.dp
internal val AUTO_SCROLL_STEP = 10.dp
private const val AUTO_SCROLL_FRAME_NANOS = 16_000_000L
private const val AUTO_SCROLL_LONGEST_FRAMES = 3L
private val PAGE_SPACING = 8.dp
private val FAILED_PADDING = 16.dp
private val FAILED_TEXT_SIZE = 14.dp
