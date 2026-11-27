package hu.reelee81.reepdf.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorSignatureFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aWrittenSignatureIsReadBackFromItsOwnFileInANewFolder() {
        val dir = File(temp.root, "Signatures")
        val files = VectorSignatureFiles(dir)

        files.write(1, "second")

        assertEquals("second", files.read(1))
        assertNull(files.read(0))
        assertEquals(listOf("vector_signature_2.txt"), dir.list()!!.toList())
    }

    @Test
    fun writingAgainReplacesTheTextAndLeavesNoTemporaryFile() {
        val files = VectorSignatureFiles(temp.root)

        files.write(0, "old")
        files.write(0, "new")

        assertEquals("new", files.read(0))
        assertEquals(listOf("vector_signature_1.txt"), temp.root.list()!!.toList())
    }

    @Test
    fun deletingRemovesOnlyItsOwnFileAndAMissingOneIsFine() {
        val files = VectorSignatureFiles(temp.root)
        files.write(0, "first")
        files.write(1, "second")

        files.delete(0)
        files.delete(0)

        assertNull(files.read(0))
        assertEquals("second", files.read(1))
    }

    @Test
    fun aFileOverTheLimitIsNotRead() {
        val files = VectorSignatureFiles(temp.root)
        File(temp.root, "vector_signature_1.txt").writeBytes(ByteArray(VectorSignatureFiles.MAX_BYTES.toInt() + 1))

        assertNull(files.read(0))
    }

    @Test
    fun readingAllGivesEverySlotWithOnlyTheAcceptedSignatures() {
        val files = VectorSignatureFiles(File(temp.root, "Signatures"))
        files.write(1, "good")

        assertEquals(listOf(null, "good"), files.readAll { true })

        files.write(0, "broken")

        assertEquals(listOf(null, "good"), files.readAll { it != "broken" })
        assertEquals(listOf("broken", "good"), files.readAll { true })
    }

    @Test
    fun readingAllFromAMissingFolderGivesEmptySlots() {
        assertEquals(listOf(null, null), VectorSignatureFiles(File(temp.root, "Missing")).readAll { true })
    }

    @Test
    fun onlyTheTwoSlotsExist() {
        val files = VectorSignatureFiles(temp.root)

        for (slot in listOf(-1, VectorSignatureFiles.SLOTS)) {
            assertFalse(runCatching { files.read(slot) }.isSuccess)
            assertFalse(runCatching { files.write(slot, "x") }.isSuccess)
        }
    }
}
