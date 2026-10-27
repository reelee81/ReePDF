package hu.reelee81.reepdf.core

object ProgressNotifyPacing {
    const val REVEAL_MS = 5_000L
    const val NOTIFY_MS = 1_000L

    fun waitMs(shownAt: Long, notified: Int, notifiedAt: Long, percent: Int, now: Long): Long? {
        if (percent == notified) return null
        val untilReveal = shownAt + REVEAL_MS - now
        val untilNotify = if (percent >= 0 && notified >= 0) notifiedAt + NOTIFY_MS - now else 0L
        return maxOf(untilReveal, untilNotify, 0L)
    }
}
