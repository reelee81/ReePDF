package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.EmptyPage
import hu.reelee81.reepdf.core.SourcePartPlan.Piece.Incoming
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfSourcePartsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var work: File

    @Before
    fun setUp() {
        dir = File(temp.root, "source_temp")
        work = temp.newFolder("work")
    }

    @Test
    fun importsFillThePartsAndLeaveFullPartsUntouched() {
        for (batchSize in listOf(1, 2, 20)) {
            dir.deleteRecursively()
            val first = imported(listOf(pdf("a.pdf", 101..107)), batchSize)
            assertEquals(listOf("0001.pdf", "0002.pdf", "0003.pdf"), names())
            assertEquals(listOf(listOf(101, 102, 103), listOf(104, 105, 106), listOf(107)), partWidths(first))

            val untouched = File(dir, "0001.pdf").readBytes()
            val second = append(first, listOf(pdf("b.pdf", 201..202)), batchSize)

            assertEquals(listOf(listOf(101, 102, 103), listOf(104, 105, 106), listOf(107, 201, 202)), partWidths(second))
            assertArrayEquals(untouched, File(dir, "0001.pdf").readBytes())
            assertEquals(second.layout.pageCounts, PdfSourceParts.open(dir).layout.pageCounts)
            assertNoWorkFiles()
        }
    }

    @Test
    fun aDeletionRewritesOnlyItsPartsAndDropsEmptyOnes() {
        val parts = imported(listOf(pdf("a.pdf", 101..108)))
        val untouched = File(dir, "0001.pdf").readBytes()

        val after = parts.write(SourcePartPlan.removePages(parts.layout, setOf(4, 6, 7)), 2, NoProgress)

        assertEquals(listOf(listOf(101, 102, 103), listOf(104, 106)), partWidths(after))
        assertEquals(listOf("0001.pdf", "0002.pdf"), names())
        assertArrayEquals(untouched, File(dir, "0001.pdf").readBytes())
    }

    @Test
    fun aReorderMovesThePagesAcrossParts() {
        val parts = imported(listOf(pdf("a.pdf", 101..108)))

        val after = parts.write(SourcePartPlan.reorder(parts.layout, intArrayOf(0, 1, 5, 2, 3, 4, 6, 7)), 2, NoProgress)

        assertEquals(listOf(listOf(101, 102, 106), listOf(103, 104, 105), listOf(107, 108)), partWidths(after))
    }

    @Test
    fun aReplacedGroupAndAnEmptyPageLandWhereThePlanSays() {
        val parts = imported(listOf(pdf("a.pdf", 101..108)))
        val signed = pdf("signed.pdf", 900..900)

        val replaced = parts.write(
            SourcePartPlan.replaceGroups(parts.layout, perGroup = 2, groups = setOf(1)),
            2,
            NoProgress,
            PdfSourceParts.Inputs(replacements = mapOf(1 to signed))
        )
        assertEquals(listOf(listOf(101, 102, 900), listOf(105, 106), listOf(107, 108)), partWidths(replaced))

        val withEmpty = replaced.write(
            SourcePartPlan.append(replaced.layout, listOf(EmptyPage), partPages = PART_PAGES),
            2,
            NoProgress,
            PdfSourceParts.Inputs(emptyPage = PageSize(777f, 500f))
        )
        assertEquals(listOf(listOf(101, 102, 900), listOf(105, 106), listOf(107, 108, 777)), partWidths(withEmpty))
    }

    @Test
    fun aBatchReadsAcrossPartsAndReleasesEveryFile() {
        val descriptors = File("/proc/self/fd")
        assumeTrue("No /proc/self/fd on this system", descriptors.isDirectory)
        val parts = imported(listOf(pdf("a.pdf", 101..108)))

        val before = descriptors.list()?.size ?: 0
        val widths = parts.batch { batch -> (2..6).map { batch.page(it).mediaBox.width.toInt() } }
        val after = descriptors.list()?.size ?: 0

        assertEquals(listOf(103, 104, 105, 106, 107), widths)
        assertTrue("Open files before $before, after $after", after <= before)
        val location = parts.locate(4)
        assertEquals("0002.pdf", location.file.name)
        assertEquals(1, location.page)
    }

    @Test
    fun aFailedWriteLeavesTheOldPartsAndNoWorkFiles() {
        val parts = imported(listOf(pdf("a.pdf", 101..108)))
        val before = names().associateWith { File(dir, it).readBytes() }

        assertThrows(IllegalStateException::class.java) {
            parts.write(SourcePartPlan.replaceGroups(parts.layout, perGroup = 2, groups = setOf(3)), 2, NoProgress)
        }

        assertEquals(before.keys.toList(), names())
        for ((name, bytes) in before) assertArrayEquals(name, bytes, File(dir, name).readBytes())
        assertNoWorkFiles()
    }

    @Test
    fun aPlanOutOfOrderIsRejected() {
        val parts = imported(listOf(pdf("a.pdf", 101..108)))

        assertThrows(IllegalArgumentException::class.java) {
            parts.write(SourcePartPlan(listOf(SourcePartPlan.Part.Keep(1), SourcePartPlan.Part.Keep(0))), 2, NoProgress)
        }
    }

    @Test
    fun aSmallBudgetSplitsTheBatchesButWritesTheSameSource() {
        val results = listOf(0L, Long.MAX_VALUE).map { budget ->
            dir.deleteRecursively()
            val parts = imported(listOf(pdf("a.pdf", 101..107)), 20, budget)
            val imported = partWidths(parts)
            val ranges = ArrayList<IntRange>()
            val after = parts.write(
                SourcePartPlan.removePages(parts.layout, setOf(0)),
                20,
                NoProgress,
                onCopied = { first, last, _ -> ranges += first..last },
                budget = budget
            )
            Triple(imported, partWidths(after), ranges)
        }

        assertEquals(results[1].first, results[0].first)
        assertEquals(listOf(listOf(102, 103), listOf(104, 105, 106), listOf(107)), results[0].second)
        assertEquals(results[1].second, results[0].second)
        assertEquals(listOf(1..1, 2..2), results[0].third)
        assertEquals(listOf(1..2), results[1].third)
    }

    @Test
    fun aMissingFolderIsAnEmptySource() {
        assertFalse(PdfSourceParts.exists(dir))
        assertEquals(0, PdfSourceParts.open(dir).total)
    }

    private fun imported(inputs: List<File>, batchSize: Int = 2, budget: Long = Long.MAX_VALUE): PdfSourceParts =
        append(PdfSourceParts.open(dir), inputs, batchSize, budget)

    private fun append(parts: PdfSourceParts, inputs: List<File>, batchSize: Int, budget: Long = Long.MAX_VALUE): PdfSourceParts {
        val pieces = inputs.mapIndexed { index, file -> Incoming(index, 0, pageCount(file) - 1) }
        return parts.write(
            SourcePartPlan.append(parts.layout, pieces, partPages = PART_PAGES),
            batchSize,
            NoProgress,
            PdfSourceParts.Inputs(incoming = inputs.map { PdfSourceParts.Incoming(it, null) }, copierDir = work),
            budget = budget
        )
    }

    private fun partWidths(parts: PdfSourceParts): List<List<Int>> = (0 until parts.layout.parts).map { part ->
        PdfDocument(PdfIo.reader(parts.file(part).absolutePath)).use { pdf ->
            (1..pdf.numberOfPages).map { pdf.getPage(it).mediaBox.width.toInt() }
        }
    }

    private fun names(): List<String> = dir.list().orEmpty().sorted()

    private fun assertNoWorkFiles() {
        assertTrue(names().toString(), names().none { it.endsWith(".work") })
    }

    private fun pageCount(file: File): Int = PdfDocument(PdfIo.reader(file.absolutePath)).use { it.numberOfPages }

    private fun pdf(name: String, widths: IntRange): File {
        val file = File(work, name)
        PdfDocument(PdfWriter(file)).use { pdf ->
            for (width in widths) pdf.addNewPage(PageSize(width.toFloat(), 500f))
        }
        return file
    }

    private object NoProgress : PdfProgress {
        override fun advance(units: Int) = Unit
        override fun idle() = Unit
    }

    private companion object {
        const val PART_PAGES = 3
    }
}
