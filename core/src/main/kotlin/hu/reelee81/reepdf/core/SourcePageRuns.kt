package hu.reelee81.reepdf.core

import kotlin.math.min

object SourcePageRuns {
    fun removedPages(groups: Iterable<Int>, per: Int, total: Int): Set<Int> = buildSet {
        for (group in groups) {
            val start = group * per
            val end = min(total - 1, start + per - 1)
            for (i in start..end) add(i)
        }
    }
}
