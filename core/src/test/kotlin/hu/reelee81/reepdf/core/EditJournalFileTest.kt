package hu.reelee81.reepdf.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditJournalFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var target: File

    private fun journalTest(block: suspend TestScope.(EditJournalFile) -> Unit) = runTest {
        target = File(temp.newFolder(), "edits.journal")
        val job = Job()
        try {
            block(EditJournalFile(CoroutineScope(coroutineContext + job)) { target })
        } finally {
            job.cancel()
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun theFirstRecordAfterABeginStartsTheFileWithTheHeaderAndLaterOnesAreAppended() = journalTest { journal ->
        journal.begin(bytes(9, 9))
        journal.append { bytes(1) }
        journal.append { bytes(2, 3) }
        journal.awaitIdle()

        assertArrayEquals(bytes(9, 9, 1, 2, 3), target.readBytes())
    }

    @Test
    fun nothingIsWrittenBeforeABegin() = journalTest { journal ->
        journal.append { bytes(1) }
        journal.rewrite { sequenceOf(bytes(2)) }
        journal.awaitIdle()

        assertFalse(target.exists())
    }

    @Test
    fun aNewBeginKeepsTheOldFileUntilTheNextRecordReplacesIt() = journalTest { journal ->
        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.begin(bytes(8))
        journal.awaitIdle()

        assertArrayEquals(bytes(9, 1), target.readBytes())
        journal.append { bytes(2) }
        journal.awaitIdle()
        assertArrayEquals(bytes(8, 2), target.readBytes())
    }

    @Test
    fun aRewriteReplacesTheWholeFileAndLaterRecordsAreAppended() = journalTest { journal ->
        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.append { bytes(2) }
        journal.rewrite { sequenceOf(bytes(5), bytes(6)) }
        journal.append { bytes(7) }
        journal.awaitIdle()

        assertArrayEquals(bytes(9, 5, 6, 7), target.readBytes())
        assertEquals(listOf(target.name), target.parentFile.list().orEmpty().toList())
    }

    @Test
    fun aDiscardDeletesTheFileAndTheNextRecordStartsAgain() = journalTest { journal ->
        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.discard()
        journal.awaitIdle()

        assertFalse(target.exists())
        journal.append { bytes(2) }
        journal.awaitIdle()
        assertArrayEquals(bytes(9, 2), target.readBytes())
    }

    @Test
    fun aReadWaitsForTheEarlierWritesAndReturnsNullWithoutAFile() = journalTest { journal ->
        assertNull(journal.read { it.readBytes() })

        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.append { bytes(2) }

        assertArrayEquals(bytes(9, 1, 2), journal.read { it.readBytes() })
    }

    @Test
    fun aFailedRecordDeletesTheFileAndStopsAppendingUntilARewrite() = journalTest { journal ->
        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.append { throw IllegalStateException("broken") }
        journal.append { bytes(3) }
        journal.awaitIdle()

        assertFalse(target.exists())
        journal.rewrite { sequenceOf(bytes(4)) }
        journal.append { bytes(5) }
        journal.awaitIdle()
        assertArrayEquals(bytes(9, 4, 5), target.readBytes())
    }

    @Test
    fun aFailedRewriteLeavesNoTemporaryFileBehind() = journalTest { journal ->
        journal.begin(bytes(9))
        journal.append { bytes(1) }
        journal.rewrite { sequence { yield(bytes(2)); throw IllegalStateException("broken") } }
        journal.awaitIdle()

        assertTrue(target.parentFile.list().orEmpty().isEmpty())
    }
}
