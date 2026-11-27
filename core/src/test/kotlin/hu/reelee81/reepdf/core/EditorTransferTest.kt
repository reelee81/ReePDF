package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorTransferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val record by lazy { File(temp.root, "files/editor_transfer") }
    private val live by lazy { VectorSignatureFiles(File(temp.root, "files/Signatures")) }
    private val profile by lazy { VectorSignatureFiles(File(temp.root, "files/ProfileSignatures")) }
    private val applied = mutableListOf<Pair<EditorTransfer.Target, String?>>()
    private val values = mutableMapOf<String, EditorTransfer.Value>()
    private var failSettings = false

    private fun transfers() = EditorTransfers(record, live, profile) { transfer ->
        if (failSettings) throw IOException("stopped")
        applied += transfer.target to transfer.settings
        values += transfer.values
    }

    private val mainScreen = mapOf(
        "reeprofile1_rows" to EditorTransfer.Value.Text("2"),
        "reeprofile1_file_name_template" to EditorTransfer.Value.Text("Label_ő_ű"),
        "reeprofile1_position" to EditorTransfer.Value.Number(-7),
        "reeprofile1_scale" to EditorTransfer.Value.Flag(true),
        "reeprofile1_fit" to EditorTransfer.Value.Flag(false)
    )

    @Test
    fun aWrittenRecordIsReadBackWithEveryPart() {
        val transfer = EditorTransfer(EditorTransfer.Target.LIVE, "settings ő ű\n", listOf(null, "second\nline"))

        EditorTransferFile.write(record, transfer)
        val read = EditorTransferFile.read(record)!!

        assertEquals(EditorTransfer.Target.LIVE, read.target)
        assertEquals("settings ő ű\n", read.settings)
        assertEquals(listOf(null, "second\nline"), read.signatures)
        assertTrue(read.values.isEmpty())
        assertEquals(listOf("editor_transfer"), record.parentFile!!.list()!!.toList())
    }

    @Test
    fun aProfileRecordKeepsTheMainScreenValuesWithTheirTypesAndOrder() {
        EditorTransferFile.write(record, EditorTransfer(EditorTransfer.Target.PROFILE, null, listOf(null, null), mainScreen))
        val read = EditorTransferFile.read(record)!!

        assertNull(read.settings)
        assertEquals(mainScreen, read.values)
        assertEquals(mainScreen.keys.toList(), read.values.keys.toList())
    }

    @Test
    fun aRecordKeepsItsByteLayout() {
        val live = EditorTransfer(EditorTransfer.Target.LIVE, "ReePDF editor settings 1\npen_color=#00854C\n", listOf("sig", null))
        val profile = EditorTransfer(
            EditorTransfer.Target.PROFILE,
            null,
            listOf(null, null),
            mapOf(
                "a" to EditorTransfer.Value.Text("ő"),
                "b" to EditorTransfer.Value.Number(-2),
                "c" to EditorTransfer.Value.Flag(true)
            )
        )

        EditorTransferFile.write(record, live)
        assertEquals(
            "5245455401010000002b52656550444620656469746f722073657474696e677320310a70656e5f636f6c6f723d233030383534430a" +
                "0200000003736967ffffffff000000005b804ec6",
            record.readBytes().toHexString()
        )
        EditorTransferFile.write(record, profile)
        assertEquals(
            "524545540100ffffffff02ffffffffffffffff0000000300000001610000000002c591000000016201fffffffe00000001630201cd77ee1d",
            record.readBytes().toHexString()
        )
    }

    @Test
    fun aMissingDamagedOrCutRecordIsNotRead() {
        assertNull(EditorTransferFile.read(record))

        EditorTransferFile.write(record, EditorTransfer(EditorTransfer.Target.PROFILE, "settings", listOf("first", "second"), mainScreen))
        val bytes = record.readBytes()
        for (index in bytes.indices) {
            record.writeBytes(bytes.copyOf().also { it[index] = (it[index] + 1).toByte() })
            assertNull("byte $index", EditorTransferFile.read(record))
        }
        for (length in bytes.indices) {
            record.writeBytes(bytes.copyOf(length))
            assertNull("length $length", EditorTransferFile.read(record))
        }
        record.writeBytes(bytes)
        assertNotNull(EditorTransferFile.read(record))
    }

    @Test
    fun savingIntoTheProfileMakesItHoldExactlyTheGivenSignatures() {
        profile.write(0, "old first")
        profile.write(1, "old second")

        transfers().run(EditorTransfer(EditorTransfer.Target.PROFILE, "new settings", listOf("new first", null), mainScreen))

        assertEquals(listOf("new first", null), profile.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.PROFILE to "new settings"), applied)
        assertEquals(mainScreen, values)
        assertFalse(record.exists())
    }

    @Test
    fun aProfileTransferWithoutEditorSettingsLeavesTheProfileSignaturesAlone() {
        profile.write(0, "saved first")

        transfers().run(EditorTransfer(EditorTransfer.Target.PROFILE, null, listOf(null, null), mainScreen))

        assertEquals(listOf("saved first", null), profile.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.PROFILE to null), applied)
        assertEquals(mainScreen, values)
    }

    @Test
    fun loadingIntoTheEditorLeavesASlotAloneWhenTheProfileHasNothingThere() {
        live.write(0, "drawn first")
        live.write(1, "drawn second")

        transfers().run(EditorTransfer(EditorTransfer.Target.LIVE, "saved settings", listOf("saved first", null)))

        assertEquals(listOf("saved first", "drawn second"), live.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.LIVE to "saved settings"), applied)
        assertFalse(record.exists())
    }

    @Test
    fun aTransferStoppedBeforeItsSettingsIsFinishedByTheNextRecovery() {
        profile.write(0, "old first")
        profile.write(1, "old second")
        failSettings = true

        assertTrue(runCatching {
            transfers().run(EditorTransfer(EditorTransfer.Target.PROFILE, "new settings", listOf(null, "new second"), mainScreen))
        }.isFailure)

        assertTrue(record.exists())
        assertTrue(values.isEmpty())
        failSettings = false
        transfers().recover()

        assertEquals(listOf(null, "new second"), profile.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.PROFILE to "new settings"), applied)
        assertEquals(mainScreen, values)
        assertFalse(record.exists())
    }

    @Test
    fun aTransferStoppedBetweenTheTwoSignaturesIsFinishedByTheNextRecovery() {
        live.write(0, "drawn first")
        live.write(1, "drawn second")
        val blocker = File(temp.root, "files/Signatures/vector_signature_2.txt")
        blocker.delete()
        File(blocker, "blocked").mkdirs()

        assertTrue(runCatching {
            transfers().run(EditorTransfer(EditorTransfer.Target.LIVE, "saved settings", listOf("saved first", "saved second")))
        }.isFailure)

        assertEquals("saved first", live.read(0))
        assertTrue(applied.isEmpty())
        assertTrue(record.exists())
        blocker.deleteRecursively()
        transfers().recover()

        assertEquals(listOf("saved first", "saved second"), live.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.LIVE to "saved settings"), applied)
        assertFalse(record.exists())
    }

    @Test
    fun aRecordWrittenJustBeforeAStopIsAppliedByTheNextRecovery() {
        EditorTransferFile.write(record, EditorTransfer(EditorTransfer.Target.PROFILE, "settings", listOf("first", "second")))

        transfers().recover()

        assertEquals(listOf("first", "second"), profile.readAll { true })
        assertEquals(listOf(EditorTransfer.Target.PROFILE to "settings"), applied)
        assertFalse(record.exists())
    }

    @Test
    fun recoveringAgainChangesNothing() {
        transfers().run(EditorTransfer(EditorTransfer.Target.LIVE, "settings", listOf("first", null)))
        applied.clear()
        live.write(1, "drawn later")

        transfers().recover()

        assertEquals(listOf("first", "drawn later"), live.readAll { true })
        assertTrue(applied.isEmpty())
    }

    @Test
    fun aDamagedRecordIsDroppedWithoutTouchingAnything() {
        live.write(0, "drawn first")
        EditorTransferFile.write(record, EditorTransfer(EditorTransfer.Target.LIVE, "settings", listOf("saved", null)))
        record.writeBytes(record.readBytes().copyOf(10))

        transfers().recover()

        assertEquals(listOf("drawn first", null), live.readAll { true })
        assertTrue(applied.isEmpty())
        assertFalse(record.exists())
    }

    @Test
    fun aTransferNeedsEverySlotAndTheEditorTakesOnlyItsSettings() {
        assertTrue(runCatching { EditorTransfer(EditorTransfer.Target.LIVE, "settings", listOf("only one")) }.isFailure)
        assertTrue(runCatching { EditorTransfer(EditorTransfer.Target.LIVE, null, listOf(null, null)) }.isFailure)
        assertTrue(runCatching { EditorTransfer(EditorTransfer.Target.LIVE, "settings", listOf(null, null), mainScreen) }.isFailure)
    }
}
