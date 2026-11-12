package hu.reelee81.reepdf.pdf

class PdfRenderAbort {
    private val lock = Any()
    private var aborted = false
    private var onAbort: (() -> Unit)? = null

    val isAborted: Boolean
        get() = synchronized(lock) { aborted }

    fun abort() {
        synchronized(lock) {
            if (aborted) return
            aborted = true
            onAbort?.invoke()
        }
    }

    fun <R> whileBound(onAbort: () -> Unit, block: () -> R): R {
        synchronized(lock) {
            this.onAbort = onAbort
            if (aborted) onAbort()
        }
        try {
            return block()
        } finally {
            synchronized(lock) { this.onAbort = null }
        }
    }
}
