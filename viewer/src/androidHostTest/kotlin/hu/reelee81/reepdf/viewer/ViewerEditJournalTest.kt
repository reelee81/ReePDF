package hu.reelee81.reepdf.viewer

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditJournalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val document = JournalDocument()
    private val journal = MemoryJournal()
    private val editor = editor(journal)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun editor(target: ViewerEditJournal?): ViewerEditor = ViewerEditor(scope) { }.also { created ->
        created.journal = target
        created.signatures.attach(SignatureStore())
    }

    private fun editing(chosen: ViewerEditor = editor) {
        chosen.attach(document)
        chosen.onVisible(0..1)
        chosen.start()
    }

    private fun draw(page: Int, vararg points: Pair<Float, Float>, on: ViewerEditor = editor) {
        on.beginStroke(page, ViewerTextPoint(points[0].first, points[0].second), 1f, 5f)
        for ((x, y) in points.drop(1)) on.extendStroke(ViewerTextPoint(x, y))
        on.endStroke()
    }

    private fun erase(at: Pair<Float, Float>) {
        editor.selectTool(ViewerEditTool.ERASER)
        editor.beginErase(0, ViewerTextPoint(at.first, at.second), 2f)
        editor.endErase()
        editor.selectTool(ViewerEditTool.PEN)
    }

    private fun resumed(read: ViewerEditJournalRead = journal.read()): ViewerEditor {
        val restored = (read as ViewerEditJournalRead.Found).restore
        assertTrue(restored.fits(document.pageCount))
        return editor(MemoryJournal()).also { next ->
            next.attach(document)
            next.onVisible(0..1)
            next.resume(RESUME, restored)
        }
    }

    private fun shown(of: ViewerEditor): String = buildString {
        for (page in 0 until document.pageCount) {
            for (mark in of.addedMarks(page)) {
                append("$page ${mark.kind} ${mark.color} ${mark.width} ${mark.path.verbs.contentToString()} ${mark.path.points.contentToString()}\n")
            }
        }
        val plan = of.plan()
        append("erased ${plan.erased.toSortedMap()} blank ${plan.blankPages.sorted()} all ${plan.blankAll} ")
        append("undo ${of.state.canUndo} redo ${of.state.canRedo}")
    }

    @Test
    fun everyKindOfStepComesBackFromTheJournalWithItsUndoPosition() {
        document.marks[0] = ViewerPageMarks(listOf(SAVED_LINE), intArrayOf(7))
        editing()
        draw(0, 10f to 80f, 40f to 85f, 70f to 80f)
        draw(1, 10f to 10f, 20f to 20f)
        draw(0, 50f to 20f, 90f to 20f)
        erase(70f to 20f)
        erase(15f to 50f)
        editor.selectTool(ViewerEditTool.SIGNATURE)
        editor.tapSignature(0, ViewerTextPoint(150f, 150f), 300f, 300f, 2f)
        val box = editor.framed!!.box
        assertTrue(editor.grabFramed(0, ViewerTextPoint(box[0] + 1f, box[1] + 1f), 3f, 1f, 0f))
        editor.dragFramed(ViewerTextPoint(box[0] + 21f, box[1] + 11f), 300f, 300f)
        editor.endFramedDrag()
        editor.releaseFramed()
        editor.eraseVisible(setOf(1))
        editor.undo()
        editor.undo()
        editor.redo()

        val next = resumed()

        assertTrue(next.isActive)
        assertEquals(shown(editor), shown(next))
        assertEquals(setOf(SAVED_LINE.id), next.plan().erased[SAVED_LINE.stream])
        next.redo()
        editor.redo()
        assertEquals(shown(editor), shown(next))
        next.undo()
        editor.undo()
        assertEquals(shown(editor), shown(next))
    }

    @Test
    fun anErasedMarkIsTheVeryMarkItsStepAdded() {
        editing()
        draw(0, 10f to 10f, 40f to 10f)
        draw(0, 10f to 60f, 40f to 60f)
        erase(20f to 10f)

        val restored = (journal.read() as ViewerEditJournalRead.Found).restore
        val added = restored.actions[0] as ViewerEditSession.Action.Add
        val erased = restored.actions[2] as ViewerEditSession.Action.Erase

        assertSame(added.mark, erased.marks.single())
        assertEquals(3, restored.position)
        assertTrue(restored.complete)
    }

    @Test
    fun theFirstStepStartsTheJournalAndUndoAndRedoAddOnlyAFewBytes() {
        editing()
        assertEquals(0, journal.bytes.size)
        draw(0, 10f to 10f, 40f to 10f)
        val drawn = journal.bytes.size

        editor.undo()
        editor.redo()

        assertTrue(drawn > ViewerEditJournalFormat.header(IDENTITY).size)
        assertEquals(drawn + 2 * ViewerEditJournalFormat.MOVE_WEIGHT.toInt(), journal.bytes.size)
        assertEquals(0, journal.rewrites)
    }

    @Test
    fun leavingTheEditsDiscardsTheJournal() {
        editing()
        draw(0, 10f to 10f, 40f to 10f)

        editor.discard()

        assertEquals(1, journal.discards)
        assertEquals(0, journal.bytes.size)
    }

    @Test
    fun theJournalIsWrittenOnlyWhileOneIsAttached() {
        val silent = editor(null)
        editing(silent)

        draw(0, 10f to 10f, 40f to 10f, on = silent)

        assertEquals(1, silent.addedMarks(0).size)
        assertEquals(0, journal.bytes.size)
    }

    @Test
    fun manyDroppedRedoStepsCompactTheJournalToTheLiveSteps() {
        editing()
        val long = Array(1_500) { index -> (index % 300).toFloat() + 1f to (index / 300) * 10f + 5f }
        repeat(4) {
            draw(0, *long)
            editor.undo()
            draw(1, 10f to 10f, 20f to 20f)
        }

        assertEquals(2, journal.rewrites)
        assertTrue(journal.bytes.size < 4_096)
        assertEquals(shown(editor), shown(resumed()))
    }

    @Test
    fun aJournalOfAnotherDocumentIsIgnoredAndABrokenHeaderIsUnreadable() {
        editing()
        draw(0, 10f to 10f, 40f to 10f)

        assertSame(ViewerEditJournalRead.None, journal.read("other"))
        val broken = journal.bytes.copyOf().also { it[0] = 0 }
        assertSame(ViewerEditJournalRead.Unreadable, read(broken))
        assertSame(ViewerEditJournalRead.Unreadable, read(ByteArray(0)))
        val changed = journal.bytes.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertSame(ViewerEditJournalRead.Unreadable, read(changed))
    }

    @Test
    fun aCutOffOrDamagedStepKeepsTheStepsBeforeIt() {
        editing()
        draw(0, 10f to 10f, 40f to 10f)
        val first = journal.bytes.size
        draw(0, 10f to 60f, 40f to 60f)
        val second = journal.bytes.size
        draw(1, 10f to 10f, 20f to 20f)

        val cut = read(journal.bytes.copyOf(journal.bytes.size - 3)) as ViewerEditJournalRead.Found
        assertFalse(cut.restore.complete)
        assertEquals(2, cut.restore.actions.size)
        val damaged = journal.bytes.copyOf().also { it[first + 9] = (it[first + 9] + 1).toByte() }
        val stopped = read(damaged) as ViewerEditJournalRead.Found
        assertFalse(stopped.restore.complete)
        assertEquals(1, stopped.restore.actions.size)
        val whole = read(journal.bytes.copyOf(second)) as ViewerEditJournalRead.Found
        assertTrue(whole.restore.complete)
        assertEquals(2, whole.restore.actions.size)
    }

    @Test
    fun stepsOutsideTheDocumentDoNotFit() {
        editing()
        draw(1, 10f to 10f, 20f to 20f)

        val restored = (journal.read() as ViewerEditJournalRead.Found).restore

        assertTrue(restored.fits(2))
        assertFalse(restored.fits(1))
    }

    @Test
    fun theOpenSignaturePadComesBackWithItsStrokesAndWidth() {
        editing()
        editor.openSignaturePad(1)
        val pad = editor.signatures.pad!!
        pad.begin(0.1f, 0.1f, 0.001f)
        pad.extend(0.3f, 0.2f)
        pad.end(0.5f, 0.1f)
        pad.clear()
        pad.begin(0.2f, 0.3f, 0.001f)
        pad.end(0.6f, 0.35f)
        editor.chooseSignatureWidth(3)

        val back = resumed().signatures.pad

        assertNotNull(back)
        assertEquals(1, back!!.slot)
        assertEquals(3, back.widthIndex)
        assertEquals(1, back.strokes.size)
        assertArrayEquals(pad.strokes.single(), back.strokes.single(), 0f)
        editor.signatures.cancelPad()
        assertNull(resumed().signatures.pad)
    }

    @Test
    fun resumingBringsBackThePanelsAndQuestionsAndRewritesTheJournal() {
        editing()
        draw(0, 10f to 10f, 40f to 10f)
        val target = MemoryJournal()
        val next = editor(target)
        next.attach(document)

        next.resume(
            ViewerResume(null, editing = true, paused = true, panel = ViewerEditPanel.COLORS, rowOpen = true, question = ViewerEditQuestion.EXIT, deleting = 0, saving = false),
            (journal.read() as ViewerEditJournalRead.Found).restore
        )

        assertTrue(next.isActive)
        assertTrue(next.paused)
        assertEquals(ViewerEditPanel.COLORS, next.panel)
        assertTrue(next.rowOpen)
        assertEquals(ViewerEditQuestion.EXIT, next.question)
        assertEquals(0, next.signatures.deleting)
        assertEquals(1, target.rewrites)
        assertEquals(shown(editor), shown(resumed(target.read())))
    }

    @Test
    fun formMarksAndTheirChangesComeBackFromTheJournal() {
        editing()
        editor.selectTool(ViewerEditTool.FORM_MARK)
        for ((index, symbol) in ViewerFormSymbol.entries.withIndex()) {
            editor.chooseFormSymbol(symbol)
            assertTrue(editor.tapFormMark(0, ViewerTextPoint(30f + index * 40f, 50f), 300f, 300f, 2f))
            editor.releaseFramed()
        }
        assertFalse(editor.tapFormMark(0, ViewerTextPoint(30f, 50f), 300f, 300f, 2f))
        editor.chooseColor(2)

        val next = resumed()

        assertEquals(
            listOf(ViewerMarkKind.BOX_CHECK, ViewerMarkKind.BOX_DASH, ViewerMarkKind.BOX_DOT, ViewerMarkKind.BOX_X),
            next.addedMarks(0).map { it.kind }
        )
        assertEquals(shown(editor), shown(next))
        next.undo()
        editor.undo()
        assertEquals(shown(editor), shown(next))
    }

    @Test
    fun resumingWithoutAJournalStartsAnEmptyEditor() {
        val next = editor(MemoryJournal())
        next.attach(document)

        next.resume(RESUME, null)

        assertTrue(next.isActive)
        assertFalse(next.state.hasChanges)
    }

    private fun read(bytes: ByteArray, identity: String = IDENTITY): ViewerEditJournalRead =
        ViewerEditJournalFormat.read(ByteArrayInputStream(bytes)::read, identity)

    private class MemoryJournal : ViewerEditJournal {
        private val header = ViewerEditJournalFormat.header(IDENTITY)
        var bytes = ByteArray(0)
        var rewrites = 0
        var discards = 0

        override fun append(record: ViewerEditRecord) {
            if (bytes.isEmpty()) bytes = header
            bytes += ViewerEditJournalFormat.encode(record)
        }

        override fun rewrite(records: List<ViewerEditRecord>) {
            bytes = records.fold(header) { written, record -> written + ViewerEditJournalFormat.encode(record) }
            rewrites++
        }

        override fun discard() {
            bytes = ByteArray(0)
            discards++
        }

        fun read(identity: String = IDENTITY): ViewerEditJournalRead =
            ViewerEditJournalFormat.read(ByteArrayInputStream(bytes)::read, identity)
    }

    private class SignatureStore : ViewerSignatureStore {
        override suspend fun read(slot: Int): ViewerSignature? = if (slot == 0) SIGNATURE else null

        override suspend fun write(slot: Int, signature: ViewerSignature) = Unit

        override suspend fun delete(slot: Int) = Unit
    }

    private class JournalDocument : ViewerDocument, ViewerMarkSource {
        val marks = HashMap<Int, ViewerPageMarks>()

        override val pageCount = 2

        override suspend fun pageMarks(page: Int, blank: Boolean): ViewerPageMarks = marks[page] ?: ViewerPageMarks.NONE

        override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) = Unit

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { 300f }, FloatArray(pageCount) { 300f })

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
        val SIGNATURE = ViewerSignature(2f, listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.3f, 0.9f, 0.2f)))
        val SAVED_LINE = ViewerSavedMark(
            "a", 7, ViewerMarkKind.PEN, 0x000000, 2f,
            ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(10f, 50f, 30f, 50f))
        )
        val RESUME = ViewerResume(null, editing = true, paused = false, panel = null, rowOpen = false, question = null, deleting = null, saving = false)
    }
}
