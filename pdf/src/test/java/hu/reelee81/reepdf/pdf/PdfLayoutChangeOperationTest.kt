package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfLayoutChangeOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val crop = PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f)
    private val config = NupConfig(1, 2, 0, isLandscape = true, drawFrame = false, scaleIndividually = false, fitIndividually = false)

    @Before
    fun setUp() {
        dir = temp.newFolder()
    }

    @Test
    fun oneGroupIsCroppedAndTheLayoutIsRebuiltAfterTheBackups() {
        writeSource(5)
        nup().writeText("old layout")

        val result = runBlocking { operation(nupIndex = 1).execute {} }!!

        assertEquals(setOf(2, 3), result.crops.keys)
        assertEquals(crop, result.crops[2])
        assertEquals(config, result.config)
        assertEquals(listOf(1), result.selected)
        assertEquals(listOf("selection saved"), events)
        assertEquals(3, pageCount(nup()))
        assertFalse(File(dir, "nup_temp.pdf.part").exists())
        assertEquals(5, pageCount(File(dir, "source_temp_copy/0001.pdf")))
        assertEquals("old layout", File(dir, "nup_temp_copy.pdf").readText())
        assertNull(result.pageSizes)
    }

    @Test
    fun aSingleUpLayoutAlsoBringsThePageSizesForTheThumbnails() {
        writeSource(3)
        val singleUp = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)

        val result = runBlocking { operation(config = singleUp).execute {} }!!

        val sizes = result.pageSizes!!
        assertArrayEquals(FloatArray(3) { PageSize.A4.width }, sizes.widths, 0f)
        assertArrayEquals(FloatArray(3) { PageSize.A4.height }, sizes.heights, 0f)
        assertEquals(3, pageCount(nup()))
    }

    @Test
    fun allPagesCanBeCroppedAndAFullPageClearsTheCrops() {
        writeSource(3)
        val earlier = PdfCropRect(0.2f, 0.2f, 0.8f, 0.8f)

        val all = runBlocking { operation(applyToAll = true, crops = mapOf(0 to earlier, 7 to earlier)).execute {} }!!
        val cleared = runBlocking { operation(applyToAll = true, crop = null, crops = all.crops).execute {} }!!

        assertEquals(mapOf(0 to crop, 1 to crop, 2 to crop), all.crops)
        assertEquals(emptyMap<Int, PdfCropRect>(), cleared.crops)
    }

    @Test
    fun theRotationAndPositionArraysAreSizedToTheSourcePages() {
        writeSource(3)

        val fitting = runBlocking { operation(rotation = intArrayOf(1, 2, 3), position = intArrayOf(9, 1, 5)).execute {} }!!
        val short = runBlocking { operation(rotation = intArrayOf(1), position = intArrayOf(9)).execute {} }!!

        assertArrayEquals(intArrayOf(1, 2, 3), fitting.rotation)
        assertArrayEquals(intArrayOf(9, 1, 5), fitting.position)
        assertArrayEquals(intArrayOf(0, 0, 0), short.rotation)
        assertArrayEquals(intArrayOf(9, 5, 5), short.position)
    }

    @Test
    fun rotatingTurnsTheGroupsAndLeavesTheOtherArraysAlone() {
        writeSource(5)
        val crops = mapOf(0 to crop, 7 to crop)

        val result = runBlocking {
            operation(
                crops = crops,
                rotation = intArrayOf(0, 1, 2, 3, 0),
                position = intArrayOf(9, 1, 5, 5, 5),
                change = PdfLayoutChangeOperation.Change.Rotate(listOf(0, 2))
            ).execute {}
        }!!

        assertArrayEquals(intArrayOf(1, 2, 2, 3, 1), result.rotation)
        assertArrayEquals(intArrayOf(9, 1, 5, 5, 5), result.position)
        assertEquals(crops, result.crops)
        assertEquals(3, pageCount(nup()))
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun aRotationOfTheWrongSizeStartsFromUnrotatedPages() {
        writeSource(5)

        val result = runBlocking {
            operation(rotation = intArrayOf(0, 3), change = PdfLayoutChangeOperation.Change.Rotate(listOf(1))).execute {}
        }!!

        assertArrayEquals(intArrayOf(0, 0, 1, 1, 0), result.rotation)
    }

    @Test
    fun positioningSetsTheGroupsAndFillsMissingPagesWithTheDefault() {
        writeSource(5)
        val crops = mapOf(1 to crop)

        val result = runBlocking {
            operation(
                crops = crops,
                rotation = intArrayOf(1, 1, 1, 1, 1),
                position = intArrayOf(9),
                change = PdfLayoutChangeOperation.Change.Position(listOf(1), 3)
            ).execute {}
        }!!

        assertArrayEquals(intArrayOf(9, 5, 3, 3, 5), result.position)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1), result.rotation)
        assertEquals(crops, result.crops)
        assertEquals(3, pageCount(nup()))
    }

    @Test
    fun withoutSourcePagesOnlyTheSelectionIsSaved() {
        val result = runBlocking { operation().execute {} }

        assertNull(result)
        assertEquals(listOf("selection saved"), events)
        assertFalse(nup().exists())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
    }

    @Test
    fun theWorkFilesAreRestoredOnlyAfterTheyWereBackedUp() {
        writeSource(3)
        nup().writeText("old layout")
        val operation = operation()

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(3, pageCount(source()))
        assertEquals("old layout", nup().readText())

        runBlocking { operation.execute {} }
        assertEquals(2, pageCount(nup()))

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(3, pageCount(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
        assertTrue(dir.listFiles().orEmpty().all { it == sourceDir() || it == nup() })
    }

    @Test
    fun aFailedRebuildThrowsLeavesTheInputsAloneAndCanBeRestored() {
        writeSource(3)
        nup().writeText("old layout")
        File(dir, "nup_temp.pdf.part").apply { mkdirs() }.resolve("blocker").writeText("x")
        val rotation = intArrayOf(1, 2, 3)
        val position = intArrayOf(4, 5, 6)
        val operation = operation(
            rotation = rotation,
            position = position,
            change = PdfLayoutChangeOperation.Change.Rotate(listOf(0))
        )

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertNotNull(failure)
        assertArrayEquals(intArrayOf(1, 2, 3), rotation)
        assertArrayEquals(intArrayOf(4, 5, 6), position)
        assertEquals("old layout", nup().readText())

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(3, pageCount(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
    }

    @Test
    fun aRebuildUsesTheNewLayoutAndKeepsTheSelectedSourcePages() {
        writeSource(5)
        val singleUp = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
        val crops = mapOf(1 to crop, 7 to crop)

        val result = runBlocking {
            operation(
                config = singleUp,
                crops = crops,
                rotation = intArrayOf(1),
                position = intArrayOf(9),
                change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 2)
            ).execute {}
        }!!

        assertEquals(setOf(2, 3), result.selected.toSet())
        assertEquals(singleUp, result.config)
        assertEquals(crops, result.crops)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), result.rotation)
        assertArrayEquals(intArrayOf(9, 5, 5, 5, 5), result.position)
        assertEquals(5, pageCount(nup()))
        assertNotNull(result.pageSizes)
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun aRebuildToMoreCellsMergesTheSelectionAndAnEmptySelectionStaysEmpty() {
        writeSource(5)

        val merged = runBlocking {
            operation(selected = listOf(0, 1, 2), change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 1)).execute {}
        }!!
        val none = runBlocking {
            operation(selected = emptyList(), change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 1)).execute {}
        }!!

        assertEquals(setOf(0, 1), merged.selected.toSet())
        assertEquals(emptyList<Int>(), none.selected)
        assertEquals(3, pageCount(nup()))
    }

    @Test
    fun aRebuildForAProfileSetsEveryPageToTheNewDefaultPosition() {
        writeSource(3)

        val reset = runBlocking {
            operation(
                position = intArrayOf(5, 1),
                defaultPosition = 7,
                change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 2, resetPositions = true)
            ).execute {}
        }!!
        val kept = runBlocking {
            operation(position = intArrayOf(5, 1, 5), change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 2)).execute {}
        }!!

        assertArrayEquals(intArrayOf(7, 7, 7), reset.position)
        assertEquals(7, reset.defaultPosition)
        assertArrayEquals(intArrayOf(5, 1, 5), kept.position)
        assertEquals(5, kept.defaultPosition)
    }

    @Test
    fun aRebuildFromAnUnreadableSourceFailsAndLeavesTheLayoutAlone() {
        sourceDir().mkdirs()
        source().writeText("not a pdf")
        nup().writeText("old layout")
        val operation = operation(change = PdfLayoutChangeOperation.Change.Rebuild(oldPerGroup = 2))

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun deletingGroupsRemovesTheirPagesAndShiftsTheArrays() {
        writeMarkedSource(5)
        val crops = mapOf(0 to crop, 3 to crop, 4 to crop)

        val result = runBlocking {
            operation(
                crops = crops,
                rotation = intArrayOf(1, 2, 3, 0, 1),
                position = intArrayOf(9, 1, 2, 3, 4),
                selected = listOf(0, 2),
                change = PdfLayoutChangeOperation.Change.DeleteGroups(listOf(1))
            ).execute {}
        }!!

        assertEquals(listOf(200f, 201f, 204f), pageWidths(source()))
        assertArrayEquals(intArrayOf(1, 2, 1), result.rotation)
        assertArrayEquals(intArrayOf(9, 1, 4), result.position)
        assertEquals(mapOf(0 to crop, 2 to crop), result.crops)
        assertEquals(listOf(0, 1), result.selected)
        assertFalse(result.cleared)
        assertEquals(2, pageCount(nup()))
        assertEquals(5, pageCount(File(dir, "source_temp_copy/0001.pdf")))
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun deletingSeveralGroupsDropsTheirSelection() {
        writeMarkedSource(5)

        val result = runBlocking {
            operation(selected = listOf(0, 2), change = PdfLayoutChangeOperation.Change.DeleteGroups(listOf(0, 2))).execute {}
        }!!

        assertEquals(listOf(202f, 203f), pageWidths(source()))
        assertEquals(emptyList<Int>(), result.selected)
        assertEquals(1, pageCount(nup()))
    }

    @Test
    fun deletingEveryPageClearsWithoutBackupOrWriting() {
        writeSource(3)
        nup().writeText("old layout")

        val result = runBlocking {
            operation(crops = mapOf(0 to crop), rotation = intArrayOf(1, 1, 1), change = PdfLayoutChangeOperation.Change.DeleteGroups(listOf(0, 1))).execute {}
        }!!

        assertTrue(result.cleared)
        assertEquals(emptyMap<Int, PdfCropRect>(), result.crops)
        assertEquals(0, result.rotation.size)
        assertEquals(0, result.position.size)
        assertEquals(3, pageCount(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())
        assertEquals(listOf("selection saved"), events)
    }

    @Test
    fun anEmptyPageIsAppendedWithTheSizeOfTheFirstPage() {
        writeMarkedSource(3)
        val singleUp = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
        val crops = mapOf(1 to crop)

        val result = runBlocking {
            operation(
                config = singleUp,
                crops = crops,
                rotation = intArrayOf(1, 2, 3),
                position = intArrayOf(9),
                selected = listOf(1),
                change = PdfLayoutChangeOperation.Change.AddEmptyPage
            ).execute {}
        }!!

        assertEquals(listOf(200f, 201f, 202f, 200f), pageWidths(source()))
        assertArrayEquals(intArrayOf(1, 2, 3, 0), result.rotation)
        assertArrayEquals(intArrayOf(9, 5, 5, 5), result.position)
        assertEquals(crops, result.crops)
        assertEquals(listOf(2), result.selected)
        assertEquals(4, pageCount(nup()))
        assertNotNull(result.pageSizes)
    }

    @Test
    fun aReorderMovesTheGroupsWithTheirArraysForEveryBatchSize() {
        for (batchSize in listOf(1, 2, 10)) {
            writeMarkedSource(5)

            val result = runBlocking {
                operation(
                    crops = mapOf(4 to crop),
                    rotation = intArrayOf(0, 1, 2, 3, 0),
                    position = intArrayOf(1, 2, 3, 4, 6),
                    selected = listOf(0),
                    batchSize = batchSize,
                    change = PdfLayoutChangeOperation.Change.Reorder(listOf(2, 0, 1))
                ).execute {}
            }!!

            assertEquals(listOf(204f, 200f, 201f, 202f, 203f), pageWidths(source()))
            assertArrayEquals(intArrayOf(0, 0, 1, 2, 3), result.rotation)
            assertArrayEquals(intArrayOf(6, 1, 2, 3, 4), result.position)
            assertEquals(mapOf(0 to crop), result.crops)
            assertEquals(listOf(0), result.selected)
            assertEquals(3, pageCount(nup()))
        }
    }

    @Test
    fun aFailedSourceChangeRestoresTheWorkFiles() {
        writeMarkedSource(5)
        nup().writeText("old layout")
        File(dir, "nup_temp.pdf.part").apply { mkdirs() }.resolve("blocker").writeText("x")
        val operation = operation(change = PdfLayoutChangeOperation.Change.Reorder(listOf(2, 0, 1)))

        val failure = runCatching { runBlocking { operation.execute {} } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(listOf(200f, 201f, 202f, 203f, 204f), pageWidths(source()))
        assertEquals("old layout", nup().readText())
        assertFalse(File(dir, "source_temp_copy").exists())
        assertFalse(File(dir, "nup_temp_copy.pdf").exists())

        runBlocking { operation.restoreIfBackedUp() }
        assertEquals(5, pageCount(source()))
        assertEquals("old layout", nup().readText())
    }

    @Test
    fun deletingFromASourceInPartsRewritesOnlyTheTouchedParts() {
        writeMarkedParts(2, 2, 1)
        val untouched = File(sourceDir(), "0001.pdf").readBytes()

        val result = runBlocking {
            operation(
                rotation = intArrayOf(1, 2, 3, 0, 1),
                change = PdfLayoutChangeOperation.Change.DeleteGroups(listOf(1))
            ).execute {}
        }!!

        assertEquals(listOf(listOf(200f, 201f), listOf(204f)), partWidths())
        assertEquals(listOf("0001.pdf", "0003.pdf"), sourceDir().list()!!.sorted())
        assertArrayEquals(untouched, File(sourceDir(), "0001.pdf").readBytes())
        assertArrayEquals(intArrayOf(1, 2, 1), result.rotation)
        assertEquals(2, pageCount(nup()))
    }

    @Test
    fun anEmptyPageOnASourceInPartsGoesIntoTheLastPart() {
        writeMarkedParts(2, 1)
        val untouched = File(sourceDir(), "0001.pdf").readBytes()

        runBlocking { operation(change = PdfLayoutChangeOperation.Change.AddEmptyPage).execute {} }!!

        assertEquals(listOf(listOf(200f, 201f), listOf(202f, 200f)), partWidths())
        assertArrayEquals(untouched, File(sourceDir(), "0001.pdf").readBytes())
    }

    @Test
    fun aReorderOnASourceInPartsRewritesOnlyThePartsWithMovedPages() {
        writeMarkedParts(2, 2, 1)
        val untouched = File(sourceDir(), "0001.pdf").readBytes()

        val result = runBlocking {
            operation(
                rotation = intArrayOf(0, 1, 2, 3, 0),
                change = PdfLayoutChangeOperation.Change.Reorder(listOf(0, 2, 1))
            ).execute {}
        }!!

        assertEquals(listOf(listOf(200f, 201f), listOf(204f, 202f), listOf(203f)), partWidths())
        assertArrayEquals(untouched, File(sourceDir(), "0001.pdf").readBytes())
        assertArrayEquals(intArrayOf(0, 1, 0, 2, 3), result.rotation)
    }

    private fun writeMarkedParts(vararg sizes: Int) {
        sourceDir().mkdirs()
        var index = 0
        for ((part, size) in sizes.withIndex()) {
            PdfDocument(PdfWriter(File(sourceDir(), "000${part + 1}.pdf"))).use { pdf ->
                repeat(size) {
                    val page = pdf.addNewPage(PageSize(200f + index++, 300f))
                    PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
                }
            }
        }
    }

    private fun partWidths(): List<List<Float>> =
        sourceDir().listFiles().orEmpty().sortedBy { it.name }.map { pageWidths(it) }

    private fun sourceDir() = File(dir, "source_temp")

    private fun source() = File(sourceDir(), "0001.pdf")

    private fun nup() = File(dir, "nup_temp.pdf")

    private fun writeSource(pages: Int) {
        sourceDir().mkdirs()
        PdfDocument(PdfWriter(source())).use { pdf ->
            repeat(pages) {
                val page = pdf.addNewPage(PageSize.A4)
                PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
    }

    private fun writeMarkedSource(pages: Int) {
        sourceDir().mkdirs()
        PdfDocument(PdfWriter(source())).use { pdf ->
            repeat(pages) { index ->
                val page = pdf.addNewPage(PageSize(200f + index, 300f))
                PdfCanvas(page).rectangle(10.0, 10.0, 100.0, 100.0).fill().release()
            }
        }
    }

    private fun pageCount(file: File): Int = PdfDocument(PdfReader(file)).use { it.numberOfPages }

    private fun pageWidths(file: File): List<Float> =
        PdfDocument(PdfReader(file)).use { pdf -> (1..pdf.numberOfPages).map { pdf.getPage(it).pageSize.width } }

    private fun operation(
        nupIndex: Int = 0,
        crop: PdfCropRect? = this.crop,
        applyToAll: Boolean = false,
        crops: Map<Int, PdfCropRect> = emptyMap(),
        rotation: IntArray = IntArray(0),
        position: IntArray = IntArray(0),
        config: NupConfig = this.config,
        selected: List<Int> = listOf(1),
        defaultPosition: Int = 5,
        batchSize: Int = 2,
        change: PdfLayoutChangeOperation.Change = PdfLayoutChangeOperation.Change.Crop(nupIndex, crop, applyToAll)
    ) = PdfLayoutChangeOperation(
        files = PdfLayoutChangeOperation.Files(
            dir,
            sourceDir(),
            nup(),
            WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
        ),
        saveSelection = { events += "selection saved" },
        input = PdfLayoutChangeOperation.Input(
            change = change,
            perGroup = 2,
            config = config,
            crops = crops,
            rotation = rotation,
            position = position,
            defaultPosition = defaultPosition,
            batchSize = batchSize,
            selected = selected
        ),
        progress = PdfLayoutChangeOperation.Progress({ 0L }, tickMs = 500L, idleMs = 1_000L)
    )
}
