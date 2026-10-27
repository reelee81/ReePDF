package hu.reelee81.reepdf.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourcePageArraysTest {
    @Test
    fun rotationsAreResetOnlyWhenTheSizeDiffers() {
        val rotation = intArrayOf(1, 2, 3)
        assertSame(rotation, SourcePageArrays.rotationsSized(rotation, 3))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), SourcePageArrays.rotationsSized(rotation, 4))
        assertArrayEquals(intArrayOf(0, 0), SourcePageArrays.rotationsSized(rotation, 2))
    }

    @Test
    fun appendingGrowsRotationsWithZerosAndNeverShrinksThem() {
        val rotation = intArrayOf(1, 2, 3)
        assertArrayEquals(intArrayOf(1, 2, 3, 0, 0), SourcePageArrays.rotationsGrown(rotation, 5))
        assertSame(rotation, SourcePageArrays.rotationsGrown(rotation, 3))
        assertSame(rotation, SourcePageArrays.rotationsGrown(rotation, 2))
    }

    @Test
    fun positionsAreResizedWithTheDefaultAndInvalidValuesAreRepaired() {
        val positions = intArrayOf(1, 0, 12)
        assertSame(positions, SourcePageArrays.positionsSized(positions, 3, 7))
        assertArrayEquals(intArrayOf(1, 5, 5, 7, 7), SourcePageArrays.positionsSized(positions, 5, 7))
        assertArrayEquals(intArrayOf(1, 5), SourcePageArrays.positionsSized(positions, 2, 7))
        assertArrayEquals(intArrayOf(5, 5), SourcePageArrays.positionsSized(IntArray(0), 2, 0))
    }

    @Test
    fun rotatingGroupsTurnsEveryPageOfTheGroupAndWrapsAround() {
        val rotation = intArrayOf(0, 1, 2, 3, 3, 0, 1)
        val rotated = SourcePageArrays.withRotatedGroups(rotation, listOf(1, 3), per = 2, total = 7)

        assertArrayEquals(intArrayOf(0, 1, 3, 0, 3, 0, 2), rotated)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 3, 0, 1), rotation)
        assertArrayEquals(
            intArrayOf(2, 1, 2),
            SourcePageArrays.withRotatedGroups(intArrayOf(0, 1, 2), listOf(0, 0, 5), per = 1, total = 3)
        )
    }

    @Test
    fun positioningGroupsSetsEveryPageOfTheGroup() {
        val positions = intArrayOf(5, 5, 5, 5, 5)
        val positioned = SourcePageArrays.withGroupPosition(positions, listOf(0, 2, 9), per = 2, total = 5, position = 3)

        assertArrayEquals(intArrayOf(3, 3, 5, 5, 3), positioned)
        assertArrayEquals(intArrayOf(5, 5, 5, 5, 5), positions)
    }

    @Test
    fun removalDropsTheIndicesAndKeepsTheOrder() {
        val values = intArrayOf(10, 11, 12, 13, 14, 15)
        assertArrayEquals(intArrayOf(10, 13, 15), SourcePageArrays.afterRemoval(values, setOf(1, 2, 4, 99)))
        assertArrayEquals(values, SourcePageArrays.afterRemoval(values, emptySet()))
        assertArrayEquals(IntArray(0), SourcePageArrays.afterRemoval(values, (0..5).toSet()))
    }

    @Test
    fun reorderingMapsWholeGroupsIncludingAShortLastGroup() {
        assertArrayEquals(
            intArrayOf(4, 2, 3, 0, 1),
            SourcePageArrays.reorderedIndices(listOf(2, 1, 0), per = 2, total = 5)
        )
        assertArrayEquals(
            intArrayOf(2, 0, 1),
            SourcePageArrays.reorderedIndices(listOf(1, 0, 7), per = 2, total = 3)
        )
        assertArrayEquals(
            intArrayOf(2, 3, -1, -1),
            SourcePageArrays.reorderedIndices(listOf(1), per = 2, total = 4)
        )
    }

    @Test
    fun reorderingMovesRotationsAndPositionsWithTheirPages() {
        val newToOld = intArrayOf(4, 2, 3, 0, 1, -1)
        val rotation = intArrayOf(1, 2, 3, 0, 1)
        val positions = intArrayOf(1, 2, 0, 4)

        assertArrayEquals(
            intArrayOf(1, 3, 0, 1, 2, 0),
            SourcePageArrays.rotationsAfterReorder(rotation, newToOld)
        )
        assertArrayEquals(
            intArrayOf(8, 5, 4, 1, 2, NupGeometry.DEFAULT_POSITION),
            SourcePageArrays.positionsAfterReorder(positions, newToOld, defaultPosition = 8)
        )
    }
}
