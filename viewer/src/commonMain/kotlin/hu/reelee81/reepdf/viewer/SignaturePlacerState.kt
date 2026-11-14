package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SignaturePlacerSpec(
    val pageWidthMm: Float,
    val pageHeightMm: Float,
    val signatureWidthMm: Float,
    val signatureHeightMm: Float,
    val maxXMm: Float,
    val maxYMm: Float,
    val startXMm: Float,
    val startYMm: Float
)

class SignaturePlacerState(private val spec: SignaturePlacerSpec, private val scope: CoroutineScope) {
    private val maxX = spec.maxXMm.coerceAtLeast(0f)
    private val maxY = spec.maxYMm.coerceAtLeast(0f)
    private val startX = spec.startXMm.coerceIn(0f, maxX)
    private val startY = spec.startYMm.coerceIn(0f, maxY)
    private var source: ViewerPageSource? = null
    private var openJob: Job? = null
    private var grabOffset: Offset? = null

    val surface: ViewerSurface = ViewerSurface(
        scope,
        zoomLimits = ViewerZoomLimits.fitWidth(ViewerViewport.DOUBLE_TAP_FIT_FACTOR),
        onLayout = { revealSignature() },
        onResized = { revealSignature() }
    )

    var xMm by mutableFloatStateOf(startX)
        private set
    var yMm by mutableFloatStateOf(startY)
        private set
    var openError by mutableStateOf<String?>(null)
        private set
    var asking by mutableStateOf(false)
        private set
    var signature by mutableStateOf<ImageBitmap?>(null)

    val isModified: Boolean
        get() = xMm != startX || yMm != startY

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

    fun signatureBox(): Rect? {
        val pages = surface.layout ?: return null
        val perMmX = pages.width(0) / spec.pageWidthMm
        val perMmY = pages.height(0) / spec.pageHeightMm
        return Rect(
            (xMm * perMmX).toFloat(),
            (yMm * perMmY).toFloat(),
            ((xMm + spec.signatureWidthMm) * perMmX).toFloat(),
            ((yMm + spec.signatureHeightMm) * perMmY).toFloat()
        )
    }

    fun signatureOnScreen(): Rect? = signatureBox()?.let { surface.screenRect(0, it) }

    fun grab(x: Float, y: Float, slop: Float): Boolean {
        val shown = signatureOnScreen() ?: return false
        if (x < shown.left - slop || x > shown.right + slop || y < shown.top - slop || y > shown.bottom + slop) return false
        val finger = fingerMm(x, y) ?: return false
        grabOffset = Offset(finger.x - xMm, finger.y - yMm)
        return true
    }

    fun dragTo(x: Float, y: Float) {
        val offset = grabOffset ?: return
        val finger = fingerMm(x, y) ?: return
        xMm = (finger.x - offset.x).coerceIn(0f, maxX)
        yMm = (finger.y - offset.y).coerceIn(0f, maxY)
    }

    fun endDrag() {
        grabOffset = null
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
        grabOffset = null
        doc?.close()
    }

    private fun fingerMm(x: Float, y: Float): Offset? {
        val pages = surface.layout ?: return null
        val (_, point) = surface.pagePoint(x, y) ?: return null
        return Offset(
            (point.x * spec.pageWidthMm / pages.width(0)).toFloat(),
            (point.y * spec.pageHeightMm / pages.height(0)).toFloat()
        )
    }

    private fun revealSignature() {
        val box = signatureBox() ?: return
        surface.reveal(0, box, Int.MAX_VALUE)
    }
}
