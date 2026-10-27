package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePageRunsTest {
    @Test
    fun theRemovedPagesOfGroupsStopAtTheLastPage() {
        assertPages(setOf(2, 3), SourcePageRuns.removedPages(listOf(1), per = 2, total = 5))
        assertPages(setOf(0, 1, 4), SourcePageRuns.removedPages(listOf(0, 2), per = 2, total = 5))
        assertPages(emptySet(), SourcePageRuns.removedPages(listOf(3), per = 2, total = 5))
    }

    private fun assertPages(expected: Set<Int>, actual: Set<Int>) {
        assertEquals(expected, actual)
    }
}
