package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePartLayoutTest {
    private val layout = SourcePartLayout(listOf(3, 1, 4))

    @Test
    fun thePartsFollowEachOther() {
        assertEquals(3, layout.parts)
        assertEquals(8, layout.total)
        assertEquals(listOf(0, 3, 4), (0 until layout.parts).map { layout.start(it) })
        assertEquals(listOf(2, 3, 7), (0 until layout.parts).map { layout.end(it) })
    }

    @Test
    fun aGlobalPageIsFoundInItsPart() {
        assertEquals(listOf(0, 0, 0, 1, 2, 2, 2, 2), (0 until layout.total).map { layout.partOf(it) })
        assertEquals(listOf(0, 1, 2, 0, 0, 1, 2, 3), (0 until layout.total).map { layout.local(it) })
        assertThrows(IllegalArgumentException::class.java) { layout.partOf(8) }
        assertThrows(IllegalArgumentException::class.java) { layout.partOf(-1) }
    }

    @Test
    fun aRangeSplitsAtThePartBoundaries() {
        assertRuns(listOf(SourcePartLayout.Run(0, 1, 2), SourcePartLayout.Run(1, 0, 0), SourcePartLayout.Run(2, 0, 1)), layout.runs(1, 5))
        assertRuns(listOf(SourcePartLayout.Run(2, 1, 3)), layout.runs(5, 7))
        assertRuns(emptyList(), layout.runs(4, 3))
    }

    @Test
    fun anEmptySourceHasNoParts() {
        val empty = SourcePartLayout(emptyList())
        assertEquals(0, empty.parts)
        assertEquals(0, empty.total)
        assertThrows(IllegalArgumentException::class.java) { empty.partOf(0) }
    }

    @Test
    fun aPartWithoutPagesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourcePartLayout(listOf(2, 0)) }
    }

    private fun assertRuns(expected: List<SourcePartLayout.Run>, actual: List<SourcePartLayout.Run>) {
        assertEquals(expected, actual)
    }
}
