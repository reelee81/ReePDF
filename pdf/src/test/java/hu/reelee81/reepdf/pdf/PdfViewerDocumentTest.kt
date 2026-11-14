package hu.reelee81.reepdf.pdf

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewerDocumentTest {
    private class FakeEngine(override val pageCount: Int = 3) : PdfViewerEngine<String> {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val threads: MutableSet<Thread> = Collections.synchronizedSet(mutableSetOf())

        @Volatile
        var sizes: (Int) -> PdfViewerEngine.PageSize = { PdfViewerEngine.PageSize(100f + it, 200f + it) }

        @Volatile
        var renders: (Int, PdfRenderAbort) -> Unit = { _, _ -> }

        override fun pageSize(pageIndex: Int): PdfViewerEngine.PageSize {
            record("size $pageIndex")
            return sizes(pageIndex)
        }

        override fun render(pageIndex: Int, zoom: Float, left: Int, top: Int, target: String, abort: PdfRenderAbort) {
            record("render $pageIndex $zoom $left $top $target")
            renders(pageIndex, abort)
        }

        @Volatile
        var texts: (Int) -> String = { "text $it" }

        override fun search(pageIndex: Int, needle: String): PdfTextHits {
            record("search $pageIndex $needle")
            texts(pageIndex)
            return PdfTextHits(floatArrayOf(1f, 2f, 3f, 4f), intArrayOf(0, 1))
        }

        override fun count(pageIndex: Int, needle: String): Int {
            record("count $pageIndex $needle")
            texts(pageIndex)
            return 2
        }

        override fun word(pageIndex: Int, point: PdfTextPoint, tolerance: Float): PdfTextSpan {
            record("word $pageIndex ${point.x} ${point.y} $tolerance")
            return PdfTextSpan(point, PdfTextPoint(point.x + 1f, point.y), floatArrayOf(0f, 0f, 1f, 1f))
        }

        override fun caret(pageIndex: Int, point: PdfTextPoint): PdfTextPoint {
            record("caret $pageIndex ${point.x} ${point.y}")
            return PdfTextPoint(point.x + 2f, point.y)
        }

        override fun highlight(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): FloatArray {
            record("highlight $pageIndex ${from?.x} ${to?.x}")
            return floatArrayOf(5f, 6f, 7f, 8f)
        }

        override fun copy(pageIndex: Int, from: PdfTextPoint?, to: PdfTextPoint?): String {
            record("copy $pageIndex ${from?.x} ${to?.x}")
            return texts(pageIndex)
        }

        override fun text(pageIndex: Int): String {
            record("text $pageIndex")
            return texts(pageIndex)
        }

        override fun emptyStore() = record("empty")

        override fun dropLoadedObjects() = record("drop")

        override fun releaseFreeMemory() = record("free")

        @Volatile
        var closeFailure: Exception? = null

        override fun close() {
            record("close")
            closeFailure?.let { throw it }
        }

        fun renderedTargets(): List<String> = synchronized(events) {
            events.filter { it.startsWith("render") }.map { it.substringAfterLast(' ') }
        }

        fun only(prefix: String): List<String> = synchronized(events) { events.filter { it.startsWith(prefix) } }

        private fun record(event: String) {
            threads += Thread.currentThread()
            events += event
        }
    }

    private fun CountDownLatch.reached() = assertTrue(await(5, TimeUnit.SECONDS))

    private fun waitForAbort(abort: PdfRenderAbort, started: CountDownLatch) {
        val aborted = CountDownLatch(1)
        abort.whileBound(aborted::countDown) {
            started.countDown()
            aborted.await(5, TimeUnit.SECONDS)
        }
        throw IllegalStateException("aborted by the cookie")
    }

    private suspend fun open(engine: FakeEngine, nativeBytes: (() -> Long)? = null) =
        PdfViewerDocument.open(nativeBytes) { engine }

    private suspend fun PdfViewerDocument<String>.shut() {
        close()
        awaitClosed()
    }

    private suspend fun PdfViewerDocument<String>.fitted(page: Int, maxWidth: Int = 100, maxHeight: Int = 100) =
        renderFitted(page, maxWidth, maxHeight) { width, height -> "${width}x$height" }

    private fun squarePages(engine: FakeEngine) {
        engine.sizes = { PdfViewerEngine.PageSize(100f, 100f) }
    }

    @Test
    fun aFinishedRenderReportsItsDrawingTime() = runBlocking {
        val engine = FakeEngine()
        engine.renders = { _, _ -> Thread.sleep(DRAWING_MS) }
        val document = open(engine)
        val reported: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        assertEquals(PdfRenderResult.Done, document.render(0, 1f, 0, 0, "tile") { reported += it })
        engine.renders = { _, _ -> throw IllegalStateException("broken") }
        assertTrue(document.render(1, 1f, 0, 0, "tile") { reported += it } is PdfRenderResult.Failed)
        document.shut()

        assertEquals(1, reported.size)
        assertTrue(reported.single() >= DRAWING_MS)
    }

    @Test
    fun everyEngineCallRunsOnTheSameOwnThread() = runBlocking {
        val engine = FakeEngine()
        val document = open(engine)

        document.pageSizes()
        document.trimMemory()
        assertEquals(PdfRenderResult.Done, document.render(1, 2f, 10, 20, "tile"))
        document.shut()

        assertEquals(1, engine.threads.size)
        val thread = engine.threads.single()
        assertNotSame(Thread.currentThread(), thread)
        assertEquals("ReePDF viewer", thread.name)
        val expected = listOf(
            "size 0",
            "size 1",
            "size 2",
            "empty",
            "drop",
            "free",
            "empty",
            "drop",
            "free",
            "render 1 2.0 10 20 tile",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun aMemoryRequestStillWaitingWhenTheDocumentClosesIsLeftToTheClose() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        document.trimMemory()
        document.close()
        release.countDown()
        render.await()
        document.awaitClosed()

        assertEquals(listOf("render 0 1.0 0 0 tile", "close"), engine.events)
    }

    @Test
    fun aFailedOpenStopsTheThread() = runBlocking {
        var thread: Thread? = null

        val failure = runCatching {
            PdfViewerDocument.open<String> {
                thread = Thread.currentThread()
                throw IOException("The document needs a password")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        val documentThread = thread!!
        documentThread.join(5000)
        assertFalse(documentThread.isAlive)
    }

    @Test
    fun aCancelledOpenClosesTheEngineItHadOpened() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            PdfViewerDocument.open {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                engine
            }
        }
        started.reached()
        job.cancel()
        release.countDown()
        job.join()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ("close" !in engine.events && System.nanoTime() < deadline) Thread.sleep(5)

        assertTrue(job.isCancelled)
        val thread = engine.threads.single()
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertEquals(listOf("close"), engine.events)
    }

    @Test
    fun theSizesOfEveryPageAreReadInOneGo() = runBlocking {
        val engine = FakeEngine(pageCount = 3)
        val document = open(engine)

        val sizes = document.pageSizes()

        assertArrayEquals(floatArrayOf(100f, 101f, 102f), sizes.widths, 0f)
        assertArrayEquals(floatArrayOf(200f, 201f, 202f), sizes.heights, 0f)
        assertArrayEquals(booleanArrayOf(false, false, false), sizes.unreadable)
        document.shut()
    }

    @Test
    fun theSizeReadingEndsByEmptyingTheStoreDroppingTheLoadedObjectsAndReleasingFreeMemory() = runBlocking {
        val engine = FakeEngine(pageCount = 2)
        val bytes = AtomicLong(100L * 1024 * 1024)
        engine.sizes = {
            bytes.addAndGet(40L * 1024 * 1024)
            PdfViewerEngine.PageSize(100f, 200f)
        }
        val document = open(engine) { bytes.get() }

        document.pageSizes()
        document.render(0, 1f, 0, 0, "a")
        bytes.addAndGet(65L * 1024 * 1024)
        document.render(1, 1f, 0, 0, "b")
        document.shut()

        val expected = listOf(
            "size 0",
            "size 1",
            "empty",
            "drop",
            "free",
            "render 0 1.0 0 0 a",
            "render 1 1.0 0 0 b",
            "empty",
            "drop",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun anUnreadablePageGetsThePreviousSizeOrA4AndIsMarked() = runBlocking {
        val engine = FakeEngine(pageCount = 5)
        engine.sizes = {
            when (it) {
                0 -> throw IllegalStateException("broken page")
                1 -> PdfViewerEngine.PageSize(300f, 400f)
                2 -> PdfViewerEngine.PageSize(0f, 400f)
                3 -> PdfViewerEngine.PageSize(300f, Float.NaN)
                else -> PdfViewerEngine.PageSize(500f, 600f)
            }
        }
        val document = open(engine)

        val sizes = document.pageSizes()

        assertArrayEquals(floatArrayOf(595f, 300f, 300f, 300f, 500f), sizes.widths, 0f)
        assertArrayEquals(floatArrayOf(842f, 400f, 400f, 400f, 600f), sizes.heights, 0f)
        assertArrayEquals(booleanArrayOf(true, false, true, true, false), sizes.unreadable)
        document.shut()
    }

    @Test
    fun cancellingTheSizeReadingStopsAtTheNextPage() = runBlocking {
        val engine = FakeEngine(pageCount = 1000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.sizes = {
            if (it == 10) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            PdfViewerEngine.PageSize(1f, 1f)
        }
        val document = open(engine)

        val job = launch(Dispatchers.Default) { document.pageSizes() }
        started.reached()
        job.cancel()
        release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(11, engine.only("size").size)
        assertTrue(engine.only("drop").isEmpty())
        assertTrue(engine.only("free").isEmpty())
        assertEquals(PdfRenderResult.Done, document.render(0, 1f, 0, 0, "after"))
        document.shut()
    }

    @Test
    fun aFailingPageIsReportedAndTheNextRenderStillWorks() = runBlocking {
        val engine = FakeEngine()
        val error = IllegalStateException("broken page")
        engine.renders = { page, _ -> if (page == 1) throw error }
        val document = open(engine)

        val failed = document.render(1, 1f, 0, 0, "broken")
        val next = document.render(2, 1f, 0, 0, "next")

        assertSame(error, (failed as PdfRenderResult.Failed).error)
        assertEquals(PdfRenderResult.Done, next)
        document.shut()
    }

    @Test
    fun cancellingTheCallerAbortsTheRenderAndReturnsOnlyAfterTheEngineStopped() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        engine.renders = { _, abort ->
            try {
                waitForAbort(abort, started)
            } finally {
                Thread.sleep(50)
                stopped.set(true)
            }
        }
        val document = open(engine)

        val job = launch(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(stopped.get())
        engine.renders = { _, _ -> }
        assertEquals(PdfRenderResult.Done, document.render(1, 1f, 0, 0, "next"))
        document.shut()
    }

    @Test
    fun anAbortedRenderThatReturnsNormallyStillCountsAsAborted() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        engine.renders = { _, abort ->
            val aborted = CountDownLatch(1)
            abort.whileBound(aborted::countDown) {
                started.countDown()
                aborted.await(5, TimeUnit.SECONDS)
            }
        }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        document.close()

        assertEquals(PdfRenderResult.Aborted, render.await())
        document.awaitClosed()
    }

    @Test
    fun aRenderCancelledBeforeItStartsNeverReachesTheEngine() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val document = open(engine)

        val first = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "first") }
        started.reached()
        val second = launch(Dispatchers.Default) { document.render(1, 1f, 0, 0, "second") }
        delay(100.milliseconds)
        second.cancel()
        release.countDown()
        second.join()

        assertEquals(PdfRenderResult.Done, first.await())
        assertEquals(PdfRenderResult.Done, document.render(2, 1f, 0, 0, "third"))
        assertTrue(second.isCancelled)
        assertEquals(listOf("first", "third"), engine.renderedTargets())
        document.shut()
    }

    @Test
    fun closingAbortsTheRunningRenderClosesTheEngineAndStopsTheThread() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        engine.renders = { _, abort -> waitForAbort(abort, started) }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        document.close()

        assertEquals(PdfRenderResult.Aborted, render.await())
        document.awaitClosed()
        assertEquals("close", engine.events.last())
        assertEquals(1, engine.only("close").size)
        val thread = engine.threads.single()
        thread.join(5000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun aRenderWaitingWhenTheDocumentClosesEndsAbortedWithoutReachingTheEngine() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val document = open(engine)

        val first = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "first") }
        started.reached()
        val second = async(Dispatchers.Default) { document.render(1, 1f, 0, 0, "second") }
        delay(100.milliseconds)
        document.close()
        release.countDown()

        assertEquals(PdfRenderResult.Aborted, first.await())
        assertEquals(PdfRenderResult.Aborted, second.await())
        document.awaitClosed()
        assertEquals(listOf("first"), engine.renderedTargets())
    }

    @Test
    fun everyCallAfterClosingFailsAndClosingTwiceClosesOnce() = runBlocking {
        val engine = FakeEngine()
        val document = open(engine)

        document.close()
        document.close()
        document.trimMemory()
        document.awaitClosed()

        assertTrue(document.isClosed)
        assertTrue(runCatching { document.render(0, 1f, 0, 0, "late") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { document.pageSizes() }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("close"), engine.events)
    }

    @Test
    fun aDocumentCountsAsClosedWithoutErrorOnlyAfterItsEngineClosedCleanly() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val document = open(engine)
        val failing = FakeEngine().apply { closeFailure = IOException("close failed") }
        val broken = open(failing)

        val whileOpen = document.isClosedWithoutError
        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        document.close()
        val closing = document.isClosedWithoutError
        release.countDown()
        render.await()
        document.awaitClosed()
        broken.close()
        val failure = runCatching { broken.awaitClosed() }.exceptionOrNull()

        assertFalse(whileOpen)
        assertFalse(closing)
        assertTrue(document.isClosedWithoutError)
        assertTrue(failure is IOException)
        assertTrue(broken.isClosed)
        assertFalse(broken.isClosedWithoutError)
    }

    @Test
    fun theStoreIsEmptiedAfterTooMuchNativeGrowthAndOnRequestAndOnlyTheRequestReleasesFreeMemory() = runBlocking {
        val engine = FakeEngine()
        val bytes = AtomicLong(100L * 1024 * 1024)
        val document = open(engine) { bytes.get() }

        document.render(0, 1f, 0, 0, "a")
        bytes.addAndGet(65L * 1024 * 1024)
        document.render(1, 1f, 0, 0, "b")
        document.render(2, 1f, 0, 0, "c")
        document.trimMemory()
        document.render(0, 1f, 0, 0, "d")
        document.shut()

        val expected = listOf(
            "render 0 1.0 0 0 a",
            "render 1 1.0 0 0 b",
            "empty",
            "drop",
            "render 2 1.0 0 0 c",
            "empty",
            "drop",
            "free",
            "render 0 1.0 0 0 d",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun aFittedRenderReadsTheSizeAndFillsTheBoxByWidthOrByHeight() = runBlocking {
        val engine = FakeEngine(pageCount = 4)
        engine.sizes = {
            when (it) {
                0 -> PdfViewerEngine.PageSize(200f, 100f)
                1 -> PdfViewerEngine.PageSize(100f, 400f)
                2 -> PdfViewerEngine.PageSize(300f, 200f)
                else -> PdfViewerEngine.PageSize(1f, 10_000f)
            }
        }
        val document = open(engine)

        val results = (0 until 4).map { document.fitted(it) }
        document.shut()

        assertEquals(List(4) { PdfRenderResult.Done }, results.map { it.result })
        assertEquals(listOf("100x50", "25x100", "100x66", "1x100"), results.map { it.target })
        val expected = listOf(
            "size 0",
            "render 0 0.5 0 0 100x50",
            "size 1",
            "render 1 0.25 0 0 25x100",
            "size 2",
            "render 2 0.33333334 0 0 100x66",
            "size 3",
            "render 3 1.0 0 0 1x100",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun aFittedRenderWithoutAValidSizeOrTargetFailsWithoutATargetAndTheNextStillWorks() = runBlocking {
        val engine = FakeEngine(pageCount = 3)
        val sizeError = IllegalStateException("broken page")
        val targetError = IllegalStateException("no memory for the target")
        engine.sizes = {
            when (it) {
                0 -> throw sizeError
                1 -> PdfViewerEngine.PageSize(0f, 100f)
                else -> PdfViewerEngine.PageSize(100f, 100f)
            }
        }
        val document = open(engine)

        val broken = document.fitted(0)
        val empty = document.fitted(1)
        val noTarget = document.renderFitted(2, 100, 100) { _, _ -> throw targetError }
        val next = document.fitted(2)
        document.shut()

        assertSame(sizeError, (broken.result as PdfRenderResult.Failed).error)
        assertNull(broken.target)
        assertTrue((empty.result as PdfRenderResult.Failed).error is IllegalStateException)
        assertNull(empty.target)
        assertSame(targetError, (noTarget.result as PdfRenderResult.Failed).error)
        assertNull(noTarget.target)
        assertEquals(PdfRenderResult.Done, next.result)
        assertEquals(listOf("100x100"), engine.renderedTargets())
    }

    @Test
    fun aFailedOrAbortedFittedRenderStillHandsBackTheTargetItCreated() = runBlocking {
        val engine = FakeEngine()
        squarePages(engine)
        val error = IllegalStateException("broken page")
        val started = CountDownLatch(1)
        engine.renders = { page, abort -> if (page == 0) throw error else waitForAbort(abort, started) }
        val document = open(engine)

        val failed = document.fitted(0)
        val running = async(Dispatchers.Default) { document.fitted(1) }
        started.reached()
        document.close()
        val aborted = running.await()
        document.awaitClosed()

        assertSame(error, (failed.result as PdfRenderResult.Failed).error)
        assertEquals("100x100", failed.target)
        assertEquals(PdfRenderResult.Aborted, aborted.result)
        assertEquals("100x100", aborted.target)
    }

    @Test
    fun closingWhileTheSizeIsReadSkipsTheTargetAndTheRender() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.sizes = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            PdfViewerEngine.PageSize(100f, 100f)
        }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.fitted(0) }
        started.reached()
        document.close()
        release.countDown()
        val result = render.await()
        document.awaitClosed()

        assertEquals(PdfRenderResult.Aborted, result.result)
        assertNull(result.target)
        assertEquals(listOf("size 0", "close"), engine.events)
    }

    @Test
    fun aFittedRenderWaitingWhenTheDocumentClosesCreatesNoTarget() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { page, _ ->
            if (page == 0) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val created: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val document = open(engine)

        val first = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "first") }
        started.reached()
        val second = async(Dispatchers.Default) {
            document.renderFitted(1, 100, 100) { width, height -> "${width}x$height".also(created::add) }
        }
        delay(100.milliseconds)
        document.close()
        release.countDown()
        val waiting = second.await()
        document.awaitClosed()

        assertEquals(PdfRenderResult.Aborted, first.await())
        assertEquals(PdfRenderResult.Aborted, waiting.result)
        assertNull(waiting.target)
        assertTrue(created.isEmpty())
        assertEquals(listOf("render 0 1.0 0 0 first", "close"), engine.events)
    }

    @Test
    fun cancellingAFittedRenderAbortsItAndReturnsOnlyAfterTheEngineStopped() = runBlocking {
        val engine = FakeEngine()
        squarePages(engine)
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        engine.renders = { _, abort ->
            try {
                waitForAbort(abort, started)
            } finally {
                Thread.sleep(50)
                stopped.set(true)
            }
        }
        val document = open(engine)

        val job = launch(Dispatchers.Default) { document.fitted(0) }
        started.reached()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(stopped.get())
        engine.renders = { _, _ -> }
        assertEquals(PdfRenderResult.Done, document.fitted(1).result)
        document.shut()
    }

    @Test
    fun fittedRendersCountForTheStoreRule() = runBlocking {
        val engine = FakeEngine()
        squarePages(engine)
        val bytes = AtomicLong(100L * 1024 * 1024)
        val document = open(engine) { bytes.get() }

        document.fitted(0)
        bytes.addAndGet(65L * 1024 * 1024)
        document.fitted(1)
        document.shut()

        val expected = listOf(
            "size 0",
            "render 0 1.0 0 0 100x100",
            "size 1",
            "render 1 1.0 0 0 100x100",
            "empty",
            "drop",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun aFittedRenderNeedsAPageOfTheDocumentAndANonEmptyBox() = runBlocking {
        val engine = FakeEngine(pageCount = 2)
        val document = open(engine)

        val outside = runCatching { document.fitted(2) }.exceptionOrNull()
        val noWidth = runCatching { document.fitted(0, maxWidth = 0) }.exceptionOrNull()
        val noHeight = runCatching { document.fitted(0, maxHeight = -1) }.exceptionOrNull()
        document.shut()

        assertTrue(outside is IllegalArgumentException)
        assertTrue(noWidth is IllegalArgumentException)
        assertTrue(noHeight is IllegalArgumentException)
        assertEquals(listOf("close"), engine.events)
    }

    @Test
    fun textCallsRunOnTheDocumentThreadInTurnWithRendersAndHandBackTheEngineResults() = runBlocking {
        val engine = FakeEngine()
        val document = open(engine)

        document.render(0, 1f, 0, 0, "tile")
        val hits = document.search(1, "word")
        val counted = document.count(0, "word")
        val word = document.word(2, PdfTextPoint(3f, 4f), 5f)
        val caret = document.caret(1, PdfTextPoint(6f, 7f))
        val rects = document.highlight(0, null, PdfTextPoint(9f, 9f))
        val copied = document.copy(1, PdfTextPoint(1f, 1f), null)
        val text = document.text(2)
        document.shut()

        assertEquals(1, hits.count)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), hits.rects, 0f)
        assertEquals(2, counted)
        assertEquals(3f, word!!.from.x, 0f)
        assertEquals(4f, word.to.x, 0f)
        assertEquals(8f, caret!!.x, 0f)
        assertEquals(7f, caret.y, 0f)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f), rects, 0f)
        assertEquals("text 1", copied)
        assertEquals("text 2", text)
        assertEquals(1, engine.threads.size)
        assertNotSame(Thread.currentThread(), engine.threads.single())
        val expected = listOf(
            "render 0 1.0 0 0 tile",
            "search 1 word",
            "count 0 word",
            "word 2 3.0 4.0 5.0",
            "caret 1 6.0 7.0",
            "highlight 0 null 9.0",
            "copy 1 1.0 null",
            "text 2",
            "close"
        )
        assertEquals(expected, engine.events)
    }

    @Test
    fun aTextCallWaitingWhenTheDocumentClosesFailsWithoutReachingTheEngine() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        val search = async(Dispatchers.Default) { runCatching { document.search(1, "late") } }
        delay(100.milliseconds)
        document.close()
        release.countDown()
        render.await()
        val failure = search.await().exceptionOrNull()
        document.awaitClosed()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("render 0 1.0 0 0 tile", "close"), engine.events)
        assertTrue(runCatching { document.copy(0, null, null) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun aTextCallCancelledBeforeItStartsNeverReachesTheEngine() = runBlocking {
        val engine = FakeEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.renders = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val document = open(engine)

        val render = async(Dispatchers.Default) { document.render(0, 1f, 0, 0, "tile") }
        started.reached()
        val search = launch(Dispatchers.Default) { document.search(1, "cancelled") }
        delay(100.milliseconds)
        search.cancel()
        release.countDown()
        search.join()
        render.await()
        val next = document.search(2, "next")
        document.shut()

        assertTrue(search.isCancelled)
        assertEquals(1, next.count)
        assertEquals(listOf("render 0 1.0 0 0 tile", "search 2 next", "close"), engine.events)
    }

    @Test
    fun aFailingTextCallReportsTheErrorAndTheNextOneStillWorks() = runBlocking {
        val engine = FakeEngine()
        val error = IllegalStateException("broken page")
        engine.texts = { if (it == 1) throw error else "text $it" }
        val document = open(engine)

        val failed = runCatching { document.copy(1, null, null) }.exceptionOrNull()
        val next = document.copy(2, null, null)
        document.shut()

        assertTrue(failed is IllegalStateException)
        assertEquals(error.message, failed?.message)
        assertEquals("text 2", next)
    }

    @Test
    fun textCallsCountForTheStoreRule() = runBlocking {
        val engine = FakeEngine()
        val bytes = AtomicLong(100L * 1024 * 1024)
        val document = open(engine) { bytes.get() }

        document.search(0, "a")
        bytes.addAndGet(65L * 1024 * 1024)
        document.text(1)
        document.text(2)
        document.shut()

        assertEquals(listOf("search 0 a", "text 1", "empty", "drop", "text 2", "close"), engine.events)
    }

    @Test
    fun aTextCallNeedsAPageOfTheDocument() = runBlocking {
        val engine = FakeEngine(pageCount = 2)
        val document = open(engine)

        val below = runCatching { document.search(-1, "a") }.exceptionOrNull()
        val above = runCatching { document.text(2) }.exceptionOrNull()
        document.shut()

        assertTrue(below is IllegalArgumentException)
        assertTrue(above is IllegalArgumentException)
        assertEquals(listOf("close"), engine.events)
    }

    @Test
    fun aSinglePageSizeIsReadWithoutTheOtherPages() = runBlocking {
        val engine = FakeEngine(pageCount = 30_000)
        val document = open(engine)

        val size = document.pageSize(14_999)
        document.shut()

        assertEquals(15_099f, size?.width)
        assertEquals(15_199f, size?.height)
        assertEquals(listOf("size 14999", "close"), engine.events)
    }

    @Test
    fun anUnreadableSinglePageHasNoSize() = runBlocking {
        val engine = FakeEngine()
        engine.sizes = { page ->
            when (page) {
                0 -> throw IllegalStateException("broken page")
                1 -> PdfViewerEngine.PageSize(0f, 200f)
                else -> PdfViewerEngine.PageSize(Float.NaN, 200f)
            }
        }
        val document = open(engine)

        val sizes = (0 until 3).map { document.pageSize(it) }
        document.shut()

        assertEquals(listOf(null, null, null), sizes)
    }

    @Test
    fun aSinglePageSizeNeedsAPageOfTheDocument() = runBlocking {
        val engine = FakeEngine(pageCount = 2)
        val document = open(engine)

        val below = runCatching { document.pageSize(-1) }.exceptionOrNull()
        val above = runCatching { document.pageSize(2) }.exceptionOrNull()
        document.shut()

        assertTrue(below is IllegalArgumentException)
        assertTrue(above is IllegalArgumentException)
        assertEquals(listOf("close"), engine.events)
    }

    @Test
    fun searchHitsCountTheirHitsAndRejectStartsThatDoNotMatchTheRectangles() {
        val hits = PdfTextHits(FloatArray(12), intArrayOf(0, 2, 3))

        assertEquals(2, hits.count)
        assertEquals(0, PdfTextHits.NONE.count)
        assertTrue(runCatching { PdfTextHits(FloatArray(8), intArrayOf(0, 1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PdfTextHits(FloatArray(4), intArrayOf(1, 1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PdfTextHits(FloatArray(0), IntArray(0)) }.exceptionOrNull() is IllegalArgumentException)
    }

    private companion object {
        const val DRAWING_MS = 30L
    }
}
