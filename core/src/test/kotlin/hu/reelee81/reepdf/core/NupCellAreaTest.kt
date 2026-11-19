package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.NupGeometry.A4_HEIGHT_PT
import hu.reelee81.reepdf.core.NupGeometry.A4_WIDTH_PT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class NupCellAreaTest {
    private val a6 = NupBox(0f, 0f, 298f, 420f)
    private val scaled = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)
    private val fitted = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = true)
    private val content = NupBox(60f, 150f, 140f, 170f)

    @Test
    fun aScaledPageCoversExactlyItsPlacedPageInsideItsSlot() {
        for (slot in 0 until 4) {
            val cell = place(scaled, slot)
            val area = NupCellArea.of(a6, cell.matrix, cell.slot())

            assertArea(NupCellArea.mapped(a6, cell.matrix)!!, area)
            assertInside(cell.slot(), area!!)
        }
    }

    @Test
    fun aCroppedPageCoversOnlyItsCroppedPart() {
        val crop = NupBox(74.5f, 105f, 149f, 210f)
        val cell = place(scaled, 0, cropBox = crop)
        val area = NupCellArea.of(crop, cell.matrix, cell.slot())!!

        assertArea(NupCellArea.mapped(crop, cell.matrix)!!, area)
        val page = NupCellArea.mapped(a6, cell.matrix)!!
        assertTrue(page.left < area.left && page.right > area.right && page.bottom < area.bottom && page.top > area.top)
    }

    @Test
    fun aFittedPageReachingOverItsNeighborsIsCutToItsOwnSlot() {
        for (slot in 0 until 4) {
            val cell = place(fitted, slot, contentBox = content)
            val page = NupCellArea.mapped(a6, cell.matrix)!!
            val area = NupCellArea.of(a6, cell.matrix, cell.slot())!!

            assertTrue("slot $slot overflows", page.right - page.left > cell.width || page.top - page.bottom > cell.height)
            assertArea(
                NupArea(
                    maxOf(page.left, cell.x.toDouble()), maxOf(page.bottom, cell.y.toDouble()),
                    minOf(page.right, cell.x.toDouble() + cell.width), minOf(page.top, cell.y.toDouble() + cell.height)
                ),
                area
            )
            assertInside(cell.slot(), area)
        }
    }

    @Test
    fun theCellsOfASheetNeverShareArea() {
        for (config in listOf(scaled, fitted)) {
            val areas = (0 until 4).map { slot ->
                val cell = place(config, slot, contentBox = content)
                NupCellArea.of(a6, cell.matrix, cell.slot())!!
            }
            for (i in areas.indices) {
                for (j in i + 1 until areas.size) {
                    val other = areas[j]
                    assertFalse("$i and $j overlap", areas[i].intersects(other.left, other.bottom, other.right, other.top))
                }
            }
        }
    }

    @Test
    fun everyQuarterTurnCoversThePlacedPageWithItsSidesSwappedWhenSideways() {
        val crop = NupBox(20f, 40f, 200f, 300f)
        for (turns in 0..3) {
            val cell = place(scaled, 1, cropBox = crop, quarterTurns = turns)
            val area = NupCellArea.of(crop, cell.matrix, cell.slot())!!
            val scale = hypot(cell.matrix[0], cell.matrix[1])
            val sideways = turns % 2 == 1

            assertEquals("width $turns", (if (sideways) crop.height else crop.width) * scale, area.right - area.left, 1e-3)
            assertEquals("height $turns", (if (sideways) crop.width else crop.height) * scale, area.top - area.bottom, 1e-3)
            assertInside(cell.slot(), area)
        }
    }

    @Test
    fun aSingleUpPageIsCutToTheSheet() {
        val config = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = true)
        val cell = place(config, 0, contentBox = content)
        val area = NupCellArea.of(a6, cell.matrix, cell.slot())!!

        assertArea(NupArea(0.0, 0.0, A4_WIDTH_PT.toDouble(), A4_HEIGHT_PT.toDouble()), area)
    }

    @Test
    fun aPageOutsideItsSlotOrWithADegenerateMatrixHasNoArea() {
        val cell = place(scaled, 0)
        val shifted = cell.matrix.copyOf().also { it[4] += A4_WIDTH_PT }

        assertNull(NupCellArea.of(a6, shifted, cell.slot()))
        assertNull(NupCellArea.of(a6, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 10.0, 10.0), cell.slot()))
        assertNull(NupCellArea.of(a6, doubleArrayOf(1.0, 0.0, 0.0, Double.NaN, 0.0, 0.0), cell.slot()))
        assertNull(NupCellArea.of(a6, doubleArrayOf(1.0, 0.0, 0.0, 1.0), cell.slot()))
    }

    @Test
    fun touchingEdgesDoNotIntersect() {
        val area = NupArea(10.0, 20.0, 30.0, 40.0)

        assertTrue(area.intersects(29.9, 39.9, 50.0, 60.0))
        assertFalse(area.intersects(30.0, 20.0, 50.0, 40.0))
        assertFalse(area.intersects(10.0, 40.0, 30.0, 60.0))
        assertFalse(area.intersects(-5.0, 0.0, 10.0, 20.0))
        assertTrue(area.intersects(0.0, 0.0, 100.0, 100.0))
    }

    private fun place(
        config: NupConfig,
        slot: Int,
        cropBox: NupBox = a6,
        contentBox: NupBox? = null,
        quarterTurns: Int = 0
    ): NupCell = NupCellLayout.place(
        config, A4_WIDTH_PT, A4_HEIGHT_PT, slot, a6, cropBox, contentBox, quarterTurns, NupGeometry.DEFAULT_POSITION
    )

    private fun NupCell.slot() = NupBox(x, y, width, height)

    private fun assertArea(expected: NupArea, actual: NupArea?) {
        assertNotNull(actual)
        assertEquals(expected.left, actual!!.left, 1e-3)
        assertEquals(expected.bottom, actual.bottom, 1e-3)
        assertEquals(expected.right, actual.right, 1e-3)
        assertEquals(expected.top, actual.top, 1e-3)
    }

    private fun assertInside(slot: NupBox, area: NupArea) {
        assertTrue(area.left >= slot.x - 1e-3 && area.bottom >= slot.y - 1e-3)
        assertTrue(area.right <= slot.x + slot.width + 1e-3 && area.top <= slot.y + slot.height + 1e-3)
    }
}
