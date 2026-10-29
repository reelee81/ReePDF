package hu.reelee81.reepdf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleModeTest {
    @Test
    fun theStoredFlagsGiveTheThreePositions() {
        assertEquals(ScaleMode.OFF, ScaleMode.of(scale = false, fit = false))
        assertEquals(ScaleMode.SCALE, ScaleMode.of(scale = true, fit = false))
        assertEquals(ScaleMode.FIT, ScaleMode.of(scale = true, fit = true))
    }

    @Test
    fun fitWithoutScaleIsOff() {
        assertEquals(ScaleMode.OFF, ScaleMode.of(scale = false, fit = true))
    }

    @Test
    fun everyPositionStoresTheFlagsItWasReadFrom() {
        for (mode in ScaleMode.entries) {
            assertEquals(mode, ScaleMode.of(mode.scale, mode.fit))
        }
        assertFalse(ScaleMode.OFF.scale)
        assertTrue(ScaleMode.FIT.fit)
    }

    @Test
    fun positionsFollowTheSwitchOrderAndAreClamped() {
        assertEquals(ScaleMode.OFF, ScaleMode.at(0))
        assertEquals(ScaleMode.SCALE, ScaleMode.at(1))
        assertEquals(ScaleMode.FIT, ScaleMode.at(2))
        assertEquals(ScaleMode.OFF, ScaleMode.at(-1))
        assertEquals(ScaleMode.FIT, ScaleMode.at(3))
        for (mode in ScaleMode.entries) {
            assertEquals(mode, ScaleMode.at(mode.ordinal))
        }
    }
}
