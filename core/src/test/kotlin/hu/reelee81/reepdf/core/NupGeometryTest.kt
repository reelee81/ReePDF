package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NupGeometryTest {
    @Test
    fun theMarginLimitKeepsEveryCellAtLeastOneMillimetre() {
        assertEquals(104, NupGeometry.maxMarginMm(1, 1, isLandscape = false))
        assertEquals(104, NupGeometry.maxMarginMm(1, 1, isLandscape = true))
        assertEquals(69, NupGeometry.maxMarginMm(2, 2, isLandscape = false))
        assertEquals(41, NupGeometry.maxMarginMm(1, 4, isLandscape = false))
        assertEquals(18, NupGeometry.maxMarginMm(10, 10, isLandscape = true))
        assertEquals(1, NupGeometry.maxMarginMm(100, 100, isLandscape = false))
    }

    @Test
    fun theMarginLimitNeverGoesBelowZero() {
        assertEquals(0, NupGeometry.maxMarginMm(1, 300, isLandscape = false))
    }

    @Test
    fun onlyTheNineGridPositionsAreValid() {
        for (position in 1..9) assertEquals(position, NupGeometry.validPosition(position))
        assertEquals(NupGeometry.DEFAULT_POSITION, NupGeometry.validPosition(0))
        assertEquals(NupGeometry.DEFAULT_POSITION, NupGeometry.validPosition(10))
        assertEquals(NupGeometry.DEFAULT_POSITION, NupGeometry.validPosition(-1))
        assertEquals(5, NupGeometry.DEFAULT_POSITION)
    }

    @Test
    fun positionsMapToTheThreeByThreeGrid() {
        val expected = listOf(
            0f to 1f, 0.5f to 1f, 1f to 1f,
            0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f,
            0f to 0f, 0.5f to 0f, 1f to 0f
        )
        expected.forEachIndexed { index, (horizontal, vertical) ->
            val position = index + 1
            assertEquals("horizontal $position", horizontal, NupGeometry.horizontalPositionFactor(position), 0f)
            assertEquals("vertical $position", vertical, NupGeometry.verticalPositionFactor(position), 0f)
        }
    }
}
