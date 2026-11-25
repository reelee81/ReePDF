package hu.reelee81.reepdf.viewer

import java.io.ByteArrayInputStream
import java.io.File
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

class ViewerTypedEditorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val document = TypedDocument()
    private val journal = MemoryJournal()
    private val fonts = ViewerTextFonts { weight -> File("../app/src/main/res/font/roboto_${FILES.getValue(weight)}.ttf").readBytes() }
    private val editor = editor(journal)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun editor(target: ViewerEditJournal?): ViewerEditor = ViewerEditor(scope) { }.also { created ->
        created.journal = target
        created.textFonts = fonts
        created.signatures.attach(NoSignatures)
    }

    private fun editing(on: ViewerEditor = editor) {
        on.attach(document)
        on.onVisible(0..1)
        on.start()
        on.selectTool(ViewerEditTool.TEXT)
    }

    private fun tap(x: Float, y: Float, page: Int = 0, on: ViewerEditor = editor) = on.tapText(page, ViewerTextPoint(x, y), 300f, 400f, 2f)

    private fun typed(page: Int = 0, on: ViewerEditor = editor): List<ViewerMark> = on.addedMarks(page).filter { it.kind == ViewerMarkKind.TYPED }

    private val ViewerMark.typed: ViewerMarkText
        get() = requireNotNull(text)

    private fun open(): ViewerTypingDraft = requireNotNull(editor.typing)

    @Test
    fun aTapOpensAnEmptyBoxAndOnlyTheCommitMakesAStep() {
        editing()
        tap(50f, 100f)

        assertTrue(open().isNew)
        assertEquals("", open().mark.typed.text)
        assertEquals(50f, open().mark.typed.matrix[4], 0f)

        editor.typeText("Hello\nWorld", 9)
        assertEquals("Hello\nWorld", open().mark.typed.text)
        assertEquals(9, open().cursor)
        assertFalse(editor.state.canUndo)

        tap(250f, 380f)
        assertNull(editor.typing)
        assertEquals(listOf("Hello\nWorld"), typed().map { it.typed.text })
        assertEquals(0x000000, typed().single().color)
        assertTrue(editor.state.canUndo)
        editor.undo()
        assertTrue(typed().isEmpty())
    }

    @Test
    fun anEmptyBoxLeavesNothingBehind() {
        editing()
        tap(50f, 100f)
        editor.typeText("   \n", 4)
        tap(250f, 380f)

        assertNull(editor.typing)
        assertTrue(typed().isEmpty())
        assertFalse(editor.state.canUndo)
    }

    @Test
    fun lettersTheFontLacksNeverReachTheBoxAndTheCaretFollows() {
        editing()
        tap(50f, 100f)

        editor.typeText("ab😀c\t中", 4)

        assertEquals("abc", open().mark.typed.text)
        assertEquals(2, open().cursor)
        editor.moveCaret(99)
        assertEquals(3, open().cursor)
    }

    @Test
    fun typingIntoAFullBoxKeepsWhatWasThere() {
        editing()
        tap(10f, 10f)
        val full = "a".repeat(ViewerMarkText.MAX_CHARS - 1) + "Z"
        editor.typeText(full, full.length)
        assertEquals(full, open().mark.typed.text)

        editor.typeText(full.substring(0, 20) + "new" + full.substring(20), 23)

        assertEquals(full, open().mark.typed.text)
        assertEquals(20, open().cursor)
    }

    @Test
    fun aTapInsideTheOpenBoxOnlyMovesTheCaret() {
        editing()
        tap(50f, 100f)
        editor.typeText("Caret", 5)
        val text = open().mark.typed

        editor.placeCaret(0, ViewerTextPoint(50.5f, 100f))
        assertEquals(0, open().cursor)
        editor.placeCaret(1, ViewerTextPoint(200f, 100f))
        assertEquals(0, open().cursor)
        assertSame(text, open().mark.typed)
        editor.placeCaret(0, ViewerTextPoint(200f, 100f))
        assertEquals(5, open().cursor)
        assertTrue(open().isNew)
    }

    @Test
    fun undoIsOfferedForAnOpenBoxThatWouldMakeAStep() {
        editing()
        tap(50f, 100f)
        assertFalse(editor.canUndo)
        editor.typeText("Undo me", 7)
        assertTrue(editor.canUndo)
        assertFalse(editor.state.canUndo)
        tap(250f, 380f)
        editor.undo()
        assertTrue(editor.canRedo)

        tap(50f, 200f)
        assertTrue(editor.canRedo)
        editor.typeText("x", 1)
        assertFalse(editor.canRedo)
        editor.undo()
        assertNull(editor.typing)
        assertEquals(listOf<String>(), typed().map { it.typed.text })
    }

    @Test
    fun aDragThatEndsWhereItStartedMakesNoStep() {
        editing()
        tap(50f, 100f)
        editor.typeText("Still", 5)
        tap(250f, 380f)
        tap(52f, 100f)

        assertTrue(editor.grabText(0, ViewerTextPoint(55f, 100f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(75f, 130f))
        editor.dragText(ViewerTextPoint(55f, 100f))
        editor.endTextDrag()

        assertFalse(open().changed)
        editor.undo()
        assertTrue(typed().isEmpty())
    }

    @Test
    fun retouchingATextEditsItAndEveryChangeIsItsOwnStep() {
        editing()
        tap(50f, 100f)
        editor.typeText("Label", 5)
        tap(250f, 380f)
        val placed = typed().single()

        tap(52f, 100f)
        assertSame(placed, open().editing.single())
        assertTrue(typed().isEmpty())
        assertTrue(open().cursor in 0..1)

        assertTrue(editor.grabText(0, ViewerTextPoint(55f, 100f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(75f, 130f))
        editor.endTextDrag()
        assertEquals(70f, open().mark.typed.matrix[4], 0.001f)

        editor.chooseColor(2)
        assertEquals(0xDD0000, open().mark.color)
        editor.chooseWidth(3)
        assertEquals(700, open().mark.typed.weight)
        editor.typeText("Label 2", 7)
        tap(250f, 380f)

        assertEquals(listOf("Label 2"), typed().map { it.typed.text })
        assertEquals(700, typed().single().typed.weight)
        editor.undo()
        assertEquals("Label", typed().single().typed.text)
        assertEquals(700, typed().single().typed.weight)
        editor.undo()
        assertEquals(400, typed().single().typed.weight)
        assertEquals(0xDD0000, typed().single().color)
        editor.undo()
        assertEquals(0x000000, typed().single().color)
        assertEquals(70f, typed().single().typed.matrix[4], 0.001f)
        editor.undo()
        assertEquals(50f, typed().single().typed.matrix[4], 0.001f)
        editor.undo()
        assertTrue(typed().isEmpty())
    }

    @Test
    fun resizingAnOpenBoxIsAStepAndTheNextTextStartsAtTheDefaultSize() {
        editing()
        tap(50f, 100f)
        editor.typeText("Bigger", 6)
        val text = open().mark.typed
        val frame = ViewerTypedPlacement.shown(text, fonts.font(400), 300f, 400f)

        assertTrue(editor.grabText(0, ViewerTextPoint(frame[2] + 4f, frame[3] + 4f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(frame[2] + 4f + (frame[2] - frame[0]), frame[3] + 4f + (frame[3] - frame[1])))
        editor.endTextDrag()

        assertEquals(24f, open().mark.typed.size, 0.01f)
        assertEquals(1, open().editing.size)
        assertTrue(editor.state.canUndo)
        tap(280f, 390f)
        assertEquals(24f, typed().single().typed.size, 0.01f)
        tap(150f, 200f)
        assertEquals(ViewerMarkText.DEFAULT_SIZE, open().mark.typed.size, 0f)
    }

    @Test
    fun everyNewTextStartsAtTheDefaultSize() {
        val text = ViewerMarkText.upright("Large", 400, 40f, 40f, 60f)
        val mark = ViewerTypedPlacement.mark(0, 0, text, fonts.font(400))
        document.marks[0] = ViewerPageMarks(listOf(ViewerSavedMark("m", 7, ViewerMarkKind.TYPED, 0, 0f, mark.path, null, text)), intArrayOf(7))
        editing()

        tap(42f, 62f)
        assertEquals(40f, open().mark.typed.size, 0.01f)
        tap(250f, 380f)

        tap(10f, 200f)
        assertEquals(ViewerMarkText.DEFAULT_SIZE, open().mark.typed.size, 0f)
        val line = "fitted ".repeat(12)
        editor.typeText(line, line.length)
        assertTrue(open().mark.typed.size < ViewerMarkText.DEFAULT_SIZE)
        tap(250f, 380f)

        tap(50f, 300f)
        assertEquals(ViewerMarkText.DEFAULT_SIZE, open().mark.typed.size, 0f)
    }

    @Test
    fun anEmptyBoxIsTouchedAndResizedByItsWiderFrame() {
        editing()
        tap(50f, 100f)
        val shown = ViewerTypedPlacement.shown(open().mark.typed, fonts.font(400), 300f, 400f)
        val width = shown[2] - shown[0]
        val height = shown[3] - shown[1]
        assertEquals(30f, width, 1e-3f)

        tap(shown[2] - 2f, (shown[1] + shown[3]) / 2)
        assertTrue(editor.typing != null)

        assertTrue(editor.grabText(0, ViewerTextPoint(shown[2] + 4f, shown[3] + 4f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(shown[2] + 4f + width, shown[3] + 4f))
        editor.endTextDrag()

        val expected = 12f * (2 * width * width + height * height) / (width * width + height * height)
        assertEquals(expected, open().mark.typed.size, 0.01f)
    }

    @Test
    fun aTouchedTextBecomesTheRememberedColorAndWeightAndThePanelsShowIt() {
        editing()
        tap(50f, 100f)
        editor.typeText("apple", 5)
        tap(250f, 380f)
        tap(50f, 200f)
        editor.typeText("pear", 4)
        tap(250f, 380f)
        tap(52f, 100f)
        editor.chooseWidth(4)
        editor.chooseColor(2)
        tap(250f, 380f)
        editor.undo()
        editor.undo()

        assertEquals(400, typed().first().typed.weight)
        assertEquals(4, editor.shownWidthIndex(ViewerEditTool.TEXT))
        assertEquals(2, editor.shownColorIndex(ViewerEditTool.TEXT))
        assertEquals(0xDD0000, editor.shownColor(ViewerEditTool.TEXT))

        tap(52f, 200f)
        assertEquals(0, editor.settings.textWeight)
        assertEquals(0, editor.settings.textColor)
        assertEquals(0, editor.shownWidthIndex(ViewerEditTool.TEXT))
        assertEquals(0, editor.shownColorIndex(ViewerEditTool.TEXT))
        assertEquals(0x000000, editor.shownColor(ViewerEditTool.TEXT))
        assertEquals(editor.settings.widthIndex(ViewerEditTool.PEN), editor.shownWidthIndex(ViewerEditTool.PEN))
        editor.chooseWidth(3)
        editor.chooseColor(1)
        tap(250f, 380f)
        assertEquals(3, editor.shownWidthIndex(ViewerEditTool.TEXT))
        assertEquals(1, editor.shownColorIndex(ViewerEditTool.TEXT))

        tap(52f, 100f)
        tap(250f, 380f)
        assertEquals(0, editor.settings.textWeight)
        assertEquals(0, editor.settings.textColor)

        tap(50f, 300f)
        assertEquals(400, open().mark.typed.weight)
        assertEquals(0x000000, open().mark.color)
    }

    @Test
    fun aWeightDragAcrossSeveralStopsIsOneStep() {
        editing()
        tap(50f, 100f)
        editor.typeText("Drag", 4)
        tap(250f, 380f)
        tap(52f, 100f)

        editor.beginWidthChoice()
        for (index in 1..4) editor.chooseWidth(index)
        assertEquals(900, open().mark.typed.weight)
        editor.endWidthChoice()
        tap(250f, 380f)

        assertEquals(900, typed().single().typed.weight)
        editor.undo()
        assertEquals(400, typed().single().typed.weight)
        editor.undo()
        assertTrue(typed().isEmpty())
    }

    @Test
    fun aWeightDragBackToWhereItStartedMakesNoStep() {
        editing()
        tap(50f, 100f)
        editor.typeText("Back", 4)
        tap(250f, 380f)
        tap(52f, 100f)

        editor.beginWidthChoice()
        editor.chooseWidth(3)
        editor.chooseWidth(0)
        editor.endWidthChoice()

        assertFalse(open().changed)
        assertEquals(400, open().mark.typed.weight)
        tap(250f, 380f)
        editor.undo()
        assertTrue(typed().isEmpty())
    }

    @Test
    fun clearingAnExistingTextErasesIt() {
        editing()
        tap(50f, 100f)
        editor.typeText("erase me", 8)
        tap(250f, 380f)

        tap(52f, 100f)
        editor.typeText("", 0)
        tap(250f, 380f)

        assertTrue(typed().isEmpty())
        editor.undo()
        assertEquals("erase me", typed().single().typed.text)
    }

    @Test
    fun aColorOrWeightChosenWithoutAnOpenBoxOnlyChangesTheNextText() {
        editing()
        tap(50f, 100f)
        editor.typeText("first", 5)
        tap(250f, 380f)

        editor.chooseColor(1)
        editor.chooseWidth(4)
        tap(50f, 200f)
        editor.typeText("second", 6)
        tap(250f, 380f)

        val (first, second) = typed()
        assertEquals(0x000000, first.color)
        assertEquals(400, first.typed.weight)
        assertEquals(0x202FB0, second.color)
        assertEquals(900, second.typed.weight)
        assertEquals(1, editor.settings.textColor)
        assertEquals(4, editor.settings.textWeight)
    }

    @Test
    fun aSavedTextIsEditedInPlaceOfEveryCopy() {
        val text = ViewerMarkText.upright("Saved", 600, 10f, 40f, 60f)
        val mark = ViewerTypedPlacement.mark(0, 0x35C369, text, fonts.font(600))
        document.marks[0] = ViewerPageMarks(
            listOf(7, 8).map { stream -> ViewerSavedMark("t", stream, ViewerMarkKind.TYPED, mark.color, 0f, mark.path, null, text) },
            intArrayOf(7, 8)
        )
        editing()

        tap(42f, 62f)
        assertEquals(setOf(ViewerEditSession.SavedRef(0, 7, "t"), ViewerEditSession.SavedRef(0, 8, "t")), open().saved)
        assertEquals(setOf(7, 8), document.edits.keys)
        editor.typeText("Saved, fixed", 12)
        tap(250f, 380f)

        val plan = editor.plan()
        assertEquals(mapOf(7 to setOf("t"), 8 to setOf("t")), plan.erased)
        assertEquals(setOf(0), plan.erasedPages)
        assertEquals("Saved, fixed", plan.added.getValue(0).single().typed.text)
        assertEquals(0x35C369, plan.added.getValue(0).single().color)
        assertEquals(600, plan.added.getValue(0).single().typed.weight)
    }

    @Test
    fun anUntouchedSavedTextComesBackWhenItsBoxCloses() {
        val text = ViewerMarkText.upright("Stays", 400, 10f, 40f, 60f)
        val mark = ViewerTypedPlacement.mark(0, 0, text, fonts.font(400))
        document.marks[0] = ViewerPageMarks(listOf(ViewerSavedMark("m", 7, ViewerMarkKind.TYPED, 0, 0f, mark.path, null, text)), intArrayOf(7))
        editing()

        tap(42f, 62f)
        assertEquals(ViewerStreamEdit(false, setOf("m")), document.edits[7])
        tap(250f, 380f)

        assertEquals(ViewerStreamEdit.ORIGINAL, document.edits[7])
        assertTrue(editor.plan().isEmpty)
        assertFalse(editor.state.canUndo)
    }

    @Test
    fun aToolChangeAPauseOrAnUndoClosesTheOpenBox() {
        editing()
        tap(50f, 100f)
        editor.typeText("tool change", 11)
        editor.selectTool(ViewerEditTool.PEN)
        assertNull(editor.typing)
        assertEquals(1, typed().size)

        editor.selectTool(ViewerEditTool.TEXT)
        tap(50f, 200f)
        editor.typeText("pause", 5)
        editor.togglePause()
        assertNull(editor.typing)
        assertEquals(2, typed().size)
        editor.togglePause()

        tap(50f, 300f)
        editor.typeText("undo", 4)
        editor.undo()
        assertNull(editor.typing)
        assertEquals(2, typed().size)
    }

    @Test
    fun theOpenBoxComesBackAfterTheViewerIsRecreated() {
        editing()
        tap(50f, 100f)
        editor.typeText("Done", 4)
        tap(250f, 380f)
        tap(52f, 100f)
        editor.typeText("Done soon", 3)

        val next = resumed()

        val draft = requireNotNull(next.typing)
        assertEquals("Done soon", draft.mark.typed.text)
        assertEquals(3, draft.cursor)
        assertTrue(draft.changed)
        assertSame(next.state.added.getValue(0).single(), draft.editing.single())
        assertTrue(typed(on = next).isEmpty())
        next.commitTyping()
        assertEquals(listOf("Done soon"), typed(on = next).map { it.typed.text })
        next.undo()
        assertEquals(listOf("Done"), typed(on = next).map { it.typed.text })
    }

    @Test
    fun aNewBoxStillBeingTypedComesBackAndAClosedOneDoesNot() {
        editing()
        tap(50f, 100f)
        editor.typeText("Half done", 9)

        val reopened = requireNotNull(resumed().typing)
        assertTrue(reopened.isNew)
        assertEquals("Half done", reopened.mark.typed.text)

        editor.commitTyping()
        assertNull(resumed().typing)
    }

    @Test
    fun manyKeystrokesCompactTheJournal() {
        editing()
        tap(50f, 100f)
        val words = StringBuilder()
        repeat(400) { index ->
            words.append("word$index ")
            editor.typeText(words.toString(), words.length)
        }
        assertTrue(journal.rewrites > 0)
        assertTrue(journal.bytes.size < 64 * 1024 * 2)
        assertEquals(words.toString(), requireNotNull(resumed().typing).mark.typed.text)
    }

    @Test
    fun aStopAfterAnyJournalWriteNeverPutsATextOnThePageTwice() {
        editing()
        tap(50f, 100f)
        editor.typeText("Once", 4)
        assertTrue(editor.grabText(0, ViewerTextPoint(55f, 100f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(75f, 130f))
        editor.endTextDrag()
        editor.chooseColor(2)
        editor.typeText("Once more", 9)
        tap(250f, 380f)

        assertEquals(listOf("Once more"), typed().map { it.typed.text })
        assertEveryStopKeepsOneText()
    }

    @Test
    fun aCompactionRightAfterAStepOnTheOpenBoxKeepsTheBoxAsItIsAfterTheStep() {
        editing()
        editor.selectTool(ViewerEditTool.PEN)
        editor.beginStroke(1, ViewerTextPoint(10f, 10f), 0.5f, 2f)
        for (index in 1..4000) editor.extendStroke(ViewerTextPoint(10f + index % 200, 10f + index % 2 * 5f + index / 200 * 7f))
        editor.endStroke()
        editor.undo()
        editor.selectTool(ViewerEditTool.TEXT)
        tap(50f, 100f)
        editor.typeText("Kept", 4)
        val rewrites = journal.rewrites

        assertTrue(editor.grabText(0, ViewerTextPoint(55f, 100f), 10f, 2f, 4f))
        editor.dragText(ViewerTextPoint(75f, 130f))
        editor.endTextDrag()

        assertEquals(rewrites + 1, journal.rewrites)
        assertEveryStopKeepsOneText()
        val box = requireNotNull(resumed().typing)
        assertFalse(box.isNew)
        assertEquals(70f, box.mark.typed.matrix[4], 0.001f)
    }

    private fun assertEveryStopKeepsOneText() {
        for (bytes in journal.snapshots) {
            val next = resumed(bytes)
            next.commitTyping()
            assertTrue(typed(on = next).size <= 1)
        }
    }

    private fun resumed(bytes: ByteArray = journal.bytes): ViewerEditor {
        val restored = (ViewerEditJournalFormat.read(ByteArrayInputStream(bytes)::read, IDENTITY) as ViewerEditJournalRead.Found).restore
        assertTrue(restored.complete)
        return editor(MemoryJournal()).also { next ->
            next.attach(document)
            next.onVisible(0..1)
            next.resume(RESUME, restored)
        }
    }

    private class MemoryJournal : ViewerEditJournal {
        private val header = ViewerEditJournalFormat.header(IDENTITY)
        var bytes = ByteArray(0)
        var rewrites = 0
        val snapshots = ArrayList<ByteArray>()

        override fun append(record: ViewerEditRecord) {
            if (bytes.isEmpty()) bytes = header
            bytes += ViewerEditJournalFormat.encode(record)
            snapshots += bytes
        }

        override fun rewrite(records: List<ViewerEditRecord>) {
            bytes = records.fold(header) { written, record -> written + ViewerEditJournalFormat.encode(record) }
            rewrites++
            snapshots += bytes
        }

        override fun discard() {
            bytes = ByteArray(0)
        }
    }

    private object NoSignatures : ViewerSignatureStore {
        override suspend fun read(slot: Int): ViewerSignature? = null

        override suspend fun write(slot: Int, signature: ViewerSignature) = Unit

        override suspend fun delete(slot: Int) = Unit
    }

    private class TypedDocument : ViewerDocument, ViewerMarkSource {
        val marks = HashMap<Int, ViewerPageMarks>()
        val edits = HashMap<Int, ViewerStreamEdit>()

        override val pageCount = 2

        override suspend fun pageMarks(page: Int, blank: Boolean): ViewerPageMarks = marks[page] ?: ViewerPageMarks.NONE

        override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) {
            this.edits.putAll(edits)
        }

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { 300f }, FloatArray(pageCount) { 400f })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() = Unit

        override suspend fun search(page: Int, query: String) = ViewerTextHits.NONE

        override suspend fun count(page: Int, query: String) = 0

        override fun releaseCount() = Unit

        override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float): ViewerTextSpan? = null

        override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint = point

        override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ViewerTextHighlight.NONE

        override suspend fun baselines(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = FloatArray(0)

        override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ""

        override suspend fun text(page: Int) = ""
    }

    private companion object {
        const val IDENTITY = "/data/cache/viewer.pdf\n1234\n5678"
        val FILES = mapOf(400 to "regular", 500 to "medium", 600 to "semibold", 700 to "bold", 900 to "black")
        val RESUME = ViewerResume(null, editing = true, paused = false, panel = null, rowOpen = false, question = null, deleting = null, saving = false)
    }
}
