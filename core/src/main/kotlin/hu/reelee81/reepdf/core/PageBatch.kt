package hu.reelee81.reepdf.core

import kotlin.math.min

object PageBatch {
    const val PAGES = 100

    fun end(first: Int, last: Int, batchSize: Int, budget: Long, cost: (Int) -> Long): Int {
        require(first <= last) { "Empty batch $first..$last" }
        val limit = min(last.toLong(), first.toLong() + batchSize.coerceAtLeast(1) - 1).toInt()
        var sum = 0L
        var page = first
        while (true) {
            sum += cost(page).coerceAtLeast(0)
            if (page == limit || sum >= budget) return page
            page++
        }
    }
}
