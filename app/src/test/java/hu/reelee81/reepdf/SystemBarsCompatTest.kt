package hu.reelee81.reepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarsCompatTest {
    @Test
    fun gestureNavigationWithoutAnyButtonsKeepsTheGap() {
        assertTrue(SystemBarsCompat.needsGestureGap(31, 0, 0, 0))
    }

    @Test
    fun aButtonBarAtTheBottomNeedsNoGap() {
        assertFalse(SystemBarsCompat.needsGestureGap(31, 0, 130, 0))
    }

    @Test
    fun buttonsAtTheSideInLandscapeNeedNoGap() {
        assertFalse(SystemBarsCompat.needsGestureGap(37, 0, 0, 126))
        assertFalse(SystemBarsCompat.needsGestureGap(37, 126, 0, 0))
    }

    @Test
    fun theKeyboardCoversOnlyWhatRisesAboveTheNavigationBar() {
        assertEquals(780, SystemBarsCompat.keyboardAboveNavigation(910, 130))
        assertEquals(910, SystemBarsCompat.keyboardAboveNavigation(910, 0))
        assertEquals(0, SystemBarsCompat.keyboardAboveNavigation(0, 130))
    }

    @Test
    fun beforeAndroid10TheGapStaysAsBefore() {
        assertTrue(SystemBarsCompat.needsGestureGap(28, 0, 130, 0))
        assertTrue(SystemBarsCompat.needsGestureGap(26, 0, 0, 0))
    }
}
