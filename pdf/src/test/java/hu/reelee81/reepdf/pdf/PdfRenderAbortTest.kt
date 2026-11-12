package hu.reelee81.reepdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderAbortTest {
    @Test
    fun anAbortDuringTheBlockReachesTheBoundActionOnce() {
        val abort = PdfRenderAbort()
        var calls = 0

        val result = abort.whileBound({ calls++ }) {
            abort.abort()
            abort.abort()
            "rendered"
        }

        assertEquals("rendered", result)
        assertEquals(1, calls)
        assertTrue(abort.isAborted)
    }

    @Test
    fun anEarlierAbortRunsTheActionBeforeTheBlock() {
        val abort = PdfRenderAbort()
        val events = mutableListOf<String>()
        abort.abort()

        abort.whileBound({ events += "action" }) { events += "block" }

        assertEquals(listOf("action", "block"), events)
    }

    @Test
    fun anAbortAfterTheBlockNoLongerReachesTheAction() {
        val abort = PdfRenderAbort()
        var calls = 0

        abort.whileBound({ calls++ }) {}
        abort.abort()

        assertEquals(0, calls)
        assertTrue(abort.isAborted)
    }

    @Test
    fun theActionIsUnboundAlsoWhenTheBlockFails() {
        val abort = PdfRenderAbort()
        var calls = 0

        val failure = runCatching { abort.whileBound({ calls++ }) { throw IllegalStateException("broken") } }
        abort.abort()

        assertEquals("broken", failure.exceptionOrNull()?.message)
        assertEquals(0, calls)
        assertFalse(failure.isSuccess)
    }
}
