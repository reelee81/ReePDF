package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageBatchTest {
    @Test
    fun withoutABudgetTheBatchSizeEndsTheBatch() {
        assertEnd(5, PageBatch.end(1, 100, 5, Long.MAX_VALUE) { 10 })
        assertEnd(3, PageBatch.end(1, 3, 5, Long.MAX_VALUE) { 10 })
        assertEnd(7, PageBatch.end(7, 7, 5, Long.MAX_VALUE) { 10 })
    }

    @Test
    fun theBudgetEndsTheBatchAtThePageThatReachesIt() {
        assertEnd(3, PageBatch.end(1, 100, 20, 30) { 10 })
        assertEnd(4, PageBatch.end(1, 100, 20, 31) { 10 })
        assertEnd(12, PageBatch.end(10, 100, 20, 22) { if (it == 12) 20 else 1 })
    }

    @Test
    fun aPageLargerThanTheBudgetIsABatchOfItsOwn() {
        assertEnd(1, PageBatch.end(1, 100, 20, 10) { 1_000 })
        assertEnd(4, PageBatch.end(4, 100, 20, 0) { 0 })
    }

    @Test
    fun onlyThePagesOfTheBatchAreMeasuredInOrder() {
        val measured = ArrayList<Int>()
        PageBatch.end(2, 100, 20, 30) { measured += it; 10 }
        assertEquals(listOf(2, 3, 4), measured)
    }

    @Test
    fun aNegativeCostCountsAsNothingAndABadBatchSizeAsOnePage() {
        assertEnd(3, PageBatch.end(1, 100, 3, 5) { if (it == 1) -100 else 1 })
        assertEnd(1, PageBatch.end(1, 100, 0, Long.MAX_VALUE) { 1 })
        assertEnd(Int.MAX_VALUE, PageBatch.end(Int.MAX_VALUE - 1, Int.MAX_VALUE, 20, Long.MAX_VALUE) { 1 })
    }

    @Test
    fun anEmptyRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PageBatch.end(5, 4, 20, 10) { 1 } }
    }

    private fun assertEnd(expected: Int, actual: Int) {
        assertEquals(expected, actual)
    }
}
