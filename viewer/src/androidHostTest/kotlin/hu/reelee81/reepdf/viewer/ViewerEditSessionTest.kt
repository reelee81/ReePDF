package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditSessionTest {
    private val session = ViewerEditSession()

    private fun mark(page: Int) = ViewerMark(page, ViewerMarkKind.PEN, 0, 2f, ViewerStrokeBuilder(1f).apply { add(1f, 1f) }.path())

    private fun saved(page: Int, stream: Int, id: String) = ViewerEditSession.SavedRef(page, stream, id)

    @Test
    fun anAddedMarkCanBeUndoneAndRedone() {
        val first = mark(0)

        val added = session.perform(ViewerEditSession.Action.Add(first))
        val undone = session.undo()
        val redone = session.redo()

        assertEquals(listOf(first), added.added[0])
        assertTrue(added.canUndo)
        assertFalse(added.canRedo)
        assertTrue(undone.added.isEmpty())
        assertTrue(undone.canRedo)
        assertFalse(undone.hasChanges)
        assertEquals(listOf(first), redone.added[0])
        assertTrue(redone.hasChanges)
    }

    @Test
    fun aNewActionDropsWhatCouldBeRedone() {
        session.perform(ViewerEditSession.Action.Add(mark(0)))
        session.undo()

        val state = session.perform(ViewerEditSession.Action.Add(mark(1)))

        assertFalse(state.canRedo)
        assertEquals(setOf(1), state.added.keys)
    }

    @Test
    fun erasingRemovesNewMarksAndHidesSavedOnes() {
        val first = mark(0)
        val second = mark(0)
        session.perform(ViewerEditSession.Action.Add(first))
        session.perform(ViewerEditSession.Action.Add(second))

        val state = session.perform(ViewerEditSession.Action.Erase(setOf(first), setOf(saved(0, 7, "a"), saved(0, 8, "a"))))

        assertEquals(listOf(second), state.added[0])
        assertFalse(state.isVisible(saved(0, 7, "a")))
        assertTrue(state.isVisible(saved(0, 7, "b")))
        assertEquals(ViewerStreamEdit(false, setOf("a")), state.streamEdit(0, 7))
        assertEquals(ViewerStreamEdit.ORIGINAL, state.streamEdit(0, 9))
        assertEquals(mapOf(7 to setOf("a"), 8 to setOf("a")), state.plan().erased)
        assertTrue(session.undo().isVisible(saved(0, 7, "a")))
    }

    @Test
    fun erasingOnlyNewMarksLeavesNothingToSave() {
        val first = mark(0)
        session.perform(ViewerEditSession.Action.Add(first))

        val state = session.perform(ViewerEditSession.Action.Erase(setOf(first), emptySet()))

        assertFalse(state.hasChanges)
        assertTrue(state.plan().isEmpty)
        assertTrue(state.canUndo)
    }

    @Test
    fun blankingPagesClearsTheirMarksAndLaterMarksStay() {
        session.perform(ViewerEditSession.Action.Add(mark(0)))
        session.perform(ViewerEditSession.Action.Add(mark(1)))
        session.perform(ViewerEditSession.Action.Erase(emptySet(), setOf(saved(0, 7, "a"), saved(1, 9, "b"))))
        session.perform(ViewerEditSession.Action.Blank(setOf(0)))
        val later = mark(0)

        val state = session.perform(ViewerEditSession.Action.Add(later))

        assertEquals(listOf(later), state.added[0])
        assertEquals(1, state.added[1]?.size)
        assertTrue(state.isBlank(0))
        assertEquals(ViewerStreamEdit.BLANK, state.streamEdit(0, 7))
        assertFalse(state.isVisible(saved(0, 7, "z")))
        val plan = state.plan()
        assertEquals(setOf(0), plan.blankPages)
        assertEquals(mapOf(9 to setOf("b")), plan.erased)
        assertFalse(plan.blankAll)
    }

    @Test
    fun blankingEveryPageWinsUntilItIsUndone() {
        session.perform(ViewerEditSession.Action.Add(mark(3)))
        session.perform(ViewerEditSession.Action.Blank(setOf(1)))

        val state = session.perform(ViewerEditSession.Action.Blank(null))

        assertTrue(state.added.isEmpty())
        assertTrue(state.isBlank(5))
        assertEquals(ViewerStreamEdit.BLANK, state.streamEdit(5, 40))
        assertEquals(emptySet<Int>(), state.plan().blankPages)
        assertTrue(state.plan().blankAll)
        val undone = session.undo()
        assertFalse(undone.blankAll)
        assertTrue(undone.isBlank(1))
        assertFalse(undone.isBlank(3))
    }

    @Test
    fun clearingForgetsEverything() {
        session.perform(ViewerEditSession.Action.Add(mark(0)))

        val state = session.clear()

        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertTrue(state.plan().isEmpty)
    }
}
