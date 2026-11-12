package hu.reelee81.reepdf.viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSelectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val selection = ViewerSelection(scope)
    private val word = ViewerTextSpan(ViewerTextPoint(10f, 25f), ViewerTextPoint(50f, 25f), floatArrayOf(10f, 20f, 50f, 30f))

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun document(pageCount: Int = 10, wordPage: Int = 2) = FakeTextDocument(pageCount).apply {
        words = { page, _ -> if (page == wordPage) word else null }
    }.also(selection::attach)

    private fun assertHandle(page: Int, x: Float, y: Float, handle: ViewerTextHandle?) {
        assertNotNull(handle)
        assertEquals(listOf(page.toFloat(), x, y, LINE_HEIGHT), listOf(handle!!.page.toFloat(), handle.x, handle.y, handle.lineHeight))
    }

    @Test
    fun aLongPressOnTextSelectsTheWordWithItsHandles() {
        val doc = document()
        selection.onVisible(0..3)

        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)

        assertTrue(selection.isActive)
        assertEquals(ViewerSelection.Anchor(2, word.from), selection.first)
        assertEquals(ViewerSelection.Anchor(2, word.to), selection.last)
        assertArrayEquals(word.rects, selection.rectsOf(2), 0f)
        assertEquals(listOf("word 2"), doc.calls)
        assertHandle(2, 10f, 30f, selection.startHandle())
        assertHandle(2, 50f, 30f, selection.endHandle())
    }

    @Test
    fun aLongPressBesideTheTextKeepsTheSelectionAsItWas() {
        document()
        selection.onVisible(0..3)

        selection.selectWord(1, ViewerTextPoint(20f, 25f), 3f)
        assertFalse(selection.isActive)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        selection.selectWord(1, ViewerTextPoint(20f, 25f), 3f)

        assertEquals(ViewerSelection.Anchor(2, word.from), selection.first)
    }

    @Test
    fun aSelectionOverSeveralPagesAsksEachVisiblePageForItsPart() {
        val doc = document()
        selection.onVisible(2..5)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.calls.clear()

        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))
        selection.dragTo(4, ViewerTextPoint(70f, 40f))

        assertEquals(listOf("highlight 2 10.0,25.0 null", "highlight 3 null null", "highlight 4 null 70.0,40.0"), doc.calls)
        assertEquals(2..4, selection.pages)
        assertHandle(2, 10f, 20f, selection.startHandle())
        assertHandle(4, 70f, 20f, selection.endHandle())

        doc.calls.clear()
        selection.dragTo(1, ViewerTextPoint(5f, 5f))

        assertEquals(listOf("highlight 2 null 10.0,25.0"), doc.calls)
        assertEquals(1..2, selection.pages)
        assertNull(selection.startHandle())
        assertHandle(2, 10f, 20f, selection.endHandle())
        assertNull(selection.rectsOf(3))
        assertNull(selection.rectsOf(4))
        selection.endDrag()
        assertNull(selection.dragged)
    }

    @Test
    fun draggingTheStartHandleKeepsTheEndOfTheWordFixed() {
        val doc = document()
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.calls.clear()

        assertTrue(selection.beginDrag(ViewerSelection.Handle.START))
        assertEquals(ViewerSelection.Handle.START, selection.dragged)
        selection.dragTo(2, ViewerTextPoint(80f, 25f))

        assertEquals(listOf("highlight 2 50.0,25.0 80.0,25.0"), doc.calls)
    }

    @Test
    fun draggingKeepsTheTextPositionTheDocumentGivesForTheFinger() {
        val doc = document()
        doc.carets = { _, point -> ViewerTextPoint(point.x + 1f, 25f) }
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.calls.clear()

        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))
        selection.dragTo(2, ViewerTextPoint(70f, 32f))

        assertEquals(listOf("caret 2 70.0,32.0"), doc.caretCalls)
        assertEquals(ViewerSelection.Anchor(2, ViewerTextPoint(71f, 25f)), selection.last)
        assertEquals(listOf("highlight 2 10.0,25.0 71.0,25.0"), doc.calls)
    }

    @Test
    fun whileTheDocumentIsBusyOnlyTheNewestFingerPointWaitsForItsTurn() {
        val doc = document()
        doc.carets = { _, point -> ViewerTextPoint(point.x, 25f) }
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.caretGated = true
        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))

        selection.dragTo(2, ViewerTextPoint(60f, 30f))
        selection.dragTo(2, ViewerTextPoint(70f, 30f))
        selection.dragTo(2, ViewerTextPoint(80f, 30f))
        doc.openGate()

        assertEquals(ViewerSelection.Anchor(2, ViewerTextPoint(60f, 25f)), selection.last)
        assertEquals(listOf("caret 2 60.0,30.0", "caret 2 80.0,30.0"), doc.caretCalls)

        doc.openGate()

        assertEquals(ViewerSelection.Anchor(2, ViewerTextPoint(80f, 25f)), selection.last)
        assertEquals(0, doc.waiting)
    }

    @Test
    fun theLastFingerPointStillCountsAfterTheHandleIsReleased() {
        val doc = document()
        doc.carets = { _, point -> ViewerTextPoint(point.x, 25f) }
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.caretGated = true
        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))

        selection.dragTo(2, ViewerTextPoint(70f, 30f))
        selection.endDrag()
        doc.openGate()

        assertNull(selection.dragged)
        assertEquals(ViewerSelection.Anchor(2, ViewerTextPoint(70f, 25f)), selection.last)
    }

    @Test
    fun aNewSelectionDropsAFingerPointStillWaitingForItsPosition() {
        val doc = document()
        doc.carets = { _, point -> ViewerTextPoint(point.x, 25f) }
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        doc.caretGated = true
        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))

        selection.dragTo(2, ViewerTextPoint(70f, 30f))
        selection.selectAll(3)
        doc.openGate()

        assertTrue(selection.isWholePage(3))
        assertEquals(ViewerSelection.Anchor(3, null), selection.last)
    }

    @Test
    fun aHandleOnAnInvisiblePageCannotBeDragged() {
        document()
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        selection.onVisible(5..6)

        assertFalse(selection.beginDrag(ViewerSelection.Handle.END))
        assertNull(selection.rectsOf(2))
    }

    @Test
    fun copyJoinsThePagesWithLineBreaks() = runBlocking {
        val doc = document()
        selection.onVisible(2..4)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        selection.beginDrag(ViewerSelection.Handle.END)
        selection.dragTo(4, ViewerTextPoint(70f, 40f))
        doc.calls.clear()

        val text = selection.copyText()

        assertEquals("p2\np3\np4", text)
        assertEquals(listOf("copy 2 10.0,25.0 null", "copy 3 null null", "copy 4 null 70.0,40.0"), doc.calls)
    }

    @Test
    fun copyRefusesTooManyPagesWithoutReadingThem() = runBlocking {
        val doc = document(pageCount = 2_000, wordPage = 0)
        selection.onVisible(0..0)
        selection.selectWord(0, ViewerTextPoint(20f, 25f), 3f)
        selection.beginDrag(ViewerSelection.Handle.END)
        selection.dragTo(ViewerSelection.MAX_COPY_PAGES, ViewerTextPoint(1f, 1f))
        doc.calls.clear()

        assertNull(selection.copyText())
        assertTrue(doc.calls.isEmpty())

        selection.dragTo(ViewerSelection.MAX_COPY_PAGES - 1, ViewerTextPoint(1f, 1f))
        assertNotNull(selection.copyText())
    }

    @Test
    fun copyRefusesTooMuchText() = runBlocking {
        val doc = document()
        doc.pageTexts = { "x".repeat(ViewerSelection.MAX_COPY_CHARS / 2) }
        selection.onVisible(2..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)
        selection.beginDrag(ViewerSelection.Handle.END)
        selection.dragTo(3, ViewerTextPoint(70f, 40f))

        assertNull(selection.copyText())

        doc.pageTexts = { "x".repeat(ViewerSelection.MAX_COPY_CHARS / 2 - 1) }
        assertEquals(ViewerSelection.MAX_COPY_CHARS - 1, selection.copyText()?.length)
    }

    @Test
    fun selectAllCoversTheWholePageAndIsRecognised() = runBlocking {
        val doc = document()
        selection.onVisible(2..4)

        selection.selectAll(3)

        assertTrue(selection.isWholePage(3))
        assertFalse(selection.isWholePage(2))
        assertEquals(listOf("highlight 3 null null"), doc.calls)
        assertEquals("p3", selection.copyText())
        assertTrue(selection.beginDrag(ViewerSelection.Handle.END))
        selection.dragTo(3, ViewerTextPoint(60f, 15f))
        assertFalse(selection.isWholePage(3))
        assertEquals("highlight 3 0.0,15.0 60.0,15.0", doc.calls.last())
    }

    @Test
    fun clearForgetsTheSelection() {
        document()
        selection.onVisible(0..3)
        selection.selectWord(2, ViewerTextPoint(20f, 25f), 3f)

        selection.clear()

        assertFalse(selection.isActive)
        assertNull(selection.rectsOf(2))
        assertNull(selection.startHandle())
        assertTrue(selection.pages.isEmpty())
    }

    private companion object {
        const val LINE_HEIGHT = 10f
    }
}
