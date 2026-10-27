package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.SourcePartPlan.Part.Keep
import hu.reelee81.reepdf.core.SourcePartPlan.Part.Write
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.EmptyPage
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.Incoming
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.Pages
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.Replacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePartPlanTest {
    @Test
    fun theFirstImportFillsNewParts() {
        val plan = SourcePartPlan.append(SourcePartLayout(emptyList()), listOf(Incoming(0, 0, 6)), partPages = 3)

        assertParts(
            listOf(
                Write(null, listOf(Incoming(0, 0, 2))),
                Write(null, listOf(Incoming(0, 3, 5))),
                Write(null, listOf(Incoming(0, 6, 6)))
            ),
            plan
        )
        assertEquals(7, plan.writtenPages)
    }

    @Test
    fun anImportFillsUpTheLastPartFirst() {
        val layout = SourcePartLayout(listOf(3, 2))
        val plan = SourcePartPlan.append(layout, listOf(Incoming(0, 0, 1), Incoming(1, 0, 2)), partPages = 3)

        assertParts(
            listOf(
                Keep(0),
                Write(1, listOf(Pages(3, 4), Incoming(0, 0, 0))),
                Write(null, listOf(Incoming(0, 1, 1), Incoming(1, 0, 1))),
                Write(null, listOf(Incoming(1, 2, 2)))
            ),
            plan
        )
        assertEquals(listOf(3, 3, 3, 1), plan.pageCounts(layout))
        assertEquals(7, plan.writtenPages)
    }

    @Test
    fun anImportAfterAFullLastPartOnlyAddsNewParts() {
        val layout = SourcePartLayout(listOf(2, 3))
        val plan = SourcePartPlan.append(layout, listOf(Incoming(0, 0, 0)), partPages = 3)

        assertParts(listOf(Keep(0), Keep(1), Write(null, listOf(Incoming(0, 0, 0)))), plan)
    }

    @Test
    fun anImportLeavesAPartialMiddlePartAlone() {
        val layout = SourcePartLayout(listOf(3, 1, 3))
        val plan = SourcePartPlan.append(layout, listOf(Incoming(0, 0, 1)), partPages = 3)

        assertParts(listOf(Keep(0), Keep(1), Keep(2), Write(null, listOf(Incoming(0, 0, 1)))), plan)
    }

    @Test
    fun anEmptyPageGoesIntoTheLastPart() {
        assertParts(
            listOf(Keep(0), Write(1, listOf(Pages(3, 3), EmptyPage))),
            SourcePartPlan.append(SourcePartLayout(listOf(3, 1)), listOf(EmptyPage), partPages = 3)
        )
        assertParts(
            listOf(Keep(0), Write(null, listOf(EmptyPage))),
            SourcePartPlan.append(SourcePartLayout(listOf(3)), listOf(EmptyPage), partPages = 3)
        )
    }

    @Test
    fun nothingToAppendKeepsEveryPart() {
        assertParts(listOf(Keep(0), Keep(1)), SourcePartPlan.append(SourcePartLayout(listOf(3, 1)), emptyList(), partPages = 3))
    }

    @Test
    fun aDeletionRewritesOnlyThePartsItTouches() {
        val layout = SourcePartLayout(listOf(3, 3, 2))
        val plan = SourcePartPlan.removePages(layout, setOf(4, 6, 7))

        assertParts(listOf(Keep(0), Write(1, listOf(Pages(3, 3), Pages(5, 5)))), plan)
        assertEquals(listOf(3, 2), plan.pageCounts(layout))
    }

    @Test
    fun aDeletionIgnoresPagesOutsideTheSource() {
        assertParts(listOf(Keep(0)), SourcePartPlan.removePages(SourcePartLayout(listOf(2)), setOf(5)))
    }

    @Test
    fun aReorderRewritesOnlyThePartsBetweenTheFirstAndLastMove() {
        val layout = SourcePartLayout(listOf(2, 2, 2, 2))
        val newToOld = intArrayOf(0, 1, 4, 2, 3, 5, 6, 7)

        assertParts(
            listOf(Keep(0), Write(1, listOf(Pages(4, 4), Pages(2, 2))), Write(2, listOf(Pages(3, 3), Pages(5, 5))), Keep(3)),
            SourcePartPlan.reorder(layout, newToOld)
        )
    }

    @Test
    fun aReorderWithoutMovesKeepsEveryPart() {
        assertParts(listOf(Keep(0), Keep(1)), SourcePartPlan.reorder(SourcePartLayout(listOf(2, 1)), intArrayOf(0, 1, 2)))
        assertThrows(IllegalArgumentException::class.java) { SourcePartPlan.reorder(SourcePartLayout(listOf(2)), intArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { SourcePartPlan.reorder(SourcePartLayout(listOf(2)), intArrayOf(1, -1)) }
        assertThrows(IllegalArgumentException::class.java) { SourcePartPlan.reorder(SourcePartLayout(listOf(2)), intArrayOf(1, 1)) }
    }

    @Test
    fun rewritingPagesCopiesOnlyTheirPartsWhole() {
        val layout = SourcePartLayout(listOf(2, 2, 1))

        assertParts(listOf(Keep(0), Write(1, listOf(Pages(2, 3))), Write(2, listOf(Pages(4, 4)))), SourcePartPlan.rewritePages(layout, setOf(3, 4, 9)))
        assertParts(listOf(Keep(0), Keep(1), Keep(2)), SourcePartPlan.rewritePages(layout, emptySet()))
    }

    @Test
    fun aReplacedGroupBecomesOnePageInItsPart() {
        val layout = SourcePartLayout(listOf(4, 4))
        val plan = SourcePartPlan.replaceGroups(layout, perGroup = 2, groups = setOf(1))

        assertParts(listOf(Write(0, listOf(Pages(0, 1), Replacement(1))), Keep(1)), plan)
        assertEquals(listOf(3, 4), plan.pageCounts(layout))
    }

    @Test
    fun aReplacedGroupAcrossAPartBoundaryLeavesItsOtherPagesOut() {
        val layout = SourcePartLayout(listOf(3, 3))
        val plan = SourcePartPlan.replaceGroups(layout, perGroup = 2, groups = setOf(1))

        assertParts(listOf(Write(0, listOf(Pages(0, 1), Replacement(1))), Write(1, listOf(Pages(4, 5)))), plan)
    }

    @Test
    fun aPartHoldingOnlyTheRestOfAReplacedGroupDropsOut() {
        val layout = SourcePartLayout(listOf(1, 2, 1))
        val plan = SourcePartPlan.replaceGroups(layout, perGroup = 3, groups = setOf(0))

        assertParts(listOf(Write(0, listOf(Replacement(0))), Keep(2)), plan)
    }

    @Test
    fun aGroupBeyondTheLastPageIsIgnored() {
        assertParts(
            listOf(Write(0, listOf(Replacement(0), Pages(2, 2)))),
            SourcePartPlan.replaceGroups(SourcePartLayout(listOf(3)), perGroup = 2, groups = setOf(0, 5))
        )
    }

    private fun assertParts(expected: List<SourcePartPlan.Part>, actual: SourcePartPlan) {
        assertEquals(expected, actual.parts)
    }
}
