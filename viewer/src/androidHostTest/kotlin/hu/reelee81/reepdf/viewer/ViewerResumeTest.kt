package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Rect
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

class ViewerResumeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val states = mutableListOf<ViewerState>()

    @After
    fun tearDown() {
        states.forEach(ViewerState::close)
        scope.cancel()
    }

    private fun viewer(): ViewerState = ViewerState("test.pdf", true, scope).also(states::add)

    private fun resume(spot: ViewerSpot? = null, editing: Boolean = true, saving: Boolean = false) =
        ViewerResume(spot, editing, paused = false, panel = null, rowOpen = false, question = null, deleting = null, saving = saving)

    private fun restore(page: Int = 0, complete: Boolean = true) = ViewerEditRestore(
        listOf(ViewerEditSession.Action.Add(ViewerMark(page, ViewerMarkKind.PEN, 0, 2f, LINE))),
        1,
        null,
        complete
    )

    private fun opened(state: ViewerState, width: Int = VIEW_WIDTH, resume: ViewerResume? = null, read: ViewerEditJournalRead? = null) {
        state.open(ViewerWait.Reason.OPENING, 0, resume, read?.let { found -> { found } }) { PlainDocument() }
        state.onSize(width, VIEW_HEIGHT, SPACING)
    }

    private fun movedViewer(): ViewerState = viewer().also { state ->
        opened(state)
        state.zoomBy(3f, 200.0, 300.0)
        state.endZoom()
        state.scrollBy(150.0, 2_500.0)
    }

    @Test
    fun theViewComesBackAtTheSameZoomAndPlace() {
        val before = movedViewer()
        val spot = before.resumeState().spot
        val after = viewer()

        opened(after, resume = resume(spot, editing = false))

        val shown = before.viewport!!
        val back = after.viewport!!
        assertEquals(shown.zoom, back.zoom, 0.0001f)
        assertEquals(shown.contentTop, back.contentTop, 0.01)
        assertEquals(shown.contentLeft, back.contentLeft, 0.01)
        assertEquals(before.currentPage, after.currentPage)
        assertFalse(after.editor.isActive)
    }

    @Test
    fun onAWiderWindowTheViewKeepsItsZoomAgainstTheFullWidth() {
        val before = movedViewer()
        val after = viewer()

        opened(after, width = WIDE_WIDTH, resume = resume(before.resumeState().spot, editing = false))

        val pages = after.layout!!
        val shown = before.viewport!!
        val back = after.viewport!!
        assertEquals(
            shown.zoom / ViewerViewport.fitWidthZoom(VIEW_WIDTH, pages),
            back.zoom / ViewerViewport.fitWidthZoom(WIDE_WIDTH, pages),
            0.0001f
        )
        assertEquals(shown.contentTop, back.contentTop, 0.01)
    }

    @Test
    fun aResumedViewerReopensEditingWithTheJournal() {
        val state = viewer()

        opened(state, resume = resume(), read = ViewerEditJournalRead.Found(restore()))

        assertTrue(state.editor.isActive)
        assertEquals(1, state.editor.addedMarks(0).size)
        assertTrue(state.editor.state.canUndo)
        assertNull(state.editMessage)
    }

    @Test
    fun aSaveInterruptedByTheSystemIsToldWhenTheEditsCameBack() {
        val state = viewer()

        opened(state, resume = resume(saving = true), read = ViewerEditJournalRead.Found(restore()))

        assertSame(ViewerEditMessage.SaveInterrupted, state.editMessage)
        assertTrue(state.editor.state.hasChanges)
    }

    @Test
    fun anUnreadableOrCutOffJournalIsTold() {
        val unreadable = viewer()
        opened(unreadable, resume = resume(saving = true), read = ViewerEditJournalRead.Unreadable)
        val cut = viewer()
        opened(cut, resume = resume(), read = ViewerEditJournalRead.Found(restore(complete = false)))

        assertSame(ViewerEditMessage.RestoreIncomplete, unreadable.editMessage)
        assertTrue(unreadable.editor.isActive)
        assertFalse(unreadable.editor.state.hasChanges)
        assertSame(ViewerEditMessage.RestoreIncomplete, cut.editMessage)
        assertEquals(1, cut.editor.addedMarks(0).size)
    }

    @Test
    fun aJournalThatDoesNotFitTheDocumentIsDroppedAndTold() {
        val state = viewer()

        opened(state, resume = resume(), read = ViewerEditJournalRead.Found(restore(page = 5)))

        assertSame(ViewerEditMessage.RestoreIncomplete, state.editMessage)
        assertTrue(state.editor.isActive)
        assertFalse(state.editor.state.hasChanges)
    }

    @Test
    fun aJournalOfAnotherDocumentIsIgnoredSilently() {
        val state = viewer()

        opened(state, resume = resume(), read = ViewerEditJournalRead.None)

        assertTrue(state.editor.isActive)
        assertNull(state.editMessage)
    }

    @Test
    fun withoutAResumeNothingIsRestored() {
        val state = viewer()

        opened(state, read = ViewerEditJournalRead.Found(restore()))

        assertFalse(state.editor.isActive)
        assertTrue(state.editor.addedMarks(0).isEmpty())
    }

    @Test
    fun theResumeStaysUntilTheDocumentIsOpen() {
        val state = viewer()
        val waiting = resume(saving = true)
        val never = CompletableDeferred<ViewerDocument>()

        state.open(ViewerWait.Reason.OPENING, 0, waiting) { never.await() }

        assertSame(waiting, state.resumeState())
    }

    @Test
    fun theResumeStateTellsTheEditorAndASaveInProgress() {
        val state = viewer()
        opened(state)
        state.startEditing()
        state.editor.beginStroke(0, ViewerTextPoint(10f, 10f), 1f, 5f)
        state.editor.extendStroke(ViewerTextPoint(50f, 10f))
        state.editor.endStroke()
        state.editor.togglePause()
        state.editor.question = ViewerEditQuestion.ERASE_ALL
        val saving = CompletableDeferred<ViewerEditSaveResult>()
        state.editSaver = { saving.await() }

        state.saveEdits()
        val shown = state.resumeState()

        assertTrue(shown.editing)
        assertTrue(shown.paused)
        assertTrue(shown.saving)
        assertNull(shown.question)
    }

    @Test
    fun thePlacerComesBackWithTheMovedSignatureAndItsQuestion() {
        val placer = SignaturePlacerState(SignaturePlacerSpec(210f, 297f, 50f, 20f, 160f, 277f, 10f, 10f), scope)
        val spot = ViewerSpot(0, 30.0, 5.0, 1.5f)

        placer.resume(80f, 400f, question = true, spot = spot)

        assertEquals(80f, placer.xMm, 0f)
        assertEquals(277f, placer.yMm, 0f)
        assertTrue(placer.asking)
        assertTrue(placer.isModified)
        assertSame(spot, placer.surface.spot)
    }

    @Test
    fun theCropperComesBackWithItsFrameAndKeepsTheStartForAnInvalidOne() {
        val cropper = PageCropperState(PageCropperSpec(0.1f, 0.1f, 0.9f, 0.9f, allPages = false, minSize = 0.05f), scope)
        val other = PageCropperState(PageCropperSpec(0.1f, 0.1f, 0.9f, 0.9f, allPages = false, minSize = 0.05f), scope)

        cropper.resume(0.2f, 0.3f, 0.6f, 0.7f, all = true, question = true, spot = null)
        other.resume(0.6f, 0.3f, 0.2f, Float.NaN, all = false, question = false, spot = null)

        assertEquals(Rect(0.2f, 0.3f, 0.6f, 0.7f), cropper.frame)
        assertTrue(cropper.allPages)
        assertTrue(cropper.asking)
        assertEquals(Rect(0.1f, 0.1f, 0.9f, 0.9f), other.frame)
        assertFalse(other.isModified)
    }

    private class PlainDocument : ViewerDocument, ViewerMarkSource {
        override val pageCount = 3

        override suspend fun pageMarks(page: Int, blank: Boolean) = ViewerPageMarks.NONE

        override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) = Unit

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { PAGE_WIDTH }, FloatArray(pageCount) { PAGE_HEIGHT })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() = Unit

        override suspend fun search(page: Int, query: String) = ViewerTextHits.NONE

        override suspend fun count(page: Int, query: String) = 0

        override fun releaseCount() = Unit

        override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? = null

        override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint? = null

        override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ViewerTextHighlight.NONE

        override suspend fun baselines(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = FloatArray(0)

        override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ""

        override suspend fun text(page: Int) = ""
    }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val VIEW_WIDTH = 1_080
        const val WIDE_WIDTH = 1_600
        const val VIEW_HEIGHT = 2_000
        const val SPACING = 16.0
        val LINE = ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(10f, 10f, 50f, 10f))
    }
}
