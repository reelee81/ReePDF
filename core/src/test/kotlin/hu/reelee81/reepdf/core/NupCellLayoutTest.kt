package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.NupGeometry.A4_HEIGHT_PT
import hu.reelee81.reepdf.core.NupGeometry.A4_WIDTH_PT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NupCellLayoutTest {
    private val a4 = NupBox(0f, 0f, A4_WIDTH_PT, A4_HEIGHT_PT)

    @Test
    fun cellsAreCountedFromTheTopLeftWithMarginsBetweenThem() {
        val margin = 10 * 72f / 25.4f
        val cellW = (A4_WIDTH_PT - margin * 3) / 2
        val cellH = (A4_HEIGHT_PT - margin * 3) / 2
        val expected = listOf(
            margin to margin + cellH + margin,
            margin + cellW + margin to margin + cellH + margin,
            margin to margin,
            margin + cellW + margin to margin
        )
        for (slot in 0 until 8) {
            val cell = place(config(rows = 2, cols = 2, marginMm = 10), slot = slot)
            assertEquals("x $slot", expected[slot % 4].first, cell.x, 1e-3f)
            assertEquals("y $slot", expected[slot % 4].second, cell.y, 1e-3f)
            assertEquals(cellW, cell.width, 1e-3f)
            assertEquals(cellH, cell.height, 1e-3f)
        }
    }

    @Test
    fun aSingleUpLandscapePageShrinksAPortraitA4PageToItsHeight() {
        val k = A4_WIDTH_PT / A4_HEIGHT_PT
        val cell = place(config(rows = 1, cols = 1, landscape = true))

        assertMatrix(cell, k.toDouble(), 0.0, 0.0, k.toDouble(), ((A4_HEIGHT_PT - A4_WIDTH_PT * k) / 2).toDouble(), 0.0)
    }

    @Test
    fun aSmallPortraitPageIsNotEnlargedOnASingleUpPortraitPage() {
        val page = NupBox(0f, 0f, 200f, 300f)
        val cell = place(config(rows = 1, cols = 1), pageBox = page, cropBox = page)

        assertMatrix(cell, 1.0, 0.0, 0.0, 1.0, (A4_WIDTH_PT - 200f) / 2.0, (A4_HEIGHT_PT - 300f) / 2.0)
    }

    @Test
    fun aLargePortraitPageIsShrunkToFitASingleUpPortraitPage() {
        val page = NupBox(0f, 0f, 1190f, 1684f)
        val cell = place(config(rows = 1, cols = 1), pageBox = page, cropBox = page)

        assertMatrix(cell, 0.5, 0.0, 0.0, 0.5, 0.0, 0.0)
    }

    @Test
    fun aMatchingOrientationIsScaledLikeAnA4PageInItsCell() {
        val page = NupBox(0f, 0f, 400f, 600f)
        val cellW = A4_WIDTH_PT / 2
        val cellH = A4_HEIGHT_PT / 2
        val k = minOf(cellW / A4_WIDTH_PT, cellH / A4_HEIGHT_PT)
        val cell = place(config(rows = 2, cols = 2), pageBox = page, cropBox = page)

        assertMatrix(
            cell,
            k.toDouble(), 0.0, 0.0, k.toDouble(),
            ((cellW - 400f * k) / 2).toDouble(),
            (cellH + (cellH - 600f * k) / 2).toDouble()
        )
    }

    @Test
    fun aLargeMismatchedPageFillsItsCell() {
        val page = NupBox(0f, 0f, 600f, 400f)
        val cellW = A4_WIDTH_PT / 2
        val cellH = A4_HEIGHT_PT / 2
        val k = cellW / 600f
        val cell = place(config(rows = 2, cols = 2), pageBox = page, cropBox = page)

        assertMatrix(cell, k.toDouble(), 0.0, 0.0, k.toDouble(), 0.0, (cellH + (cellH - 400f * k) / 2).toDouble())
    }

    @Test
    fun aSmallMismatchedPageKeepsTheA4Scale() {
        val page = NupBox(0f, 0f, 300f, 200f)
        val cellW = A4_WIDTH_PT / 2
        val cellH = A4_HEIGHT_PT / 2
        val k = minOf(cellW / A4_HEIGHT_PT, cellH / A4_WIDTH_PT)
        val cell = place(config(rows = 2, cols = 2), pageBox = page, cropBox = page)

        assertMatrix(
            cell,
            k.toDouble(), 0.0, 0.0, k.toDouble(),
            ((cellW - 300f * k) / 2).toDouble(),
            (cellH + (cellH - 200f * k) / 2).toDouble()
        )
    }

    @Test
    fun scalingIndividuallyEnlargesASmallPageToItsCell() {
        val page = NupBox(0f, 0f, 100f, 100f)
        val cellW = A4_WIDTH_PT / 2
        val cellH = A4_HEIGHT_PT / 2
        val k = cellW / 100f
        val cell = place(config(rows = 2, cols = 2, scale = true), pageBox = page, cropBox = page)

        assertMatrix(cell, k.toDouble(), 0.0, 0.0, k.toDouble(), 0.0, (cellH + (cellH - 100f * k) / 2).toDouble())
    }

    @Test
    fun theMeasuredContentIsFittedAndCentredWhenFittingIndividually() {
        val content = NupBox(100f, 200f, 50f, 100f)
        val k = minOf(A4_WIDTH_PT / 50f, A4_HEIGHT_PT / 100f)
        val cell = place(config(rows = 1, cols = 1, scale = true, fit = true), contentBox = content)

        assertMatrix(
            cell,
            k.toDouble(), 0.0, 0.0, k.toDouble(),
            ((A4_WIDTH_PT - 50f * k) / 2 - 100f * k).toDouble(),
            ((A4_HEIGHT_PT - 100f * k) / 2 - 200f * k).toDouble()
        )
    }

    @Test
    fun theMeasuredContentIsIgnoredUnlessFittingIndividually() {
        val content = NupBox(100f, 200f, 50f, 100f)
        val withoutFit = place(config(rows = 1, cols = 1, scale = true), contentBox = content)
        val withoutContent = place(config(rows = 1, cols = 1, scale = true))

        assertArrayEquals(withoutContent.matrix, withoutFit.matrix, 0.0)
        assertMatrix(withoutFit, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }

    @Test
    fun quarterTurnsKeepAnOffsetCropInsideTheCell() {
        val crop = NupBox(100f, 50f, 300f, 200f)
        val cellW = A4_WIDTH_PT
        val cellH = A4_HEIGHT_PT
        val upright = place(config(rows = 1, cols = 1, scale = true), cropBox = crop, quarterTurns = 0)
        val k0 = minOf(cellW / 300f, cellH / 200f)
        val x0 = (cellW - 300f * k0) / 2
        val y0 = (cellH - 200f * k0) / 2
        assertMatrix(upright, k0.toDouble(), 0.0, 0.0, k0.toDouble(), (x0 - 100f * k0).toDouble(), (y0 - 50f * k0).toDouble())

        val k = minOf(cellW / 200f, cellH / 300f)
        val w = 300f * k
        val h = 200f * k
        val x = (cellW - h) / 2
        val y = (cellH - w) / 2
        assertMatrix(
            place(config(rows = 1, cols = 1, scale = true), cropBox = crop, quarterTurns = 1),
            0.0, k.toDouble(), -k.toDouble(), 0.0, (x + h + 50f * k).toDouble(), (y - 100f * k).toDouble()
        )
        assertMatrix(
            place(config(rows = 1, cols = 1, scale = true), cropBox = crop, quarterTurns = 2),
            -k0.toDouble(), 0.0, 0.0, -k0.toDouble(),
            (x0 + 300f * k0 + 100f * k0).toDouble(), (y0 + 200f * k0 + 50f * k0).toDouble()
        )
        assertMatrix(
            place(config(rows = 1, cols = 1, scale = true), cropBox = crop, quarterTurns = 3),
            0.0, -k.toDouble(), k.toDouble(), 0.0, (x - 50f * k).toDouble(), (y + w + 100f * k).toDouble()
        )
    }

    @Test
    fun anInvalidPositionFallsBackToTheCentre() {
        val page = NupBox(0f, 0f, 200f, 300f)
        val centre = place(config(rows = 1, cols = 1), pageBox = page, cropBox = page, position = 5)
        val invalid = place(config(rows = 1, cols = 1), pageBox = page, cropBox = page, position = 0)

        assertArrayEquals(centre.matrix, invalid.matrix, 0.0)
    }

    private fun config(
        rows: Int,
        cols: Int,
        marginMm: Int = 0,
        landscape: Boolean = false,
        scale: Boolean = false,
        fit: Boolean = false
    ) = NupConfig(rows, cols, marginMm, landscape, drawFrame = false, scaleIndividually = scale, fitIndividually = fit)

    private fun place(
        config: NupConfig,
        slot: Int = 0,
        pageBox: NupBox = a4,
        cropBox: NupBox = pageBox,
        contentBox: NupBox? = null,
        quarterTurns: Int = 0,
        position: Int = NupGeometry.DEFAULT_POSITION
    ): NupCell {
        val width = if (config.isLandscape) A4_HEIGHT_PT else A4_WIDTH_PT
        val height = if (config.isLandscape) A4_WIDTH_PT else A4_HEIGHT_PT
        return NupCellLayout.place(config, width, height, slot, pageBox, cropBox, contentBox, quarterTurns, position)
    }

    private fun assertMatrix(cell: NupCell, vararg expected: Double) {
        assertArrayEquals(expected, cell.matrix, 0.01)
    }
}
