package hu.reelee81.reepdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ViewerSignatureStore {
    suspend fun read(slot: Int): ViewerSignature?

    suspend fun write(slot: Int, signature: ViewerSignature)

    suspend fun delete(slot: Int)
}

class ViewerSignatures(private val scope: CoroutineScope) {
    private var store: ViewerSignatureStore? = null
    private var loading: Job? = null
    private val writes = Mutex()

    var onFailure: (String) -> Unit = {}

    var saved by mutableStateOf<List<ViewerSignature?>>(List(ViewerSignature.SLOTS) { null })
        private set
    var pad by mutableStateOf<ViewerSignaturePad?>(null)
        private set
    var deleting by mutableStateOf<Int?>(null)
        private set

    fun attach(chosen: ViewerSignatureStore?) {
        store = chosen
        load()
    }

    fun load() {
        val source = store ?: return
        loading?.cancel()
        loading = scope.launch {
            val read = writes.withLock {
                List(ViewerSignature.SLOTS) { slot ->
                    try {
                        source.read(slot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (store === source) saved = read
        }
    }

    fun chosen(preferred: Int): Int? {
        val current = saved
        if (current.getOrNull(preferred) != null) return preferred
        return current.indices.firstOrNull { current[it] != null }
    }

    fun openPad(slot: Int, widthIndex: Int) {
        if (pad != null || saved.getOrNull(slot) != null) return
        pad = ViewerSignaturePad(slot, widthIndex)
    }

    fun editPad(slot: Int) {
        val signature = saved.getOrNull(slot) ?: return
        if (pad != null) return
        pad = ViewerSignaturePad(slot, ViewerSignature.WIDTHS.indexOfFirst { it == signature.width }, signature.strokes)
    }

    fun cancelPad() {
        pad = null
    }

    fun close() {
        pad = null
        deleting = null
    }

    fun confirmPad(): Int? {
        val open = pad ?: return null
        val signature = open.signature() ?: return null
        pad = null
        saved = saved.toMutableList().also { it[open.slot] = signature }
        persist { it.write(open.slot, signature) }
        return open.slot
    }

    fun askDelete(slot: Int) {
        if (saved.getOrNull(slot) != null) deleting = slot
    }

    fun dismissDelete() {
        deleting = null
    }

    fun confirmDelete() {
        val slot = deleting ?: return
        deleting = null
        saved = saved.toMutableList().also { it[slot] = null }
        persist { it.delete(slot) }
    }

    private fun persist(action: suspend (ViewerSignatureStore) -> Unit) {
        val target = store ?: return
        scope.launch {
            writes.withLock {
                try {
                    action(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e.message ?: e::class.simpleName.orEmpty())
                }
            }
        }
    }
}

class ViewerSignaturePad internal constructor(val slot: Int, widthIndex: Int, drawn: List<FloatArray> = emptyList()) {
    private var builder: ViewerStrokeBuilder? = null

    var widthIndex by mutableIntStateOf(widthIndex.coerceIn(ViewerSignature.WIDTHS.indices))
        private set
    var strokes by mutableStateOf(drawn)
        private set
    var current by mutableStateOf<FloatArray?>(null)
        private set

    val width: Float
        get() = ViewerSignature.WIDTHS[widthIndex]

    val isEmpty: Boolean
        get() = strokes.isEmpty()

    val isDrawing: Boolean
        get() = builder != null

    fun chooseWidth(index: Int) {
        if (index in ViewerSignature.WIDTHS.indices) widthIndex = index
    }

    fun begin(x: Float, y: Float, step: Float) {
        finish()
        if (strokes.size >= ViewerSignature.MAX_STROKES || points() >= ViewerSignature.MAX_POINTS) return
        val started = ViewerStrokeBuilder(step)
        started.add(clampX(x), clampY(y))
        builder = started
        current = started.points()
    }

    fun extend(x: Float, y: Float) {
        val started = builder ?: return
        if (points() + started.size >= ViewerSignature.MAX_POINTS) return
        if (started.add(clampX(x), clampY(y))) current = started.points()
    }

    fun end(x: Float, y: Float) {
        val started = builder ?: return
        if (points() + started.size < ViewerSignature.MAX_POINTS) started.finish(clampX(x), clampY(y))
        finish()
    }

    fun finish() {
        val started = builder ?: return
        builder = null
        current = null
        strokes = strokes + started.points()
    }

    fun clear() {
        builder = null
        current = null
        strokes = emptyList()
    }

    fun signature(): ViewerSignature? {
        finish()
        if (strokes.isEmpty()) return null
        return ViewerSignature(width, strokes)
    }

    private fun points(): Int = strokes.sumOf { it.size / 2 }

    private fun clampX(x: Float): Float = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)

    private fun clampY(y: Float): Float = if (y.isNaN()) 0f else y.coerceIn(0f, ViewerSignature.HEIGHT)
}
