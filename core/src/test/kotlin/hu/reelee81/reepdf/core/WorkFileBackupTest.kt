package hu.reelee81.reepdf.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkFileBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun backupIn(dir: File) = WorkFileBackup(dir, "source.pdf", "nup.pdf", "source_copy.pdf", "nup_copy.pdf")

    @Test
    fun aBackupCopiesBothWorkFilesAndReplacesOlderCopies() {
        val dir = temp.newFolder()
        File(dir, "source.pdf").writeText("source")
        File(dir, "nup.pdf").writeText("nup")
        File(dir, "source_copy.pdf").writeText("an older and longer source copy")

        backupIn(dir).backup()

        assertEquals("source", File(dir, "source_copy.pdf").readText())
        assertEquals("nup", File(dir, "nup_copy.pdf").readText())
    }

    @Test
    fun aMissingWorkFileGetsNoCopy() {
        val dir = temp.newFolder()
        File(dir, "source.pdf").writeText("source")

        backupIn(dir).backup()

        assertTrue(File(dir, "source_copy.pdf").exists())
        assertFalse(File(dir, "nup_copy.pdf").exists())
    }

    @Test
    fun aRestoreBringsBackBothFilesAndClearsEverythingElse() {
        val dir = temp.newFolder()
        File(dir, "source.pdf").writeText("source")
        File(dir, "nup.pdf").writeText("nup")
        val backup = backupIn(dir)
        backup.backup()
        File(dir, "source.pdf").writeText("half written")
        File(dir, "nup.pdf").delete()
        File(dir, "source_temp_work.pdf").writeText("work")

        assertTrue(backup.restore())

        assertEquals(setOf("source.pdf", "nup.pdf"), dir.list()!!.toSet())
        assertEquals("source", File(dir, "source.pdf").readText())
        assertEquals("nup", File(dir, "nup.pdf").readText())
    }

    @Test
    fun aRestoreWithoutALayoutCopyReportsItAndKeepsOnlyTheSource() {
        val dir = temp.newFolder()
        File(dir, "source.pdf").writeText("source")
        val backup = backupIn(dir)
        backup.backup()
        File(dir, "nup.pdf").writeText("new layout")

        assertFalse(backup.restore())

        assertEquals(setOf("source.pdf"), dir.list()!!.toSet())
        assertEquals("source", File(dir, "source.pdf").readText())
    }

    @Test
    fun aSourceFolderIsBackedUpAndRestoredWithAllItsParts() {
        val dir = temp.newFolder()
        val source = File(dir, "source").apply { mkdirs() }
        File(source, "0001.pdf").writeText("first")
        File(source, "0002.pdf").writeText("second")
        File(dir, "nup.pdf").writeText("nup")
        File(dir, "source_copy").apply { mkdirs() }.resolve("0009.pdf").writeText("an older copy")
        val backup = WorkFileBackup(dir, "source", "nup.pdf", "source_copy", "nup_copy.pdf")

        backup.backup()
        assertEquals(setOf("0001.pdf", "0002.pdf"), File(dir, "source_copy").list()!!.toSet())

        File(source, "0002.pdf").writeText("half written")
        File(source, "0003.pdf").writeText("new part")
        File(source, "0003.pdf.work").writeText("work")
        File(dir, "other").apply { mkdirs() }.resolve("kept.tmp").writeText("other")

        assertTrue(backup.restore())

        assertEquals(setOf("source", "nup.pdf", "other"), dir.list()!!.toSet())
        assertEquals(setOf("0001.pdf", "0002.pdf"), source.list()!!.toSet())
        assertEquals("first", File(source, "0001.pdf").readText())
        assertEquals("second", File(source, "0002.pdf").readText())
        assertEquals("nup", File(dir, "nup.pdf").readText())
    }

    @Test
    fun aRestoreWithoutCopiesEmptiesTheFolder() {
        val dir = temp.newFolder()
        File(dir, "source.pdf").writeText("source")
        File(dir, "other.tmp").writeText("other")

        assertFalse(backupIn(dir).restore())

        assertEquals(emptySet<String>(), dir.list()!!.toSet())
    }
}
