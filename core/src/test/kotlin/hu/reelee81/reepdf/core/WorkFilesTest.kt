package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theWorkFileReplacesTheTarget() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { writeText("old") }

        WorkFiles.replace(work, target)

        assertEquals("new", target.readText())
        assertFalse(work.exists())
    }

    @Test
    fun theWorkFileReplacesAFolderInTheWay() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { mkdirs() }.also { File(it, "inner.pdf").writeText("x") }

        WorkFiles.replace(work, target)

        assertEquals("new", target.readText())
        assertFalse(work.exists())
    }

    @Test
    fun aMissingWorkFileFailsAndKeepsTheTarget() {
        val dir = temp.newFolder()
        val target = File(dir, "nup.pdf").apply { writeText("old") }

        assertThrows(IOException::class.java) { WorkFiles.replace(File(dir, "nup.pdf.part"), target) }

        assertEquals("old", target.readText())
    }

    @Test
    fun aTargetThatCannotBeMovedAsideFailsAndKeepsBothFiles() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            WorkFiles.replace(work, target) { from, to -> from != work && from != target && from.renameTo(to) }
        }

        assertEquals("old", target.readText())
        assertEquals("new", work.readText())
    }

    @Test
    fun aFailedSecondRenameKeepsTheOldTarget() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            WorkFiles.replace(work, target) { from, to -> from != work && from.renameTo(to) }
        }

        assertEquals("old", target.readText())
        assertEquals("new", work.readText())
        assertEquals(setOf("nup.pdf", "nup.pdf.part"), dir.list()!!.toSet())
    }

    @Test
    fun aTargetFolderThatCannotBeDeletedIsStillReplaced() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { mkdirs() }
        val locked = File(target, "inner").apply { mkdirs() }
        File(locked, "part.pdf").writeText("x")
        assumeTrue(locked.setWritable(false))

        try {
            WorkFiles.replace(work, target)
        } finally {
            File(dir, "nup.pdf.old/inner").setWritable(true)
        }

        assertEquals("new", target.readText())
        assertFalse(work.exists())
    }

    @Test
    fun aCopyWritesTheWholeFileAndChecksForCancellationPerBlock() {
        val dir = temp.newFolder()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val from = File(dir, "nup.pdf").apply { writeBytes(bytes) }
        val to = File(dir, "export.pdf").apply { writeText("an older and longer export that must be overwritten") }
        var checks = 0

        WorkFiles.copy(from, to) { checks++ }

        assertArrayEquals(bytes, to.readBytes())
        assertEquals(5, checks)
    }

    @Test
    fun aCancelledCopyStopsWithTheCancellation() {
        val dir = temp.newFolder()
        val from = File(dir, "nup.pdf").apply { writeBytes(ByteArray(200_000)) }
        val to = File(dir, "export.pdf")
        var checks = 0

        assertThrows(CancellationException::class.java) {
            WorkFiles.copy(from, to) { if (++checks == 2) throw CancellationException("cancelled") }
        }

        assertEquals(64 * 1024L, to.length())
    }
}
