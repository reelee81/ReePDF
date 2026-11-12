package hu.reelee81.reepdf.pdf

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfThumbnailSchedulerTest {
    private class Engine : PdfViewerEngine<String> {
        override val pageCount = 10
        val renders: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val closed = CountDownLatch(1)

        @Volatile
        var draw: (Int, PdfRenderAbort) -> Unit = { _, _ -> }

        override fun pageSize(pageIndex: Int) = PdfViewerEngine.PageSize(100f, 100f)

        override fun render(pageIndex: Int, zoom: Float, left: Int, top: Int, target: String, abort: PdfRenderAbort) {
            renders += "$pageIndex $target"
            draw(pageIndex, abort)
        }

        override fun search(pageIndex: Int, needle: String) = PdfTextHits.NONE

        override fun count(pageIndex: Int, needle: String) = 0

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

    private class Harness {
        val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val engines: MutableList<Engine> = Collections.synchronizedList(mutableListOf())
        val results = LinkedBlockingQueue<Pair<String, Result<String>>>()
        val released = LinkedBlockingQueue<String>()
        val pendingWhileDelivered: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        private val targets = AtomicInteger()

        @Volatile
        var draw: (Int, PdfRenderAbort) -> Unit = { _, _ -> }

        @Volatile
        var openFailure: Exception? = null

        @Volatile
        var beforeOpen: () -> Unit = {}

        @Volatile
        var openDespiteCancel = false

        val scheduler: PdfThumbnailScheduler<String, String>

        init {
            scheduler = PdfThumbnailScheduler(
                open = { path -> openDocument(path) },
                newTarget = { width, height -> "${width}x$height-${targets.incrementAndGet()}" },
                release = released::put,
                finished = { key, result ->
                    pendingWhileDelivered += scheduler.isPending(key)
                    results.put(key to result)
                }
            )
        }

        private suspend fun openDocument(path: String): PdfViewerDocument<String> {
            opened += path
            beforeOpen()
            openFailure?.let { throw it }
            val engine = Engine().also { it.draw = draw }
            engines += engine
            return if (openDespiteCancel) {
                withContext(NonCancellable) { PdfViewerDocument.open { engine } }
            } else {
                PdfViewerDocument.open { engine }
            }
        }

        fun request(key: String, page: Int, path: String = "a.pdf", maxWidth: Int = 100, maxHeight: Int = 100) =
            scheduler.request(key, path, page, maxWidth, maxHeight)

        fun next(): Pair<String, Result<String>> = checkNotNull(results.poll(5, TimeUnit.SECONDS)) { "No result" }

        fun nothingMore() = assertNull(results.poll(200, TimeUnit.MILLISECONDS))

        fun nextReleased(): String = checkNotNull(released.poll(5, TimeUnit.SECONDS)) { "Nothing released" }

        fun createdTargets() = targets.get()
    }

    private fun CountDownLatch.reached() = assertTrue(await(5, TimeUnit.SECONDS))

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("Timed out", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun waitForAbort(abort: PdfRenderAbort, started: CountDownLatch) {
        val aborted = CountDownLatch(1)
        abort.whileBound(aborted::countDown) {
            started.countDown()
            aborted.await(5, TimeUnit.SECONDS)
        }
        throw IllegalStateException("aborted by the cookie")
    }

    @Test
    fun aSecondRequestForAKeyStillPendingIsRejectedAndThePageRendersOnce() {
        val harness = Harness()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.draw = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        assertTrue(harness.request("k0", 0))
        started.reached()
        assertTrue(harness.scheduler.isPending("k0"))
        assertFalse(harness.request("k0", 0))
        release.countDown()
        val (key, result) = harness.next()
        harness.nothingMore()
        eventually { !harness.scheduler.isPending("k0") }
        harness.scheduler.shutdown()

        assertEquals("k0", key)
        assertEquals("100x100-1", result.getOrNull())
        assertEquals(listOf("0 100x100-1"), harness.engines.single().renders)
        assertEquals(listOf(true), harness.pendingWhileDelivered)
        assertTrue(harness.released.isEmpty())
    }

    @Test
    fun theBoxOfTheRequestGoesToTheFittedRender() {
        val harness = Harness()

        harness.request("k0", 3, maxWidth = 80, maxHeight = 40)
        val result = harness.next().second
        harness.scheduler.shutdown()

        assertEquals("40x40-1", result.getOrNull())
        assertEquals(listOf("3 40x40-1"), harness.engines.single().renders)
    }

    @Test
    fun retainingOtherKeysAbortsTheRunningRenderReleasesItsTargetAndRendersTheKeptOne() {
        val harness = Harness()
        val started = CountDownLatch(1)
        harness.draw = { page, abort -> if (page == 0) waitForAbort(abort, started) }

        harness.request("k0", 0)
        started.reached()
        harness.request("k1", 1)
        harness.scheduler.retainOnly(setOf("k1"))

        assertFalse(harness.scheduler.isPending("k0"))
        assertTrue(harness.scheduler.isPending("k1"))
        val (key, result) = harness.next()
        assertEquals("100x100-1", harness.nextReleased())
        harness.nothingMore()
        harness.scheduler.shutdown()

        assertEquals("k1", key)
        assertEquals("100x100-2", result.getOrNull())
        assertFalse(harness.scheduler.hasFailed("k0"))
    }

    @Test
    fun aCancelledRequestThatHasNotStartedNeverReachesTheEngineOrCreatesATarget() {
        val harness = Harness()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.draw = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        harness.request("k0", 0)
        started.reached()
        harness.request("k1", 1)
        Thread.sleep(100)
        harness.scheduler.cancelAll()
        release.countDown()

        assertEquals("100x100-1", harness.nextReleased())
        harness.nothingMore()
        assertEquals(listOf("0 100x100-1"), harness.engines.single().renders)
        assertEquals(1, harness.createdTargets())
        assertTrue(harness.request("k1", 1))
        assertEquals("k1", harness.next().first)
        harness.scheduler.shutdown()
    }

    @Test
    fun aFailedPageIsReportedRememberedAndRenderedAgainOnlyOnANewRequest() {
        val harness = Harness()
        val error = IllegalStateException("Overly large image")
        harness.draw = { page, _ -> if (page == 2) throw error }

        harness.request("k2", 2)
        val (key, result) = harness.next()
        eventually { !harness.scheduler.isPending("k2") }

        assertEquals("k2", key)
        assertSame(error, result.exceptionOrNull())
        assertTrue(harness.scheduler.hasFailed("k2"))
        assertEquals("100x100-1", harness.nextReleased())

        val release = CountDownLatch(1)
        harness.engines.single().draw = { page, _ ->
            if (page == 2) {
                release.await(5, TimeUnit.SECONDS)
                throw error
            }
        }
        assertTrue(harness.request("k2", 2))
        assertFalse(harness.scheduler.hasFailed("k2"))
        release.countDown()
        assertSame(error, harness.next().second.exceptionOrNull())
        eventually { harness.scheduler.hasFailed("k2") }
        harness.scheduler.shutdown()

        assertEquals(listOf("2 100x100-1", "2 100x100-2"), harness.engines.single().renders)
    }

    @Test
    fun aFailedOpenIsReportedAndTheNextRequestOpensAgain() {
        val harness = Harness()
        val error = IOException("The document needs a password")
        harness.openFailure = error

        harness.request("k0", 0)
        assertSame(error, harness.next().second.exceptionOrNull())
        eventually { harness.scheduler.hasFailed("k0") }
        harness.openFailure = null
        harness.request("k1", 1)
        val result = harness.next().second
        harness.scheduler.shutdown()

        assertEquals("100x100-1", result.getOrNull())
        assertEquals(listOf("a.pdf", "a.pdf"), harness.opened)
        assertEquals(0, harness.released.size)
    }

    @Test
    fun aPathIsOpenedOnceForManyRequestsAndEachPathGetsItsOwnDocument() {
        val harness = Harness()

        for (page in 0 until 5) harness.request("a$page", page)
        harness.request("b0", 0, path = "b.pdf")
        val keys = (0 until 6).map { harness.next().first }.toSet()
        harness.scheduler.shutdown()

        assertEquals(setOf("a0", "a1", "a2", "a3", "a4", "b0"), keys)
        assertEquals(listOf("a.pdf", "b.pdf"), harness.opened.sorted())
        assertEquals(listOf(1, 5), harness.engines.map { it.renders.size }.sorted())
    }

    @Test
    fun closingTheDocumentsClosesThemForgetsFailuresAndTheNextRequestOpensAgain() {
        val harness = Harness()
        harness.draw = { page, _ -> if (page == 1) throw IllegalStateException("broken page") }

        harness.request("k0", 0)
        harness.request("k1", 1)
        harness.next()
        harness.next()
        eventually { harness.scheduler.hasFailed("k1") }
        harness.scheduler.closeDocuments()

        harness.engines.single().closed.reached()
        assertFalse(harness.scheduler.hasFailed("k1"))
        harness.request("k2", 2)
        assertEquals("k2", harness.next().first)
        harness.scheduler.shutdown()

        assertEquals(listOf("a.pdf", "a.pdf"), harness.opened)
        assertEquals(2, harness.engines.size)
    }

    @Test
    fun closingTheDocumentsAbortsTheRunningRenderAndDeliversNothing() {
        val harness = Harness()
        val started = CountDownLatch(1)
        harness.draw = { _, abort -> waitForAbort(abort, started) }

        harness.request("k0", 0)
        started.reached()
        harness.scheduler.closeDocuments()

        assertFalse(harness.scheduler.isPending("k0"))
        assertEquals("100x100-1", harness.nextReleased())
        harness.engines.single().closed.reached()
        harness.nothingMore()
        assertFalse(harness.scheduler.hasFailed("k0"))
        harness.scheduler.shutdown()
    }

    @Test
    fun aDocumentThatFinishesOpeningAfterTheDocumentsWereClosedIsClosedAndNotUsed() {
        val harness = Harness()
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.openDespiteCancel = true
        harness.beforeOpen = {
            opening.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        harness.request("k0", 0)
        opening.reached()
        harness.scheduler.closeDocuments()
        release.countDown()

        eventually { harness.engines.size == 1 }
        harness.engines.single().closed.reached()
        harness.nothingMore()
        assertTrue(harness.engines.single().renders.isEmpty())
        assertEquals(0, harness.createdTargets())
        harness.beforeOpen = {}
        harness.request("k1", 1)
        assertEquals("k1", harness.next().first)
        harness.scheduler.shutdown()

        assertEquals(2, harness.engines.size)
    }

    @Test
    fun focusedRequestsGoFirstInFocusOrderAndTheRestFollowInArrivalOrder() {
        val harness = Harness()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.draw = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        harness.request("k0", 0)
        started.reached()
        harness.request("k1", 1)
        harness.request("k2", 2)
        harness.request("k3", 3)
        harness.scheduler.focus(listOf("k0", "k3", "k2"))
        release.countDown()
        val keys = (0 until 4).map { harness.next().first }
        harness.scheduler.shutdown()

        assertEquals(listOf("k0", "k3", "k2", "k1"), keys)
        assertEquals(listOf(0, 3, 2, 1), harness.engines.single().renders.map { it.substringBefore(' ').toInt() })
    }

    @Test
    fun aRunningRenderThatLeavesTheFocusStopsAndTheFocusedOneFollows() {
        val harness = Harness()
        val started = CountDownLatch(1)
        harness.draw = { page, abort -> if (page == 0) waitForAbort(abort, started) }

        harness.request("k0", 0)
        started.reached()
        harness.request("k1", 1)
        harness.scheduler.focus(listOf("k1"))

        assertFalse(harness.scheduler.isPending("k0"))
        assertTrue(harness.scheduler.isPending("k1"))
        val (key, result) = harness.next()
        assertEquals("100x100-1", harness.nextReleased())
        harness.nothingMore()
        harness.scheduler.shutdown()

        assertEquals("k1", key)
        assertEquals("100x100-2", result.getOrNull())
        assertFalse(harness.scheduler.hasFailed("k0"))
    }

    @Test
    fun aRunningRenderThatStaysInTheFocusFinishes() {
        val harness = Harness()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.draw = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        harness.request("k0", 0)
        started.reached()
        harness.scheduler.focus(listOf("k0", "k1"))
        release.countDown()
        val (key, result) = harness.next()
        harness.scheduler.shutdown()

        assertEquals("k0", key)
        assertEquals("100x100-1", result.getOrNull())
        assertTrue(harness.released.isEmpty())
    }

    @Test
    fun afterShutdownEveryRequestIsRejectedAndTheDocumentsAreClosed() {
        val harness = Harness()

        harness.request("k0", 0)
        harness.next()
        harness.scheduler.shutdown()

        harness.engines.single().closed.reached()
        assertFalse(harness.request("k1", 1))
        assertFalse(harness.scheduler.isPending("k1"))
        harness.nothingMore()
    }
}
