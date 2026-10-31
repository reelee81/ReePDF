package hu.reelee81.reepdf.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface PdfOperationKeepAlive {
    fun begin(request: PdfOperationRequest)
    fun end()
}

class PdfOperationRunner(
    private val scope: CoroutineScope,
    private val keepAlive: PdfOperationKeepAlive
) {
    private val nextId = AtomicLong(0L)
    private val mutableState = MutableStateFlow<PdfOperationState>(PdfOperationState.Idle)
    val state: StateFlow<PdfOperationState> = mutableState.asStateFlow()

    @Volatile private var workJob: Job? = null

    private val holdLock = Any()
    private var held = false
    private var startedScreens = 0
    private var handedOver = false

    val isHolding: Boolean
        get() = synchronized(holdLock) { held }

    fun start(
        kind: PdfOperationKind,
        message: String,
        onCancelled: suspend () -> Unit = {},
        work: suspend (report: (percent: Int?) -> Unit) -> PdfOperationResult?
    ): Long? {
        val request = PdfOperationRequest(nextId.incrementAndGet(), kind, message)
        while (true) {
            val current = mutableState.value
            val running = current.start(request) ?: return null
            if (mutableState.compareAndSet(current, running)) break
        }
        synchronized(holdLock) {
            held = true
            handedOver = false
        }
        try {
            keepAlive.begin(request)
        } catch (e: Exception) {
            synchronized(holdLock) { held = false }
            runCatching { keepAlive.end() }
            mutableState.update { it.fail(request.id, e) }
            return request.id
        }

        scope.launch {
            var ending: (PdfOperationState) -> PdfOperationState = { it.cancelled(request.id) }
            try {
                var result: PdfOperationResult? = null
                var failure: Exception? = null
                val job = launch {
                    try {
                        result = work { percent -> progress(request.id, percent) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                    }
                }
                workJob = job
                if (isCancelling(request.id)) job.cancel()
                job.join()
                workJob = null

                val error = failure
                val value = result
                when {
                    job.isCancelled || isCancelling(request.id) -> try {
                        onCancelled()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ending = { it.cancelFailed(request.id, e) }
                    }
                    error != null -> ending = { it.fail(request.id, error) }
                    else -> ending = { it.finish(request.id, value) }
                }
            } finally {
                val hold = synchronized(holdLock) {
                    (startedScreens > 0).also { if (it) mutableState.update(ending) }
                }
                if (!hold) {
                    try {
                        release()
                    } finally {
                        mutableState.update(ending)
                    }
                }
            }
        }
        return request.id
    }

    fun screenStarted() {
        synchronized(holdLock) { startedScreens++ }
    }

    fun screenStopped() {
        val unclaimed = synchronized(holdLock) {
            startedScreens = (startedScreens - 1).coerceAtLeast(0)
            startedScreens == 0 && !handedOver && isEnded(null)
        }
        if (unclaimed) release()
    }

    fun handOver(id: Long) {
        synchronized(holdLock) {
            if (isEnded(id)) handedOver = true
        }
    }

    fun abandon(id: Long) {
        val ended = synchronized(holdLock) {
            isEnded(id).also { if (it) handedOver = false }
        }
        if (ended) release()
    }

    fun progress(id: Long, percent: Int?) {
        mutableState.update { it.progress(id, percent) }
    }

    fun cancel(id: Long) {
        mutableState.update { it.cancel(id) }
        if (isCancelling(id)) workJob?.cancel()
    }

    fun acknowledge(id: Long) {
        val acknowledged = synchronized(holdLock) {
            isEnded(id).also {
                if (it) {
                    handedOver = false
                    mutableState.update { state -> state.acknowledge(id) }
                }
            }
        }
        if (acknowledged) release()
    }

    private fun isEnded(id: Long?): Boolean {
        val current = mutableState.value
        val request = current.request ?: return false
        return !current.isBusy && (id == null || request.id == id)
    }

    private fun release() {
        val wasHeld = synchronized(holdLock) { held.also { held = false } }
        if (wasHeld) keepAlive.end()
    }

    private fun isCancelling(id: Long): Boolean {
        val current = mutableState.value
        return current is PdfOperationState.Cancelling && current.request.id == id
    }
}
