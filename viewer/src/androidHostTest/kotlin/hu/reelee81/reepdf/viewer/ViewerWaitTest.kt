package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerWaitTest {
    private var now = 1_000L
    private val wait = ViewerWait { now }

    @Test
    fun overlayAppearsAfterTheRevealDelay() {
        wait.start(ViewerWait.Reason.OPENING)
        assertTrue(wait.isActive)
        assertEquals(300L, wait.revealDelayMs())
        now = 1_299L
        assertFalse(wait.isRevealed())
        now = 1_300L
        assertTrue(wait.isRevealed())
    }

    @Test
    fun aNewReasonKeepsTheStartTime() {
        wait.start(ViewerWait.Reason.OPENING)
        now = 1_200L
        wait.start(ViewerWait.Reason.OPENING_EDITED)
        assertEquals(100L, wait.revealDelayMs())
        assertEquals(ViewerWait.Reason.OPENING_EDITED, wait.reason)
    }

    @Test
    fun onlyTheEditedDocumentLeavesOnCancel() {
        wait.start(ViewerWait.Reason.OPENING)
        assertFalse(wait.leavesOnCancel)
        wait.start(ViewerWait.Reason.OPENING_EDITED)
        assertTrue(wait.leavesOnCancel)
    }

    @Test
    fun stoppingEndsTheWait() {
        wait.start(ViewerWait.Reason.OPENING)
        wait.stop()
        assertFalse(wait.isActive)
        assertNull(wait.revealDelayMs())
        now = 5_000L
        wait.start(ViewerWait.Reason.OPENING)
        assertEquals(300L, wait.revealDelayMs())
    }
}
