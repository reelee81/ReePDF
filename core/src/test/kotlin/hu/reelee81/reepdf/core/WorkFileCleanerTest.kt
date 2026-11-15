package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkFileCleanerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun cleanerTest(block: suspend TestScope.(WorkFileCleaner) -> Unit) = runTest {
        val job = Job()
        try {
            block(WorkFileCleaner(CoroutineScope(coroutineContext + job)))
        } finally {
            job.cancel()
        }
    }

    private fun workDir(): File {
        val dir = temp.newFolder()
        File(dir, "nup_temp.pdf").writeText("nup")
        File(dir, "source_temp").mkdirs()
        File(dir, "source_temp/0001.pdf").writeText("part")
        File(dir, "source_temp/0002.pdf").writeText("part")
        return dir
    }

    private fun names(dir: File): List<String> = dir.list().orEmpty().sorted()

    @Test
    fun aClearDeletesEveryEntryButTheKeptOnes() = cleanerTest { cleaner ->
        val dir = workDir()
        File(dir, "20261115_in_1").writeText("incoming")

        cleaner.clear({ dir }, keep = { it.name == "20261115_in_1" })
        cleaner.awaitIdle()

        assertTrue(dir.isDirectory)
        assertEquals(listOf("20261115_in_1"), names(dir))
    }

    @Test
    fun aDeleteRemovesOnlyTheNamedEntries() = cleanerTest { cleaner ->
        val dir = workDir()
        File(dir, "source_temp_copy").mkdirs()
        File(dir, "source_temp_copy/0001.pdf").writeText("copy")
        File(dir, "nup_temp_copy.pdf").writeText("copy")

        cleaner.delete({ dir }, listOf("source_temp_copy", "nup_temp_copy.pdf", "missing.pdf"))
        cleaner.awaitIdle()

        assertEquals(listOf("nup_temp.pdf", "source_temp"), names(dir))
        assertEquals(listOf("0001.pdf", "0002.pdf"), names(File(dir, "source_temp")))
    }

    @Test
    fun theTasksRunInTheOrderTheyWereSubmitted() = cleanerTest { cleaner ->
        val dir = workDir()
        val order = mutableListOf<String>()

        cleaner.delete({ order += "first"; dir }, listOf("nup_temp.pdf"))
        cleaner.clear({ order += "second"; dir })
        cleaner.delete({ order += "third"; dir }, listOf("source_temp"))
        cleaner.awaitIdle()

        assertEquals(listOf("first", "second", "third"), order)
        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun awaitIdleWaitsForAWaitingClearAndEveryTaskBeforeIt() = cleanerTest { cleaner ->
        val dir = workDir()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()

        cleaner.clear({ dir }, ready = { entered.complete(Unit); gate.await() })
        entered.await()
        val waiter = async { cleaner.awaitIdle() }
        yield()

        assertFalse(waiter.isCompleted)
        assertEquals(listOf("nup_temp.pdf", "source_temp"), names(dir))

        gate.complete(true)
        waiter.await()

        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun aTaskSubmittedWhileAClearWaitsRunsOnlyAfterIt() = cleanerTest { cleaner ->
        val dir = workDir()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val order = mutableListOf<String>()

        cleaner.clear({ order += "clear"; dir }, ready = { entered.complete(Unit); gate.await() })
        entered.await()
        cleaner.delete({ order += "delete"; dir }, listOf("nup_temp.pdf"))
        yield()

        assertEquals(emptyList<String>(), order)

        gate.complete(true)
        cleaner.awaitIdle()

        assertEquals(listOf("clear", "delete"), order)
    }

    @Test
    fun aClearThatIsNotReadyDeletesNothing() = cleanerTest { cleaner ->
        val dir = workDir()

        cleaner.clear({ dir }, ready = { false })
        cleaner.awaitIdle()

        assertEquals(listOf("nup_temp.pdf", "source_temp"), names(dir))
    }

    @Test
    fun theFolderAndTheKeptNamesAreReadWhenTheTaskRuns() = cleanerTest { cleaner ->
        val dir = workDir()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val pending = mutableSetOf<String>()
        var looked = false

        cleaner.clear({ looked = true; dir }, keep = { it.name in pending }, ready = { entered.complete(Unit); gate.await() })
        entered.await()
        File(dir, "20261115_in_2").writeText("incoming")
        pending += "20261115_in_2"

        assertFalse(looked)

        gate.complete(true)
        cleaner.awaitIdle()

        assertTrue(looked)
        assertEquals(listOf("20261115_in_2"), names(dir))
    }

    @Test
    fun aFailingTaskDoesNotStopTheQueue() = cleanerTest { cleaner ->
        val dir = workDir()

        cleaner.clear({ throw IOException("no folder") })
        cleaner.clear({ dir }, ready = { throw IllegalStateException("not ready") })
        cleaner.delete({ dir }, listOf("nup_temp.pdf"))
        cleaner.awaitIdle()

        assertEquals(listOf("source_temp"), names(dir))
    }

    @Test
    fun theDoneCallbackRunsAfterEveryClearAndOnlyThen() = cleanerTest { cleaner ->
        val dir = workDir()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val done = mutableListOf<String>()

        cleaner.clear({ dir }, ready = { entered.complete(Unit); gate.await() }, done = { done += "deleted:${names(dir).size}" })
        cleaner.clear({ dir }, ready = { false }, done = { done += "skipped" })
        cleaner.clear({ throw IOException("no folder") }, done = { done += "failed" })
        entered.await()
        yield()

        assertEquals(emptyList<String>(), done)

        gate.complete(true)
        cleaner.awaitIdle()

        assertEquals(listOf("deleted:0", "skipped", "failed"), done)
    }

    @Test
    fun aCancelledWaiterDoesNotStopTheQueue() = cleanerTest { cleaner ->
        val dir = workDir()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()

        cleaner.clear({ dir }, ready = { entered.complete(Unit); gate.await() })
        entered.await()
        val waiter = async { cleaner.awaitIdle() }
        yield()
        waiter.cancel()
        cleaner.delete({ dir }, listOf("missing.pdf"))

        gate.complete(true)
        cleaner.awaitIdle()

        assertTrue(waiter.isCancelled)
        assertEquals(emptyList<String>(), names(dir))
    }
}
