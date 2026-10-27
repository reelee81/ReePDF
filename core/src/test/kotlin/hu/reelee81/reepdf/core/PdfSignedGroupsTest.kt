package hu.reelee81.reepdf.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSignedGroupsTest {
    @Test
    fun signingTheSecondGroupOfAFourUpLayoutKeepsTheFollowingPagesAligned() {
        val per = 4
        val rotation = intArrayOf(0, 1, 2, 3, 1, 1, 1, 1, 3, 2, 1, 0)
        val position = intArrayOf(1, 2, 3, 4, 5, 5, 5, 5, 6, 7, 8, 9)
        val signedStarts = listOf(4)
        val merged = PdfSignedGroups.mergedIndices(signedStarts, per, 12)

        assertEquals(setOf(5, 6, 7), merged)
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 0, 3, 2, 1, 0),
            PdfSignedGroups.afterMerge(rotation, 12, signedStarts, merged, signedValue = 0, missingValue = 0)
        )
        assertArrayEquals(
            intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            PdfSignedGroups.afterMerge(position, 12, signedStarts, merged, signedValue = 5, missingValue = 5)
        )
    }

    @Test
    fun aShortLastGroupAndOneUpLayoutsAreHandled() {
        assertEquals(setOf(9), PdfSignedGroups.mergedIndices(listOf(8), 4, 10))
        assertTrue(PdfSignedGroups.mergedIndices(listOf(0, 3), 1, 5).isEmpty())
        assertArrayEquals(
            intArrayOf(0, 2, 0),
            PdfSignedGroups.afterMerge(intArrayOf(3, 2, 1), 3, listOf(0, 2), emptySet(), signedValue = 0, missingValue = 0)
        )
    }

    @Test
    fun missingValuesAreFilledBeforeMerging() {
        assertArrayEquals(
            intArrayOf(1, 0, 7),
            PdfSignedGroups.afterMerge(intArrayOf(1), 5, listOf(1), setOf(2, 3), signedValue = 0, missingValue = 7)
        )
    }
}
