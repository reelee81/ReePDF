package hu.reelee81.reepdf.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PdfThumbnailScheduler<T, R : T>(
    private val open: suspend (path: String) -> PdfViewerDocument<T>,
    private val newTarget: (width: Int, height: Int) -> R,
    private val release: (R) -> Unit,
    private val finished: (key: String, result: Result<R>) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private class Request(
        val key: String,
        val path: String,
        val pageIndex: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val generation: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val waiting = LinkedHashMap<String, Request>()
    private val failed = HashSet<String>()
    private val documents = HashMap<String, PdfViewerDocument<T>>()
    private var focus: List<String> = emptyList()
    private var running: Job? = null
    private var runningKey: String? = null
    private var generation = 0
    private var shutDown = false

    fun request(key: String, path: String, pageIndex: Int, maxWidth: Int, maxHeight: Int): Boolean {
        synchronized(lock) {
            if (shutDown || key in waiting || key == runningKey) return false
            failed -= key
            waiting[key] = Request(key, path, pageIndex, maxWidth, maxHeight, generation)
            startNext()
            return true
        }
    }

    fun isPending(key: String): Boolean = synchronized(lock) { key in waiting || key == runningKey }

    fun hasFailed(key: String): Boolean = synchronized(lock) { key in failed }

    fun focus(keys: List<String>) {
        synchronized(lock) {
            focus = keys
            if (runningKey != null && runningKey !in keys) stopRunning()
        }
    }

    fun retainOnly(keys: Set<String>) {
        synchronized(lock) {
            waiting.keys.retainAll(keys)
            if (runningKey != null && runningKey !in keys) stopRunning()
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            waiting.clear()
            stopRunning()
        }
    }

    fun closeDocuments() {
        val closing = synchronized(lock) {
            generation++
            waiting.clear()
            stopRunning()
            failed.clear()
            documents.values.toList().also { documents.clear() }
        }
        closing.forEach { it.close() }
    }

    fun shutdown() {
        synchronized(lock) { shutDown = true }
        closeDocuments()
        scope.cancel()
    }

    private fun stopRunning() {
        val job = running
        runningKey = null
        job?.cancel()
    }

    private fun startNext() {
        if (shutDown || running != null) return
        val next = focus.firstNotNullOfOrNull { waiting[it] } ?: waiting.values.firstOrNull() ?: return
        waiting.remove(next.key)
        runningKey = next.key
        val job = scope.launch(start = CoroutineStart.LAZY) { draw(next) }
        running = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (running === job) {
                    running = null
                    runningKey = null
                }
                startNext()
            }
        }
        job.start()
    }

    private suspend fun draw(request: Request) {
        var created: R? = null
        var delivered = false
        try {
            val outcome = outcome(request) { width, height ->
                newTarget(width, height).also { created = it }
            } ?: return
            val target = created
            val error = outcome.exceptionOrNull()
            if (error != null) {
                if (markFailed(request.key, request.generation)) finished(request.key, Result.failure(error))
            } else if (target != null && isCurrent(request.generation)) {
                delivered = true
                finished(request.key, Result.success(target))
            }
        } finally {
            if (!delivered) created?.let(release)
        }
    }

    private suspend fun outcome(request: Request, newTarget: (width: Int, height: Int) -> T): Result<Unit>? =
        try {
            val document = documentFor(request.path, request.generation)
            if (document == null) {
                null
            } else {
                val fitted = document.renderFitted(request.pageIndex, request.maxWidth, request.maxHeight, newTarget)
                when (val result = fitted.result) {
                    PdfRenderResult.Done -> Result.success(Unit)
                    is PdfRenderResult.Failed -> Result.failure(result.error)
                    PdfRenderResult.Aborted -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (currentCoroutineContext().isActive) Result.failure(e) else null
        }

    private suspend fun documentFor(path: String, generation: Int): PdfViewerDocument<T>? {
        synchronized(lock) {
            if (generation != this.generation) return null
            documents[path]?.let { return it }
        }
        val opened = open(path)
        val kept = synchronized(lock) {
            (generation == this.generation).also { current -> if (current) documents[path] = opened }
        }
        if (kept) return opened
        opened.close()
        return null
    }

    private fun isCurrent(generation: Int): Boolean = synchronized(lock) { generation == this.generation }

    private fun markFailed(key: String, generation: Int): Boolean =
        synchronized(lock) {
            (generation == this.generation).also { current -> if (current) failed += key }
        }
}
