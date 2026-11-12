package hu.reelee81.reepdf.pdf

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchCounterTest {
    private class Engine(private val counting: (Int) -> Unit) : PdfViewerEngine<String> {
        override val pageCount = 10
        val counts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        private val closed = CountDownLatch(1)

        val isClosed: Boolean
            get() = closed.count == 0L

        fun awaitClose() = assertTrue(closed.await(5, TimeUnit.SECONDS))

        override fun pageSize(pageIndex: Int) = PdfViewerEngine.PageSize(100f, 100f)

        override fun render(pageIndex: Int, zoom: Float, left: Int, top: Int, target: String, abort: PdfRenderAbort) = Unit

        override fun search(pageIndex: Int, needle: String) = PdfTextHits.NONE

        override fun count(pageIndex: Int, needle: String): Int {
            counts += pageIndex
            counting(pageIndex)
            return pageIndex + needle.length
        }

        override fun word(pageIndex: Int, point: PdfTextPoint, tolerance: Float): PdfTextSpan? = null

        override fun caret(pageIndex: Int, point: PdfTextPoint): PdfTextPoint? = null

        override fun highlight(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?) = FloatArray(0)

        override fun copy(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?) = ""

        override fun text(pageIndex: Int) = ""

        override fun emptyStore() = Unit

        override fun dropLoadedObjects() = Unit

        override fun releaseFreeMemory() = Unit

        override fun close() = closed.countDown()
    }

    private class Harness(reopenPages: Int = PdfSearchCounter.REOPEN_PAGES) {
        val engines: MutableList<Engine> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var counting: (Int) -> Unit = {}

        @Volatile
        var beforeOpen: suspend () -> Unit = {}

        val counter = PdfSearchCounter({
            beforeOpen()
            val engine = Engine { counting(it) }
            engines += engine
            PdfViewerDocument.open { engine }
        }, reopenPages)

        fun pagesPerEngine(): List<List<Int>> = engines.map { it.counts.toList() }
    }

    @Test
    fun theDocumentOpensOnTheFirstCountAndServesTheNextOnes() = runBlocking {
        val harness = Harness()
        assertTrue(harness.engines.isEmpty())

        val first = harness.counter.count(3, "ab")
        val second = harness.counter.count(4, "ab")

        assertEquals(5, first)
        assertEquals(6, second)
        assertEquals(listOf(listOf(3, 4)), harness.pagesPerEngine())
        assertFalse(harness.engines.single().isClosed)
        harness.counter.release()
        harness.engines.single().awaitClose()
    }

    @Test
    fun theDocumentIsClosedAndReopenedAfterTheGivenNumberOfPages() = runBlocking {
        val harness = Harness(reopenPages = 2)

        for (page in 0 until 5) harness.counter.count(page, "a")

        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), harness.pagesPerEngine())
        harness.engines[0].awaitClose()
        harness.engines[1].awaitClose()
        assertFalse(harness.engines[2].isClosed)
        harness.counter.release()
        harness.engines[2].awaitClose()
    }

    @Test
    fun aReleaseClosesTheDocumentAndTheNextCountStartsAFreshOne() = runBlocking {
        val harness = Harness(reopenPages = 2)

        harness.counter.release()
        harness.counter.count(0, "a")
        harness.counter.release()
        harness.counter.release()
        harness.engines[0].awaitClose()
        harness.counter.count(1, "a")
        harness.counter.count(2, "a")
        harness.counter.count(3, "a")

        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), harness.pagesPerEngine())
        harness.engines[1].awaitClose()
        assertFalse(harness.engines[2].isClosed)
        harness.counter.release()
        harness.engines[2].awaitClose()
    }

    @Test
    fun aReleaseWhileTheDocumentOpensClosesItAndCancelsThatCount() = runBlocking {
        val harness = Harness()
        val opening = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        harness.beforeOpen = {
            opening.complete(Unit)
            proceed.await()
        }

        val first = async(Dispatchers.Default) { runCatching { harness.counter.count(0, "a") } }
        opening.await()
        harness.counter.release()
        harness.beforeOpen = {}
        proceed.complete(Unit)
        val failure = first.await().exceptionOrNull()
        harness.engines[0].awaitClose()
        val next = harness.counter.count(1, "a")

        assertTrue(failure is CancellationException)
        assertEquals(2, next)
        assertEquals(listOf(emptyList(), listOf(1)), harness.pagesPerEngine())
        assertFalse(harness.engines[1].isClosed)
        harness.counter.release()
        harness.engines[1].awaitClose()
    }

    @Test
    fun aFailedOpenReportsTheErrorAndTheNextCountTriesAgain() = runBlocking {
        val harness = Harness()
        harness.beforeOpen = { throw IOException("missing file") }

        val failure = runCatching { harness.counter.count(0, "a") }.exceptionOrNull()
        harness.beforeOpen = {}
        val next = harness.counter.count(0, "a")

        assertTrue(failure is IOException)
        assertEquals(1, next)
        assertEquals(listOf(listOf(0)), harness.pagesPerEngine())
        harness.counter.release()
        harness.engines.single().awaitClose()
    }

    @Test
    fun aCancelledCountLeavesTheOpenDocumentInUse() = runBlocking {
        val harness = Harness()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        harness.counting = { page ->
            if (page == 0) {
                started.countDown()
                finish.await(5, TimeUnit.SECONDS)
            }
        }

        val first = launch(Dispatchers.Default) { harness.counter.count(0, "a") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        first.cancel()
        finish.countDown()
        first.join()
        val next = harness.counter.count(1, "a")

        assertTrue(first.isCancelled)
        assertEquals(2, next)
        assertEquals(listOf(listOf(0, 1)), harness.pagesPerEngine())
        harness.counter.release()
        harness.engines.single().awaitClose()
    }
}
