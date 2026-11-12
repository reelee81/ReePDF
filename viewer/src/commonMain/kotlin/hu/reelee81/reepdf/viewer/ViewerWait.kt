package hu.reelee81.reepdf.viewer

class ViewerWait(private val clock: () -> Long) {
    enum class Reason { OPENING, OPENING_EDITED }

    var reason: Reason? = null
        private set

    private var startedAt = 0L

    val isActive: Boolean
        get() = reason != null

    val leavesOnCancel: Boolean
        get() = reason == Reason.OPENING_EDITED

    fun start(reason: Reason) {
        if (this.reason == null) startedAt = clock()
        this.reason = reason
    }

    fun stop() {
        reason = null
    }

    fun revealDelayMs(): Long? =
        if (reason == null) null else (REVEAL_MS - (clock() - startedAt)).coerceAtLeast(0L)

    fun isRevealed(): Boolean = revealDelayMs() == 0L

    companion object {
        const val REVEAL_MS = 300L
    }
}
