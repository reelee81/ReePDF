package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PageCropperSpec(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val allPages: Boolean,
    val minSize: Float
)

class PageCropperState(spec: PageCropperSpec, private val scope: CoroutineScope) {
    private val minSize = spec.minSize.coerceIn(0f, 1f)
    private val startFrame = startFrame(spec)
    private var source: ViewerPageSource? = null
    private var openJob: Job? = null
    private var grip: PageCropGrip? = null
    private var grabPoint = Offset.Zero
    private var grabFrame = startFrame

    val surface: ViewerSurface = ViewerSurface(scope, zoomLimits = ViewerZoomLimits.fitWidth(ViewerViewport.DOUBLE_TAP_FIT_FACTOR))

    var frame by mutableStateOf(startFrame)
        private set
    var allPages by mutableStateOf(spec.allPages)
        private set
    var openError by mutableStateOf<String?>(null)
        private set
    var asking by mutableStateOf(false)
        private set

    val isModified: Boolean
        get() = frame != startFrame

    val isReady: Boolean
        get() = surface.layout != null

    fun open(opener: suspend () -> ViewerPageSource) {
        openJob?.cancel()
        closeSource()
        openError = null
        openJob = scope.launch {
            var opened: ViewerPageSource? = null
            try {
                val created = opener()
                opened = created
                val pageSizes = created.pageSizes()
                source = created
                surface.attach(created, pageSizes)
                surface.applyLayout()
            } catch (e: CancellationException) {
                if (source !== opened) opened?.close()
                throw e
            } catch (e: Exception) {
                if (source === opened) closeSource() else opened?.close()
                openError = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    fun toggleAllPages() {
        allPages = !allPages
    }

    fun reset() {
        grip = null
        frame = FULL_FRAME
    }

    fun pageOnScreen(): Rect? {
        val pages = surface.layout ?: return null
        return surface.screenRect(0, Rect(0f, 0f, pages.width(0).toFloat(), pages.height(0).toFloat()))
    }

    fun frameOnScreen(): Rect? {
        val pages = surface.layout ?: return null
        val width = pages.width(0).toFloat()
        val height = pages.height(0).toFloat()
        return surface.screenRect(0, Rect(frame.left * width, frame.top * height, frame.right * width, frame.bottom * height))
    }

    fun sideMarkZones(slop: Float): List<Rect> {
        val shown = frameOnScreen() ?: return emptyList()
        val view = surface.viewport ?: return emptyList()
        val bounds = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val middle = (shown.top + shown.bottom) / 2f
        return listOf(shown.left, shown.right).flatMap { x ->
            listOf(shown.top, middle, shown.bottom).map { y -> Rect(Offset(x, y), slop).intersect(bounds) }
        }.filter { it.width > 0f && it.height > 0f }
    }

    fun grab(x: Float, y: Float, maxMarkLength: Float, markSlop: Float, lineSlop: Float): Boolean {
        val shown = frameOnScreen() ?: return false
        val found = gripAt(shown, x, y, markLength(shown, maxMarkLength), markSlop, lineSlop) ?: return false
        val point = fraction(x, y) ?: return false
        grip = found
        grabPoint = point
        grabFrame = frame
        return true
    }

    fun dragTo(x: Float, y: Float) {
        val held = grip ?: return
        val point = fraction(x, y) ?: return
        frame = dragged(grabFrame, held, point.x - grabPoint.x, point.y - grabPoint.y, minSize)
    }

    fun endDrag() {
        grip = null
    }

    fun handleBack(): Boolean = when {
        asking -> {
            asking = false
            true
        }
        isModified -> {
            asking = true
            true
        }
        else -> false
    }

    fun dismissQuestion() {
        asking = false
    }

    fun onShown() {
        surface.plan()
    }

    fun onHidden() {
        surface.hide()
    }

    fun trimMemory() {
        surface.trimMemory()
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        closeSource()
    }

    private fun closeSource() {
        val doc = surface.detach()
        source = null
        grip = null
        doc?.close()
    }

    private fun fraction(x: Float, y: Float): Offset? {
        val pages = surface.layout ?: return null
        val (_, point) = surface.pagePoint(x, y) ?: return null
        return Offset((point.x / pages.width(0)).toFloat(), (point.y / pages.height(0)).toFloat())
    }

    private fun startFrame(spec: PageCropperSpec): Rect {
        val values = listOf(spec.left, spec.top, spec.right, spec.bottom)
        if (values.any { !it.isFinite() }) return FULL_FRAME
        val left = spec.left.coerceIn(0f, 1f)
        val top = spec.top.coerceIn(0f, 1f)
        val right = spec.right.coerceIn(0f, 1f)
        val bottom = spec.bottom.coerceIn(0f, 1f)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else FULL_FRAME
    }

    companion object {
        val FULL_FRAME = Rect(0f, 0f, 1f, 1f)

        fun markLength(shown: Rect, maxLength: Float): Float = min(maxLength, min(shown.width, shown.height) / 3f)
    }
}

internal enum class PageCropGrip(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(true, true, false, false),
    TOP_RIGHT(false, true, true, false),
    BOTTOM_LEFT(true, false, false, true),
    BOTTOM_RIGHT(false, false, true, true),
    LEFT(true, false, false, false),
    TOP(false, true, false, false),
    RIGHT(false, false, true, false),
    BOTTOM(false, false, false, true),
    MOVE(false, false, false, false)
}

internal fun gripAt(shown: Rect, x: Float, y: Float, length: Float, markSlop: Float, lineSlop: Float): PageCropGrip? {
    val l = shown.left
    val t = shown.top
    val r = shown.right
    val b = shown.bottom
    val half = length / 2f
    val centerX = (l + r) / 2f
    val centerY = (t + b) / 2f
    val corners = listOf(
        PageCropGrip.TOP_LEFT to min(distance(x, y, l, t, l + length, t), distance(x, y, l, t, l, t + length)),
        PageCropGrip.TOP_RIGHT to min(distance(x, y, r - length, t, r, t), distance(x, y, r, t, r, t + length)),
        PageCropGrip.BOTTOM_LEFT to min(distance(x, y, l, b, l + length, b), distance(x, y, l, b - length, l, b)),
        PageCropGrip.BOTTOM_RIGHT to min(distance(x, y, r - length, b, r, b), distance(x, y, r, b - length, r, b))
    )
    nearest(corners, markSlop)?.let { return it }
    val middles = listOf(
        PageCropGrip.TOP to distance(x, y, centerX - half, t, centerX + half, t),
        PageCropGrip.BOTTOM to distance(x, y, centerX - half, b, centerX + half, b),
        PageCropGrip.LEFT to distance(x, y, l, centerY - half, l, centerY + half),
        PageCropGrip.RIGHT to distance(x, y, r, centerY - half, r, centerY + half)
    )
    nearest(middles, markSlop)?.let { return it }
    val edges = listOf(
        distance(x, y, l, t, r, t),
        distance(x, y, l, b, r, b),
        distance(x, y, l, t, l, b),
        distance(x, y, r, t, r, b)
    )
    if (edges.any { it <= lineSlop }) return PageCropGrip.MOVE
    val grid = (1..2).flatMap { step ->
        val gridX = l + (r - l) * step / 3f
        val gridY = t + (b - t) * step / 3f
        listOf(distance(x, y, gridX, t, gridX, b), distance(x, y, l, gridY, r, gridY))
    }
    return if (grid.any { it <= lineSlop }) PageCropGrip.MOVE else null
}

internal fun dragged(start: Rect, grip: PageCropGrip, dx: Float, dy: Float, minSize: Float): Rect {
    if (grip == PageCropGrip.MOVE) {
        val moveX = dx.coerceIn(-start.left, 1f - start.right)
        val moveY = dy.coerceIn(-start.top, 1f - start.bottom)
        return Rect(start.left + moveX, start.top + moveY, start.right + moveX, start.bottom + moveY)
    }
    val left = if (grip.left) (start.left + dx).coerceIn(0f, max(0f, start.right - minSize)) else start.left
    val right = if (grip.right) (start.right + dx).coerceIn(min(1f, start.left + minSize), 1f) else start.right
    val top = if (grip.top) (start.top + dy).coerceIn(0f, max(0f, start.bottom - minSize)) else start.top
    val bottom = if (grip.bottom) (start.bottom + dy).coerceIn(min(1f, start.top + minSize), 1f) else start.bottom
    return Rect(left, top, right, bottom)
}

private fun nearest(candidates: List<Pair<PageCropGrip, Float>>, slop: Float): PageCropGrip? =
    candidates.filter { it.second <= slop }.minByOrNull { it.second }?.first

private fun distance(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
    val dx = max(max(min(x0, x1) - x, 0f), x - max(x0, x1))
    val dy = max(max(min(y0, y1) - y, 0f), y - max(y0, y1))
    return sqrt(dx * dx + dy * dy)
}
