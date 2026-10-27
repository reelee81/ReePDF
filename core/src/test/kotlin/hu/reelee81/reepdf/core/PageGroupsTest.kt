package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGroupsTest {
    @Test
    fun groupsOfAFourUpLayoutCoverTheirSourcePagesAndTheLastGroupIsShort() {
        assertEquals(setOf(5, 6, 7, 8), PageGroups.sourcePagesOfGroups(listOf(1), 4, 10))
        assertEquals(setOf(1, 2, 3, 4, 9, 10), PageGroups.sourcePagesOfGroups(listOf(0, 2), 4, 10))
    }

    @Test
    fun aOneUpLayoutMapsEachGroupToOnePage() {
        assertEquals(setOf(1, 4), PageGroups.sourcePagesOfGroups(listOf(0, 3), 1, 5))
        assertEquals(setOf(0, 3), PageGroups.groupsOfSourcePages(setOf(1, 4), 1))
    }

    @Test
    fun groupsOutsideTheDocumentAndEmptySelectionsGiveNoPages() {
        assertTrue(PageGroups.sourcePagesOfGroups(listOf(5), 4, 10).isEmpty())
        assertTrue(PageGroups.sourcePagesOfGroups(emptyList(), 4, 10).isEmpty())
        assertTrue(PageGroups.groupsOfSourcePages(emptySet(), 4).isEmpty())
    }

    @Test
    fun switchingFromTwoByTwoToThreeByThreeKeepsTheSelectedPagesSelected() {
        val pages = PageGroups.sourcePagesOfGroups(listOf(1), 4, 20)
        assertEquals(setOf(0), PageGroups.groupsOfSourcePages(pages, 9))
    }

    @Test
    fun switchingFromThreeByThreeToTwoByTwoSelectsEveryGroupThePagesLandIn() {
        val pages = PageGroups.sourcePagesOfGroups(listOf(1), 9, 20)
        assertEquals(setOf(10, 11, 12, 13, 14, 15, 16, 17, 18), pages)
        assertEquals(setOf(2, 3, 4), PageGroups.groupsOfSourcePages(pages, 4))
    }
}
