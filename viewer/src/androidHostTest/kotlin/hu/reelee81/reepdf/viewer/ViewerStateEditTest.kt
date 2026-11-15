package hu.reelee81.reepdf.viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateEditTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val state = ViewerState("test.pdf", true, scope)
    private val document = MarkDocument()

    @After
    fun tearDown() {
        state.close()
        scope.cancel()
    }

    private fun opened() {
        state.open(ViewerWait.Reason.OPENING, 0) { document }
        state.onSize(VIEW_WIDTH, VIEW_HEIGHT, SPACING)
    }

    private fun drawSomething() {
        state.editor.beginStroke(0, ViewerTextPoint(10f, 10f), 1f, 5f)
        state.editor.extendStroke(ViewerTextPoint(50f, 10f))
        state.editor.endStroke()
    }

    @Test
    fun editingStartsOnlyOnAnOpenDocumentAndEndsSearchAndSelection() {
        state.startEditing()
        assertFalse(state.editor.isActive)
        opened()
        state.startSearch()
        assertTrue(state.search.isActive)

        state.startEditing()

        assertTrue(state.editor.isActive)
        assertFalse(state.search.isActive)
        assertFalse(state.selection.isActive)
    }

    @Test
    fun backClosesTheQuestionThenThePanelThenAsksBeforeLeaving() {
        opened()
        state.startEditing()
        drawSomething()
        state.editor.tapSwatch()

        assertTrue(state.handleBack())
        assertNull(state.editor.panel)
        assertTrue(state.handleBack())
        assertEquals(ViewerEditQuestion.EXIT, state.editor.question)
        assertTrue(state.handleBack())
        assertNull(state.editor.question)
        assertTrue(state.editor.isActive)
    }

    @Test
    fun backWithoutChangesLeavesEditingAtOnce() {
        opened()
        state.startEditing()

        assertTrue(state.handleBack())

        assertFalse(state.editor.isActive)
        assertFalse(state.handleBack())
    }

    @Test
    fun nothingToSaveIsOnlyToldAndTheSaverIsNotCalled() {
        opened()
        state.startEditing()
        var calls = 0
        state.editSaver = { calls++; ViewerEditSaveResult.Unchanged }

        state.saveEdits()

        assertEquals(0, calls)
        assertSame(ViewerEditMessage.NothingToSave, state.editMessage)
        state.messageShown()
        assertNull(state.editMessage)
        assertTrue(state.editor.isActive)
    }

    @Test
    fun aSavedEditReopensTheSavedDocumentForReading() {
        opened()
        state.startEditing()
        drawSomething()
        val saved = MarkDocument()
        var plan: ViewerEditPlan? = null
        state.editSaver = { chosen ->
            plan = chosen
            ViewerEditSaveResult.Saved { saved }
        }

        state.saveEdits()

        assertEquals(1, plan?.added?.get(0)?.size)
        assertFalse(state.editor.isActive)
        assertNull(state.waitReason)
        assertTrue(document.closed)
        assertNull(state.editMessage)
    }

    @Test
    fun anUnchangedSaveLeavesEditingWithAMessage() {
        opened()
        state.startEditing()
        state.editor.eraseAll()
        state.editSaver = { ViewerEditSaveResult.Unchanged }

        state.saveEdits()

        assertFalse(state.editor.isActive)
        assertSame(ViewerEditMessage.NothingToSave, state.editMessage)
        assertNull(state.waitReason)
    }

    @Test
    fun aFailedSaveKeepsEditingAndTellsWhy() {
        opened()
        state.startEditing()
        drawSomething()
        state.editSaver = { ViewerEditSaveResult.Failed("disk full") }

        state.saveEdits()

        assertTrue(state.editor.isActive)
        assertEquals(1, state.editor.addedMarks(0).size)
        assertEquals("disk full", (state.editMessage as ViewerEditMessage.SaveFailed).text)
        assertNull(state.waitReason)
    }

    @Test
    fun aCancelledSaveKeepsEveryMark() {
        opened()
        state.startEditing()
        drawSomething()
        val never = CompletableDeferred<ViewerEditSaveResult>()
        state.editSaver = { never.await() }

        state.saveEdits()
        assertEquals(ViewerWait.Reason.SAVING_EDITS, state.waitReason)
        state.cancelSave()

        assertNull(state.waitReason)
        assertTrue(state.editor.isActive)
        assertEquals(1, state.editor.addedMarks(0).size)
        assertNull(state.editMessage)
    }

    @Test
    fun erasingTheShownPagesSparesThePartlyVisibleNeighbor() {
        opened()
        state.startEditing()
        drawSomething()
        state.editor.beginStroke(1, ViewerTextPoint(10f, 10f), 1f, 5f)
        state.editor.extendStroke(ViewerTextPoint(50f, 10f))
        state.editor.endStroke()
        val view = checkNotNull(state.viewport)
        val pages = checkNotNull(state.layout)
        assertTrue(pages.visiblePages(view.contentTop, view.contentTop + view.contentHeight).contains(1))

        state.editor.eraseVisible(state.labeledPages())

        assertEquals(setOf(0), state.labeledPages())
        assertTrue(state.editor.addedMarks(0).isEmpty())
        assertEquals(1, state.editor.addedMarks(1).size)
    }

    @Test
    fun aFailedEraseIsTold() {
        state.editor.onFailure("broken")

        assertEquals("broken", (state.editMessage as ViewerEditMessage.EraseFailed).text)
    }

    @Test
    fun aTouchOnAPageGivesItsPointAndTheGapGivesNothing() {
        opened()
        val pages = checkNotNull(state.layout)
        val view = checkNotNull(state.viewport)
        val zoom = view.zoom
        val left = (pages.left(0, view.contentLeft, view.contentWidth) * zoom - view.scrollX).toFloat()
        val gapY = (pages.bottom(0) * zoom - view.scrollY).toFloat() + 1f

        val hit = state.pageHit(left + 10f * zoom, 20f * zoom)
        val gap = state.pageHit(left + 10f * zoom, gapY)
        val beyond = state.pointOn(0, left - 10f * zoom, 20f * zoom)

        assertEquals(0, hit?.first)
        assertEquals(10f, hit!!.second.x, 0.01f)
        assertEquals(20f, hit.second.y, 0.01f)
        assertNull(gap)
        assertEquals(-10f, beyond!!.x, 0.01f)
    }

    private class MarkDocument : ViewerDocument, ViewerMarkSource {
        var closed = false

        override val pageCount = 3

        override suspend fun pageMarks(page: Int, blank: Boolean) = ViewerPageMarks.NONE

        override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) = Unit

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { PAGE_WIDTH }, FloatArray(pageCount) { PAGE_HEIGHT })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() {
            closed = true
        }

        override suspend fun search(page: Int, query: String) = ViewerTextHits.NONE

        override suspend fun count(page: Int, query: String) = 0

        override fun releaseCount() = Unit

        override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? = null

        override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint? = null

        override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = FloatArray(0)

        override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ""

        override suspend fun text(page: Int) = ""
    }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val VIEW_WIDTH = 1_080
        const val VIEW_HEIGHT = 2_000
        const val SPACING = 16.0
    }
}
