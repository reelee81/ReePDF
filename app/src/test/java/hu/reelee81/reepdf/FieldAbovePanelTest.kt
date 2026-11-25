package hu.reelee81.reepdf

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldAbovePanelTest {
    @Test
    fun aFieldAboveThePanelStaysWhereItIs() {
        assertEquals(0, FieldAbovePanel.scrollFor(100, 148, 300, 400))
        assertEquals(0, FieldAbovePanel.scrollFor(252, 300, 300, 400))
    }

    @Test
    fun aFieldThePanelCoversIsScrolledJustAboveIt() {
        assertEquals(20, FieldAbovePanel.scrollFor(272, 320, 300, 400))
        assertEquals(98, FieldAbovePanel.scrollFor(350, 398, 300, 400))
    }

    @Test
    fun aFieldBelowThePanelIsNotTheFieldsProblem() {
        assertEquals(0, FieldAbovePanel.scrollFor(400, 448, 300, 400))
    }
}
