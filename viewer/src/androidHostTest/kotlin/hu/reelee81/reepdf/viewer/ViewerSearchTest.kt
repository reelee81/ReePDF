package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSearchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val jumps = mutableListOf<Pair<Int, Rect>>()
    private val search = ViewerSearch(scope, 0L) { page, bounds -> jumps += page to bounds }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun document(pageCount: Int, hits: Map<Int, Int>) =
        FakeTextDocument(pageCount).apply { hitCounts = { hits[it] ?: 0 } }.also(search::attach)

    private fun searches(query: String, vararg pages: Int) = pages.map { "search $it $query" }

    private fun counts(query: String, vararg pages: Int) = pages.map { "count $it $query" }

    @Test
    fun theSearchGoesFromTheGivenPageAroundTheEndAndJumpsToTheFirstHitAfterIt() {
        val doc = document(6, mapOf(1 to 2, 4 to 1))

        search.start()
        search.setQuery("word", 3)

        assertEquals(searches("word", 3) + counts("word", 4) + searches("word", 4) + counts("word", 5, 0, 1, 2), doc.calls)
        assertEquals(ViewerSearch.Hit(4, 0), search.current)
        assertEquals(3, search.total)
        assertEquals(3, search.position)
        assertTrue(search.isFinished)
        assertFalse(search.noMatch)
        assertEquals(listOf(4 to Rect(0f, 20f, 5f, 30f)), jumps)
    }

    @Test
    fun thePositionGrowsWhenHitsOnEarlierPagesAreFoundLater() {
        val doc = document(6, mapOf(1 to 2, 4 to 1))
        doc.gated = true

        search.setQuery("word", 3)
        assertNull(search.current)
        doc.openGate()
        assertEquals(ViewerSearch.Hit(4, 0), search.current)
        assertEquals(1, search.position)
        assertEquals(1, search.total)
        doc.openGate()
        doc.openGate()
        doc.openGate()
        assertEquals(3, search.position)
        assertEquals(3, search.total)
        assertFalse(search.isFinished)
        doc.openGate()

        assertTrue(search.isFinished)
        assertEquals(0, doc.waiting)
    }

    @Test
    fun nextAndPreviousStepThroughTheHitsAndWrapAround() {
        val doc = document(6, mapOf(1 to 2, 4 to 1))
        search.setQuery("word", 3)
        doc.calls.clear()

        search.next()
        assertEquals(ViewerSearch.Hit(1, 0), search.current)
        assertEquals(1, search.position)
        search.next()
        assertEquals(ViewerSearch.Hit(1, 1), search.current)
        assertEquals(2, search.position)
        search.next()
        assertEquals(ViewerSearch.Hit(4, 0), search.current)
        assertEquals(3, search.position)
        search.previous()
        assertEquals(ViewerSearch.Hit(1, 1), search.current)

        assertEquals(searches("word", 1, 4, 1), doc.calls)
        assertEquals(listOf(4, 1, 1, 4, 1), jumps.map { it.first })
        assertEquals(Rect(10f, 20f, 15f, 30f), jumps[2].second)
    }

    @Test
    fun aSingleHitWrapsOntoItself() {
        document(3, mapOf(2 to 1))
        search.setQuery("word", 0)

        search.next()
        search.previous()

        assertEquals(ViewerSearch.Hit(2, 0), search.current)
        assertEquals(1, search.position)
    }

    @Test
    fun aBlankQuerySearchesNothingAndStopForgetsEverything() {
        val doc = document(3, mapOf(0 to 1))

        search.start()
        search.setQuery("   ", 0)
        assertTrue(doc.calls.isEmpty())
        assertFalse(search.isFinished)

        search.setQuery("word", 0)
        search.stop()

        assertFalse(search.isActive)
        assertEquals("", search.query)
        assertNull(search.current)
        assertEquals(0, search.total)
        assertEquals(0, search.position)
        assertNull(search.hitsOf(0))
        assertFalse(search.isFinished)
    }

    @Test
    fun aNewQueryCancelsTheRunningSearch() {
        val doc = document(2, mapOf(1 to 2))
        doc.gated = true

        search.setQuery("a", 0)
        search.setQuery("b", 0)
        doc.gated = false
        doc.openGate()
        assertFalse(search.isFinished)
        doc.openGate()

        assertEquals(listOf("search 0 a", "count 1 a", "search 0 b", "count 1 b", "search 1 b"), doc.calls)
        assertEquals("b", search.query)
        assertTrue(search.isFinished)
        assertEquals(2, search.total)
        assertEquals(1, jumps.size)
    }

    @Test
    fun noMatchShowsOnlyAfterTheWholeDocumentWasSearched() {
        val doc = document(2, emptyMap())
        doc.gated = true

        search.setQuery("word", 0)
        assertFalse(search.noMatch)
        doc.openGate()

        assertTrue(search.noMatch)
        assertNull(search.current)
        assertTrue(jumps.isEmpty())
    }

    @Test
    fun aFailingPageCountsAsNoHitAndTheSearchGoesOn() {
        val doc = document(3, mapOf(1 to 5, 2 to 1))
        doc.failingPages = setOf(1)

        search.setQuery("word", 0)

        assertEquals(searches("word", 0) + counts("word", 1, 2) + searches("word", 2), doc.calls)
        assertEquals(1, search.total)
        assertEquals(ViewerSearch.Hit(2, 0), search.current)
    }

    @Test
    fun hitRectanglesAreKeptOnlyForTheVisiblePagesAndTheCurrentHit() {
        val doc = document(6, mapOf(1 to 2, 4 to 1, 5 to 3))
        search.onVisible(0..1)

        search.setQuery("w", 0)
        assertNotNull(search.hitsOf(1))
        assertNull(search.hitsOf(4))
        assertNull(search.hitsOf(5))

        doc.calls.clear()
        search.onVisible(4..5)
        assertEquals(searches("w", 4, 5), doc.calls)
        assertEquals(1, search.hitsOf(4)?.count)
        assertEquals(3, search.hitsOf(5)?.count)
        assertNotNull(search.hitsOf(1))

        search.onVisible(2..3)
        assertNull(search.hitsOf(4))
        assertNull(search.hitsOf(5))
        assertNotNull(search.hitsOf(1))
    }

    @Test
    fun aPausedSearchGoesOnWhereItStopped() {
        val doc = document(3, mapOf(2 to 1))
        doc.gated = true

        search.setQuery("word", 0)
        search.pause()
        assertEquals(searches("word", 0) + counts("word", 1), doc.calls)
        search.resume()
        doc.gated = false
        doc.openGate()
        doc.openGate()

        assertEquals(searches("word", 0) + counts("word", 1, 1, 2) + searches("word", 2), doc.calls)
        assertTrue(search.isFinished)
        assertEquals(ViewerSearch.Hit(2, 0), search.current)
        search.resume()
        assertEquals(5, doc.calls.size)
    }

    @Test
    fun theRunSearchesTheStartPageCountsTheOthersAndAsksForBoxesOnlyForTheFirstHitAndTheVisiblePages() {
        val doc = document(6, mapOf(1 to 2, 3 to 1, 4 to 1, 5 to 3))
        search.onVisible(4..5)

        search.setQuery("w", 0)

        val expected = searches("w", 0) + counts("w", 1) + searches("w", 1) + counts("w", 2, 3, 4) + searches("w", 4) +
            counts("w", 5) + searches("w", 5)
        assertEquals(expected, doc.calls)
        assertEquals(7, search.total)
        assertEquals(ViewerSearch.Hit(1, 0), search.current)
        assertNull(search.hitsOf(3))
        assertEquals(1, search.hitsOf(4)?.count)
        assertEquals(3, search.hitsOf(5)?.count)
    }

    @Test
    fun aHitOnTheStartPageIsShownBeforeTheCountingDocumentIsUsed() {
        val doc = document(3, mapOf(0 to 1, 2 to 1))
        doc.gated = true

        search.setQuery("w", 0)

        assertEquals(ViewerSearch.Hit(0, 0), search.current)
        assertEquals(listOf(0), jumps.map { it.first })
        assertEquals(searches("w", 0) + counts("w", 1), doc.calls)
        assertFalse(search.isFinished)
        doc.openGate()
        doc.openGate()
        assertTrue(search.isFinished)
        assertEquals(2, search.total)
    }

    @Test
    fun aHitWithoutBoxesIsNeitherTheFirstHitNorJumpedTo() {
        val doc = document(3, mapOf(1 to 2, 2 to 1))
        doc.boxCounts = { if (it == 1) 0 else doc.hitCounts(it) }
        search.onVisible(1..1)

        search.setQuery("w", 0)
        assertEquals(ViewerSearch.Hit(2, 0), search.current)
        assertEquals(3, search.position)
        search.previous()

        assertEquals(ViewerSearch.Hit(1, 1), search.current)
        assertEquals(listOf(2), jumps.map { it.first })
    }

    @Test
    fun theCountingDocumentIsKeptForANewWordAndReleasedAtTheEndOnPauseForAnEmptyBoxOnStopAndOnAttach() {
        val doc = document(3, mapOf(1 to 1))

        search.setQuery("a", 0)
        assertTrue(search.isFinished)
        assertFalse(doc.counting)

        doc.gated = true
        search.setQuery("b", 0)
        assertTrue(doc.counting)
        val released = doc.releases
        search.setQuery("bc", 0)
        assertEquals(released, doc.releases)
        assertTrue(doc.counting)
        search.pause()
        assertFalse(doc.counting)

        search.resume()
        assertTrue(doc.counting)
        search.setQuery("", 0)
        assertFalse(doc.counting)

        search.setQuery("c", 0)
        assertTrue(doc.counting)
        search.stop()
        assertFalse(doc.counting)

        search.setQuery("d", 0)
        assertTrue(doc.counting)
        search.attach(FakeTextDocument(1))
        assertFalse(doc.counting)
    }

    @Test
    fun attachingAnotherDocumentEndsTheSearch() {
        document(2, mapOf(0 to 1))
        search.start()
        search.setQuery("word", 0)

        search.attach(FakeTextDocument(5))

        assertFalse(search.isActive)
        assertNull(search.current)
        assertEquals("", search.query)
    }
}
