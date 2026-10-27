package hu.reelee81.reepdf.core

import kotlin.math.min

object PageGroups {
    fun sourcePagesOfGroups(groups: Iterable<Int>, per: Int, totalPages: Int): Set<Int> = buildSet {
        groups.forEach { g ->
            val start = g * per + 1
            val end = min(totalPages, start + per - 1)
            for (p in start..end) add(p)
        }
    }

    fun groupsOfSourcePages(pages: Iterable<Int>, per: Int): Set<Int> =
        pages.map { p -> (p - 1) / per }.toSet()
}
