package hu.reelee81.reepdf.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputExportsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun everyPdfGoesExceptTheProtectedOnesFoldersAndOtherFiles() {
        val dir = temp.newFolder()
        File(dir, "nup_temp.pdf").writeText("nup")
        File(dir, "Label.pdf").writeText("old export")
        File(dir, "upper.PDF").writeText("old export")
        File(dir, "notes.txt").writeText("text")
        File(dir, "source_temp").mkdirs()
        File(dir, "folder.pdf").mkdirs()

        OutputExports.removeOld(dir, setOf("nup_temp.pdf", "source_temp"))

        assertEquals(setOf("nup_temp.pdf", "notes.txt", "source_temp", "folder.pdf"), dir.list()!!.toSet())
    }

    @Test
    fun theNewestOnesCanBeKept() {
        val dir = temp.newFolder()
        for ((index, name) in listOf("a.pdf", "b.pdf", "c.pdf").withIndex()) {
            File(dir, name).apply { writeText(name) }.setLastModified(1_000_000L * (index + 1))
        }

        OutputExports.removeOld(dir, emptySet(), keepRecent = 2)

        assertEquals(setOf("b.pdf", "c.pdf"), dir.list()!!.toSet())
    }
}
