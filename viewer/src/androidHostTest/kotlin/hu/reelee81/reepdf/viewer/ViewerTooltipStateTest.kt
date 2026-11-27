package hu.reelee81.reepdf.viewer

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTooltipStateTest {
    private val anchor = IntRect(0, 0, 10, 10)

    @Test
    fun liftingAFingerWithoutABubbleCountsNothing() {
        val tooltip = ViewerTooltipState()
        tooltip.release()
        assertNull(tooltip.current)
        assertEquals(0, tooltip.releases)
    }

    @Test
    fun everyLiftWhileShownRestartsTheHideTimer() {
        val tooltip = ViewerTooltipState()
        tooltip.show("Toll", anchor)
        assertEquals("Toll", tooltip.current?.text)
        assertEquals(0, tooltip.releases)
        tooltip.release()
        tooltip.release()
        assertEquals(2, tooltip.releases)
    }

    @Test
    fun aNewBubbleReplacesTheOldOneAndWaitsForItsOwnLift() {
        val tooltip = ViewerTooltipState()
        tooltip.show("Toll", anchor)
        tooltip.release()
        tooltip.show("Radír", anchor)
        assertEquals("Radír", tooltip.current?.text)
        assertEquals(0, tooltip.releases)
    }

    @Test
    fun aDismissedBubbleIgnoresLaterLifts() {
        val tooltip = ViewerTooltipState()
        tooltip.show("Toll", anchor)
        tooltip.dismiss()
        tooltip.release()
        assertNull(tooltip.current)
        assertEquals(0, tooltip.releases)
    }
}
