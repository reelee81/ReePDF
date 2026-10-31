package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException
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
    fun aTargetThatCannotBeReplacedFails() {
        val dir = temp.newFolder()
        val work = File(dir, "nup.pdf.part").apply { writeText("new") }
        val target = File(dir, "nup.pdf").apply { mkdirs() }
        val locked = File(target, "inner").apply { mkdirs() }
        File(locked, "part.pdf").writeText("x")
        assumeTrue(locked.setWritable(false))

        try {
            assertThrows(IOException::class.java) { WorkFiles.replace(work, target) }
        } finally {
            locked.setWritable(true)
        }

        assertEquals("new", work.readText())
    }
}
