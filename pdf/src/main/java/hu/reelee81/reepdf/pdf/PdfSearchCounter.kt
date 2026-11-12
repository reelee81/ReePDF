package hu.reelee81.reepdf.pdf

import kotlinx.coroutines.CancellationException

class PdfSearchCounter<T>(
    private val open: suspend () -> PdfViewerDocument<T>,
    private val reopenPages: Int = REOPEN_PAGES
) {
    private val lock = Any()
    private var document: PdfViewerDocument<T>? = null
    private var generation = 0
    private var counted = 0

    suspend fun count(pageIndex: Int, needle: String): Int {
        val doc = current()
        val hits = doc.count(pageIndex, needle)
        synchronized(lock) {
            if (document === doc && ++counted >= reopenPages) drop()
        }
        return hits
    }

    fun release() {
        synchronized(lock) { drop() }
    }

    private suspend fun current(): PdfViewerDocument<T> {
        val started = synchronized(lock) {
            document?.let { return it }
            generation
        }
        val opened = open()
        synchronized(lock) {
            if (generation == started && document == null) {
                document = opened
                counted = 0
                return opened
            }
        }
        opened.close()
        throw CancellationException(RELEASED_MESSAGE)
    }

    private fun drop() {
        generation++
        counted = 0
        document?.close()
        document = null
    }

    companion object {
        const val REOPEN_PAGES = 1000
        private const val RELEASED_MESSAGE = "The search counter was released while its document was opening"
    }
}
