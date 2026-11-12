package hu.reelee81.reepdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewerStoreRuleTest {
    private val mb = 1024L * 1024

    private fun PdfViewerStoreRule.pagesUntilEmptying(limit: Int): Int? {
        for (page in 1..limit) if (afterPage()) return page
        return null
    }

    @Test
    fun withoutAMeterEveryTwoHundredthPageEmptiesTheStore() {
        val rule = PdfViewerStoreRule(null)

        assertEquals(200, rule.pagesUntilEmptying(1000))
        rule.emptied()
        assertEquals(200, rule.pagesUntilEmptying(1000))
    }

    @Test
    fun anEmptyingOnRequestRestartsThePageCount() {
        val rule = PdfViewerStoreRule(null)

        assertEquals(null, rule.pagesUntilEmptying(150))
        rule.emptied()

        assertEquals(200, rule.pagesUntilEmptying(1000))
    }

    @Test
    fun withAMeterOnlyGrowthOfMoreThan64MbCounts() {
        var bytes = 300 * mb
        val rule = PdfViewerStoreRule { bytes }

        assertEquals(null, rule.pagesUntilEmptying(1000))
        bytes += 64 * mb
        assertFalse(rule.afterPage())
        bytes += 1
        assertTrue(rule.afterPage())
    }

    @Test
    fun theGrowthIsMeasuredFromTheLastEmptying() {
        var bytes = 10 * mb
        val rule = PdfViewerStoreRule { bytes }

        bytes = 80 * mb
        assertTrue(rule.afterPage())
        rule.emptied()

        bytes = 144 * mb
        assertFalse(rule.afterPage())
        bytes += 1
        assertTrue(rule.afterPage())
    }

    @Test
    fun lessMemoryThanAtTheLastEmptyingNeverEmpties() {
        var bytes = 500 * mb
        val rule = PdfViewerStoreRule { bytes }

        bytes = 100 * mb
        assertFalse(rule.afterPage())
        bytes = 560 * mb
        assertFalse(rule.afterPage())
    }
}
