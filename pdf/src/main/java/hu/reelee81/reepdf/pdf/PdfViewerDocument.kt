package hu.reelee81.reepdf.pdf

import hu.reelee81.reepdf.core.NupGeometry
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfViewerDocument<T> private constructor(
    private val engine: PdfViewerEngine<T>,
    val pageCount: Int,
    private val documentThread: ExecutorCoroutineDispatcher,
    private val store: PdfViewerStoreRule
) {
    private val lock = Any()
    private var closed = false
    private var running: PdfRenderAbort? = null
    private val finished = CompletableDeferred<Unit>()

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    val isClosedWithoutError: Boolean
        get() = finished.isCompleted && !finished.isCancelled

    suspend fun pageSizes(): PdfViewerPageSizes {
        checkOpen()
        return onDocumentThread(null) {
            val widths = FloatArray(pageCount)
            val heights = FloatArray(pageCount)
            val unreadable = BooleanArray(pageCount)
            var width = NupGeometry.A4_WIDTH_PT
            var height = NupGeometry.A4_HEIGHT_PT
            for (index in 0 until pageCount) {
                ensureActive()
                checkOpen()
                val size = readableSize(index)
                if (size == null) {
                    unreadable[index] = true
                } else {
                    width = size.width
                    height = size.height
                }
                widths[index] = width
                heights[index] = height
            }
            emptyStoreNow()
            engine.releaseFreeMemory()
            PdfViewerPageSizes(widths, heights, unreadable)
        }
    }

    suspend fun render(
        pageIndex: Int,
        zoom: Float,
        left: Int,
        top: Int,
        target: T,
        onDrawn: ((millis: Long) -> Unit)? = null
    ): PdfRenderResult {
        require(pageIndex in 0 until pageCount) { "Page $pageIndex is outside 0 until $pageCount" }
        require(zoom > 0f && zoom.isFinite()) { "Invalid zoom $zoom" }
        checkOpen()
        return renderOnDocumentThread(onDrawn) { abort -> engine.render(pageIndex, zoom, left, top, target, abort) }
    }

    suspend fun renderFitted(
        pageIndex: Int,
        maxWidth: Int,
        maxHeight: Int,
        newTarget: (width: Int, height: Int) -> T
    ): PdfFittedRender<T> {
        require(pageIndex in 0 until pageCount) { "Page $pageIndex is outside 0 until $pageCount" }
        require(maxWidth > 0 && maxHeight > 0) { "Invalid box $maxWidth x $maxHeight" }
        checkOpen()
        var target: T? = null
        val result = renderOnDocumentThread(null) { abort ->
            val size = engine.pageSize(pageIndex)
            check(isValid(size)) { "Page $pageIndex has no valid size" }
            if (!abort.isAborted) {
                val (width, height) = fitInto(size, maxWidth, maxHeight)
                val created = newTarget(width, height)
                target = created
                engine.render(pageIndex, width / size.width, 0, 0, created, abort)
            }
        }
        return PdfFittedRender(result, target)
    }

    suspend fun search(pageIndex: Int, needle: String): PdfTextHits =
        readText(pageIndex) { engine.search(pageIndex, needle) }

    suspend fun count(pageIndex: Int, needle: String): Int =
        readText(pageIndex) { engine.count(pageIndex, needle) }

    suspend fun word(pageIndex: Int, point: PdfTextPoint, tolerance: Float): PdfTextSpan? =
        readText(pageIndex) { engine.word(pageIndex, point, tolerance) }

    suspend fun caret(pageIndex: Int, point: PdfTextPoint): PdfTextPoint? =
        readText(pageIndex) { engine.caret(pageIndex, point) }

    suspend fun highlight(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): FloatArray =
        readText(pageIndex) { engine.highlight(pageIndex, from, to) }

    suspend fun copy(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): String =
        readText(pageIndex) { engine.copy(pageIndex, from, to) }

    suspend fun text(pageIndex: Int): String = readText(pageIndex) { engine.text(pageIndex) }

    fun trimMemory() {
        synchronized(lock) {
            if (closed) return
            documentThread.executor.execute {
                if (!isClosed) {
                    emptyStoreNow()
                    engine.releaseFreeMemory()
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            running?.abort()
            documentThread.executor.execute(::closeOnDocumentThread)
        }
    }

    suspend fun awaitClosed() = finished.await()

    private fun checkOpen() = check(!isClosed) { CLOSED_MESSAGE }

    private fun begin(abort: PdfRenderAbort): Boolean {
        synchronized(lock) {
            if (closed) return false
            running = abort
            return true
        }
    }

    private fun readableSize(index: Int): PdfViewerEngine.PageSize? {
        val size = try {
            engine.pageSize(index)
        } catch (_: Exception) {
            return null
        }
        return if (isValid(size)) size else null
    }

    private fun isValid(size: PdfViewerEngine.PageSize) =
        size.width > 0f && size.height > 0f && size.width.isFinite() && size.height.isFinite()

    private fun fitInto(size: PdfViewerEngine.PageSize, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val aspect = size.height / size.width
        var width = maxWidth
        var height = (width * aspect).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height / aspect).toInt()
        }
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    private suspend fun renderOnDocumentThread(
        onDrawn: ((millis: Long) -> Unit)?,
        draw: (PdfRenderAbort) -> Unit
    ): PdfRenderResult {
        val abort = PdfRenderAbort()
        return onDocumentThread(abort) {
            if (!begin(abort)) return@onDocumentThread PdfRenderResult.Aborted
            val started = System.nanoTime()
            val result = try {
                draw(abort)
                if (abort.isAborted) PdfRenderResult.Aborted else PdfRenderResult.Done
            } catch (e: Exception) {
                if (abort.isAborted) PdfRenderResult.Aborted else PdfRenderResult.Failed(e)
            } finally {
                synchronized(lock) { running = null }
            }
            if (result == PdfRenderResult.Done) onDrawn?.invoke((System.nanoTime() - started) / NANOS_PER_MILLI)
            if (store.afterPage()) emptyStoreNow()
            result
        }
    }

    private suspend fun <R> readText(pageIndex: Int, read: () -> R): R {
        require(pageIndex in 0 until pageCount) { "Page $pageIndex is outside 0 until $pageCount" }
        checkOpen()
        return onDocumentThread(null) {
            checkOpen()
            try {
                read()
            } finally {
                if (store.afterPage()) emptyStoreNow()
            }
        }
    }

    private fun emptyStoreNow() {
        engine.emptyStore()
        engine.dropLoadedObjects()
        store.emptied()
    }

    private fun closeOnDocumentThread() {
        val failure = runCatching { engine.close() }.exceptionOrNull()
        documentThread.close()
        if (failure == null) finished.complete(Unit) else finished.completeExceptionally(failure)
    }

    private suspend fun <R> onDocumentThread(
        abort: PdfRenderAbort?,
        block: suspend CoroutineScope.() -> R
    ): R = coroutineScope {
        val work = async(documentThread, block = block)
        try {
            work.await()
        } catch (e: CancellationException) {
            abort?.abort()
            ensureActive()
            throw IllegalStateException(CLOSED_MESSAGE, e)
        }
    }

    companion object {
        private const val CLOSED_MESSAGE = "The viewer document is closed"
        private const val THREAD_NAME = "ReePDF viewer"
        private const val NANOS_PER_MILLI = 1_000_000L

        suspend fun <T> open(
            nativeBytes: (() -> Long)? = null,
            engine: () -> PdfViewerEngine<T>
        ): PdfViewerDocument<T> {
            val documentThread = Executors.newSingleThreadExecutor(::newDocumentThread).asCoroutineDispatcher()
            var opened: PdfViewerEngine<T>? = null
            try {
                return withContext(documentThread) {
                    val created = engine()
                    opened = created
                    PdfViewerDocument(created, created.pageCount, documentThread, PdfViewerStoreRule(nativeBytes))
                }
            } catch (e: Throwable) {
                opened?.let { created -> documentThread.executor.execute { runCatching { created.close() } } }
                documentThread.close()
                throw e
            }
        }

        private fun newDocumentThread(task: Runnable) = Thread(task, THREAD_NAME).apply { isDaemon = true }
    }
}

sealed interface PdfRenderResult {
    data object Done : PdfRenderResult

    data object Aborted : PdfRenderResult

    class Failed(val error: Exception) : PdfRenderResult
}

class PdfFittedRender<T>(val result: PdfRenderResult, val target: T?)

class PdfViewerPageSizes(val widths: FloatArray, val heights: FloatArray, val unreadable: BooleanArray)
