package hu.reelee81.reepdf.pdf

internal class PdfViewerStoreRule(private val nativeBytes: (() -> Long)?) {
    private var baseline = nativeBytes?.invoke() ?: 0L
    private var pages = 0

    fun afterPage(): Boolean {
        val meter = nativeBytes ?: return ++pages >= PAGE_LIMIT
        return meter() - baseline > GROWTH_LIMIT_BYTES
    }

    fun emptied() {
        pages = 0
        baseline = nativeBytes?.invoke() ?: 0L
    }

    private companion object {
        const val GROWTH_LIMIT_BYTES = 64L * 1024 * 1024
        const val PAGE_LIMIT = 200
    }
}
