package hu.reelee81.reepdf.viewer

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

class ViewerEditorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val refreshed = mutableListOf<Set<Int>?>()
    private val editor = ViewerEditor(scope) { refreshed += it }
    private val document = FakeMarkDocument()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun editing(visible: IntRange = 0..0) {
        editor.attach(document)
        editor.onVisible(visible)
        editor.start()
    }

    private fun draw(page: Int, vararg points: Pair<Float, Float>) {
        editor.beginStroke(page, ViewerTextPoint(points[0].first, points[0].second), 1f, 5f)
        for ((x, y) in points.drop(1)) editor.extendStroke(ViewerTextPoint(x, y))
        editor.endStroke()
    }

    private fun erase(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        editor.selectTool(ViewerEditTool.ERASER)
        editor.beginErase(0, ViewerTextPoint(from.first, from.second), 2f)
        editor.extendErase(0, ViewerTextPoint(to.first, to.second))
        editor.endErase()
    }

    private fun savedLine(id: String, stream: Int, x: Float, clip: FloatArray? = null) = ViewerSavedMark(
        id, stream, ViewerMarkKind.PEN, 0x000000, 2f,
        ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(x, 50f, x + 20f, 50f)), clip
    )

    @Test
    fun nothingIsDrawnBeforeEditingStartsAndStartingReadsTheVisiblePages() {
        editor.attach(document)
        editor.onVisible(0..1)

        draw(0, 1f to 1f, 9f to 9f)

        assertTrue(editor.addedMarks(0).isEmpty())
        assertTrue(document.reads.isEmpty())
        editor.start()
        assertTrue(editor.isActive)
        assertEquals(listOf("marks 0 false", "marks 1 false"), document.reads)
    }

    @Test
    fun aPenStrokeBecomesAMarkWithTheChosenColorAndWidth() {
        val stored = mutableListOf<ViewerEditSettings>()
        editor.onSettingsChanged = { stored += it }
        editing()
        editor.chooseColor(2)
        editor.chooseWidth(4)
        editor.chooseWidth(4)

        editor.beginStroke(0, ViewerTextPoint(10f, 10f), 1f, 5f)
        val wet = editor.wet
        editor.extendStroke(ViewerTextPoint(20f, 10f))
        editor.extendStroke(ViewerTextPoint(30f, 15f))
        editor.endStroke()

        assertEquals(ViewerMarkKind.PEN, wet?.kind)
        assertNull(editor.wet)
        val mark = editor.addedMarks(0).single()
        assertEquals(0xDD0000, mark.color)
        assertEquals(10f, mark.width, 0f)
        assertArrayEquals(floatArrayOf(30f, 15f), mark.path.points.copyOfRange(mark.path.points.size - 2, mark.path.points.size), 0f)
        assertEquals(2, stored.size)
        assertTrue(editor.state.canUndo)
        editor.undo()
        assertTrue(editor.addedMarks(0).isEmpty())
        editor.redo()
        assertEquals(1, editor.addedMarks(0).size)
    }

    @Test
    fun theHighlighterFollowsTheTextWhenItStartsOnAWord() {
        document.words = { _, point -> ViewerTextSpan(point, point, FloatArray(0)) }
        editing()
        editor.selectTool(ViewerEditTool.HIGHLIGHTER)

        draw(0, 10f to 15f, 40f to 16f)

        val mark = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.TEXT_HIGHLIGHT, mark.kind)
        assertEquals(0xFFED45, mark.color)
        assertEquals(0f, mark.width, 0f)
        assertArrayEquals(floatArrayOf(10f, 10f, 40f, 10f, 40f, 20f, 10f, 20f), mark.path.points, 0f)
    }

    @Test
    fun theHighlighterDrawsFreelyAwayFromTextAndATapOnTextLeavesNothing() {
        editing()
        editor.selectTool(ViewerEditTool.HIGHLIGHTER)

        draw(0, 10f to 15f, 40f to 30f)
        document.words = { _, point -> ViewerTextSpan(point, point, FloatArray(0)) }
        draw(0, 50f to 50f)

        val mark = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.HIGHLIGHTER, mark.kind)
        assertEquals(24f, mark.width, 0f)
    }

    @Test
    fun anUnderlineFollowsTheTextJustBelowItsBaselineInItsOwnColor() {
        document.words = { _, point -> ViewerTextSpan(point, point, FloatArray(0)) }
        editing()
        editor.selectTool(ViewerEditTool.UNDERLINE)

        editor.beginStroke(0, ViewerTextPoint(10f, 15f), 1f, 5f)
        val before = editor.wet
        editor.extendStroke(ViewerTextPoint(40f, 16f))
        val wet = editor.wet
        editor.endStroke()

        assertNull(before)
        assertEquals(ViewerMarkKind.UNDERLINE, wet?.kind)
        val mark = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.UNDERLINE, mark.kind)
        assertEquals(0x35C369, mark.color)
        assertEquals(0f, mark.width, 0f)
        assertArrayEquals(floatArrayOf(10f, 31.85f, 40f, 31.85f, 40f, 30.95f, 10f, 30.95f), mark.path.points, 0.001f)
        editor.undo()
        assertTrue(editor.addedMarks(0).isEmpty())
    }

    @Test
    fun aStrikethroughCrossesTheLettersAndLeavesNothingAwayFromText() {
        editing()
        editor.selectTool(ViewerEditTool.STRIKETHROUGH)

        editor.beginStroke(0, ViewerTextPoint(10f, 15f), 1f, 5f)
        editor.extendStroke(ViewerTextPoint(40f, 16f))
        val away = editor.wet
        editor.endStroke()
        document.words = { _, point -> ViewerTextSpan(point, point, FloatArray(0)) }
        draw(0, 50f to 50f)
        val tapped = editor.addedMarks(0).isEmpty()
        draw(0, 10f to 15f, 40f to 16f)

        assertNull(away)
        assertTrue(tapped)
        val mark = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.STRIKETHROUGH, mark.kind)
        assertEquals(0xDD0000, mark.color)
        assertArrayEquals(floatArrayOf(10f, 27.85f, 40f, 27.85f, 40f, 26.95f, 10f, 26.95f), mark.path.points, 0.001f)
    }

    @Test
    fun theUnderlineOpensItsStylesTheStrikethroughNothingAndBothKeepTheirColors() {
        editing()

        editor.tapTool(ViewerEditTool.UNDERLINE)
        editor.tapTool(ViewerEditTool.UNDERLINE)
        val secondTap = editor.panel
        editor.tapTool(ViewerEditTool.UNDERLINE)
        val thirdTap = editor.panel
        editor.tapSwatch()
        val swatch = editor.panel
        editor.chooseColor(5)
        editor.tapTool(ViewerEditTool.STRIKETHROUGH)
        editor.tapTool(ViewerEditTool.STRIKETHROUGH)
        val strikethroughTap = editor.panel
        editor.chooseColor(7)

        assertEquals(ViewerEditPanel.STYLE, secondTap)
        assertNull(thirdTap)
        assertNull(strikethroughTap)
        assertEquals(ViewerEditPanel.COLORS, swatch)
        assertEquals(5, editor.settings.underlineColor)
        assertEquals(7, editor.settings.strikethroughColor)
        assertEquals(0, editor.settings.penColor)
        assertEquals(ViewerEditTool.STRIKETHROUGH, editor.settings.drawingTool)
    }

    @Test
    fun aWavyUnderlineIsRememberedAndDrawnAsAZigzagWhileTheStrikethroughStaysStraight() {
        val stored = mutableListOf<ViewerEditSettings>()
        editor.onSettingsChanged = { stored += it }
        document.words = { _, point -> ViewerTextSpan(point, point, FloatArray(0)) }
        editing()
        editor.selectTool(ViewerEditTool.UNDERLINE)

        editor.chooseUnderlineStyle(ViewerUnderlineStyle.WAVY)
        draw(0, 10f to 15f, 40f to 16f)
        editor.selectTool(ViewerEditTool.STRIKETHROUGH)
        draw(0, 10f to 15f, 40f to 16f)

        assertEquals(ViewerUnderlineStyle.WAVY, stored.last().underlineStyle)
        val (underline, strikethrough) = editor.addedMarks(0)
        assertEquals(ViewerMarkKind.UNDERLINE, underline.kind)
        assertTrue(underline.path.points.size > 8)
        assertEquals(ViewerMarkKind.STRIKETHROUGH, strikethrough.kind)
        assertEquals(8, strikethrough.path.points.size)
    }

    @Test
    fun theEraserRemovesNewMarksInOneUndoableStep() {
        editing()
        draw(0, 0f to 0f, 100f to 0f)
        draw(0, 0f to 30f, 100f to 30f)
        draw(0, 0f to 90f, 100f to 90f)

        erase(50f to -5f, 50f to 35f)

        assertEquals(1, editor.addedMarks(0).size)
        editor.undo()
        assertEquals(3, editor.addedMarks(0).size)
        assertTrue(document.edits.isEmpty())
    }

    @Test
    fun erasingASavedMarkEditsEveryStreamItIsInAndRefreshesThePage() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f), savedLine("a", 8, 10f), savedLine("b", 7, 60f)), intArrayOf(7, 8))
        editing()

        erase(15f to 50f, 16f to 50f)

        val erased = ViewerStreamEdit(false, setOf("a"))
        assertEquals(listOf(mapOf(7 to erased, 8 to erased)), document.edits)
        assertEquals(listOf<Set<Int>?>(setOf(0)), refreshed)
        assertEquals(mapOf(7 to setOf("a"), 8 to setOf("a")), editor.plan().erased)
        editor.undo()
        assertEquals(mapOf(7 to ViewerStreamEdit.ORIGINAL, 8 to ViewerStreamEdit.ORIGINAL), document.edits.last())
        editor.redo()
        assertEquals(mapOf(7 to erased, 8 to erased), document.edits.last())
    }

    @Test
    fun aSavedMarkIsErasedOnlyWhereItCanBeSeen() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f, floatArrayOf(5f, 40f, 20f, 60f))), intArrayOf(7))
        editing()

        erase(27f to 50f, 29f to 50f)

        assertTrue(document.edits.isEmpty())
        assertFalse(editor.state.canUndo)

        erase(21f to 50f, 21f to 50f)

        assertEquals(mapOf(7 to ViewerStreamEdit(false, setOf("a"))), document.edits.single())
    }

    @Test
    fun hittingOneVisiblePieceErasesEveryPieceOfThatMarkOnThePage() {
        document.marks[0] = ViewerPageMarks(
            listOf(savedLine("a", 7, 10f, floatArrayOf(5f, 40f, 20f, 60f)), savedLine("a", 8, 10f, floatArrayOf(20f, 40f, 40f, 60f))),
            intArrayOf(7, 8)
        )
        editing()

        erase(27f to 50f, 27f to 50f)

        assertEquals(mapOf(7 to ViewerStreamEdit(false, setOf("a")), 8 to ViewerStreamEdit(false, setOf("a"))), document.edits.single())
    }

    @Test
    fun aCancelledEraseBringsTheSavedMarkBackWithoutAStep() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        editing()
        editor.selectTool(ViewerEditTool.ERASER)

        editor.beginErase(0, ViewerTextPoint(15f, 50f), 2f)
        editor.cancelGesture()

        assertEquals(2, document.edits.size)
        assertEquals(mapOf(7 to ViewerStreamEdit.ORIGINAL), document.edits.last())
        assertFalse(editor.state.canUndo)
    }

    @Test
    fun erasingTheVisiblePagesBlanksOnlyTheirStreams() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        document.marks[1] = ViewerPageMarks(emptyList(), intArrayOf(9))
        editing(0..1)
        draw(1, 5f to 5f, 25f to 5f)

        editor.eraseVisible(setOf(0))

        assertEquals(mapOf(7 to ViewerStreamEdit.BLANK), document.edits.single())
        assertEquals(setOf(0), refreshed.last())
        assertEquals(1, editor.addedMarks(1).size)
        assertEquals(setOf(0), editor.plan().blankPages)
        editor.undo()
        assertEquals(mapOf(7 to ViewerStreamEdit.ORIGINAL), document.edits.last())
    }

    @Test
    fun erasingPagesWithNothingOnThemIsNoStep() {
        editing(0..1)

        editor.eraseVisible(setOf(0, 1))

        assertFalse(editor.state.canUndo)
        assertTrue(document.edits.isEmpty())
    }

    @Test
    fun marksDrawnAfterErasingThePageCanBeErasedAgain() {
        editing()
        draw(0, 0f to 0f, 100f to 0f)
        editor.eraseVisible(setOf(0))
        draw(0, 0f to 30f, 100f to 30f)

        editor.eraseVisible(setOf(0))
        editor.eraseVisible(setOf(0))

        assertTrue(editor.addedMarks(0).isEmpty())
        editor.undo()
        assertEquals(1, editor.addedMarks(0).size)
    }

    @Test
    fun marksDrawnAfterErasingEveryPageCanBeErasedAgain() {
        editing()
        draw(0, 0f to 0f, 100f to 0f)
        editor.eraseAll()
        draw(0, 0f to 30f, 100f to 30f)

        editor.eraseAll()
        editor.eraseAll()

        assertTrue(editor.addedMarks(0).isEmpty())
        editor.undo()
        assertEquals(1, editor.addedMarks(0).size)
    }

    @Test
    fun erasingEveryPageRefreshesAllAndBlanksPagesAsTheyAppear() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        document.marks[5] = ViewerPageMarks(listOf(savedLine("c", 11, 10f)), intArrayOf(11))
        editing()

        editor.eraseAll()
        editor.onVisible(5..5)

        assertEquals(mapOf(7 to ViewerStreamEdit.BLANK), document.edits.single())
        assertEquals(listOf("marks 0 false", "marks 5 true"), document.reads)
        assertEquals(listOf(null, setOf(5)), refreshed)
        assertTrue(editor.plan().blankAll)
        editor.undo()
        assertEquals(mapOf(7 to ViewerStreamEdit.ORIGINAL, 11 to ViewerStreamEdit.ORIGINAL), document.edits.last())
        assertNull(refreshed.last())
    }

    @Test
    fun discardingRestoresTheStreamsAndLeavesEditing() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        editing()
        erase(15f to 50f, 16f to 50f)

        editor.discard()

        assertFalse(editor.isActive)
        assertEquals(mapOf(7 to ViewerStreamEdit.ORIGINAL), document.edits.last())
        assertTrue(editor.plan().isEmpty)
        assertFalse(editor.state.canUndo)
    }

    @Test
    fun aFailedEditIsReportedAndNothingIsRefreshed() {
        val failures = mutableListOf<String>()
        editor.onFailure = { failures += it }
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        document.failEdits = true
        editing()

        erase(15f to 50f, 16f to 50f)

        assertEquals(listOf("broken"), failures)
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun anotherDocumentStartsFromNothing() {
        editing()
        draw(0, 1f to 1f, 9f to 9f)
        editor.beginStroke(0, ViewerTextPoint(1f, 1f), 1f, 5f)

        editor.attach(FakeMarkDocument())

        assertFalse(editor.isActive)
        assertNull(editor.wet)
        assertTrue(editor.addedMarks(0).isEmpty())
        assertFalse(editor.state.canUndo)
    }

    @Test
    fun savingEndsEditingWithoutTouchingTheStreams() {
        document.marks[0] = ViewerPageMarks(listOf(savedLine("a", 7, 10f)), intArrayOf(7))
        editing()
        erase(15f to 50f, 16f to 50f)
        val edits = document.edits.size

        editor.saved()

        assertFalse(editor.isActive)
        assertEquals(edits, document.edits.size)
        assertTrue(editor.plan().isEmpty)
    }

    @Test
    fun theFirstTapChoosesAToolAndTheSecondOpensItsPanel() {
        editing()

        editor.tapTool(ViewerEditTool.PEN)
        val penWidths = editor.panel
        editor.tapTool(ViewerEditTool.PEN)
        val closed = editor.panel
        editor.tapSwatch()
        val colors = editor.panel
        editor.tapTool(ViewerEditTool.HIGHLIGHTER)

        assertEquals(ViewerEditPanel.WIDTH, penWidths)
        assertNull(closed)
        assertEquals(ViewerEditPanel.COLORS, colors)
        assertNull(editor.panel)
        assertEquals(ViewerEditTool.HIGHLIGHTER, editor.settings.tool)
    }

    @Test
    fun theToolRowStaysOpenWhileChoosingAndSettingTheWidth() {
        editing()

        editor.tapGroup()
        val opened = editor.rowOpen
        editor.tapTool(ViewerEditTool.HIGHLIGHTER)
        val stillOpen = editor.rowOpen
        val chosenPanel = editor.panel
        editor.tapTool(ViewerEditTool.HIGHLIGHTER)
        val widths = editor.panel
        val rowWithWidths = editor.rowOpen
        editor.tapGroup()

        assertTrue(opened)
        assertTrue(stillOpen)
        assertNull(chosenPanel)
        assertEquals(ViewerEditTool.HIGHLIGHTER, editor.settings.tool)
        assertEquals(ViewerEditPanel.WIDTH, widths)
        assertTrue(rowWithWidths)
        assertFalse(editor.rowOpen)
        assertNull(editor.panel)
    }

    @Test
    fun theEraserTheSwatchAndOutsideTouchesCloseTheToolRow() {
        editing()

        editor.tapGroup()
        editor.tapTool(ViewerEditTool.ERASER)
        val afterEraser = editor.rowOpen
        editor.tapGroup()
        val toolFromEraser = editor.settings.tool
        val rowFromEraser = editor.rowOpen
        editor.tapGroup()
        val secondTap = editor.rowOpen
        editor.tapSwatch()
        val afterSwatch = editor.rowOpen
        editor.tapGroup()
        editor.closePanel()

        assertFalse(afterEraser)
        assertEquals(ViewerEditTool.PEN, toolFromEraser)
        assertFalse(rowFromEraser)
        assertTrue(secondTap)
        assertFalse(afterSwatch)
        assertFalse(editor.rowOpen)
        assertNull(editor.panel)
    }

    @Test
    fun scrollingPausesDrawingUntilTheUserTurnsItOff() {
        editing()
        editor.tapSwatch()

        editor.togglePause()
        val panel = editor.panel
        draw(0, 1f to 1f, 9f to 9f)
        editor.tapTool(ViewerEditTool.ERASER)
        editor.tapGroup()
        val stillPaused = editor.paused
        editor.togglePause()

        assertNull(panel)
        assertTrue(editor.addedMarks(0).isEmpty())
        assertTrue(stillPaused)
        assertEquals(ViewerEditTool.PEN, editor.settings.tool)
        assertFalse(editor.paused)
    }

    @Test
    fun theEraserOpensNothingAndASecondTapOnTextOpensItsWeights() {
        editing()

        editor.tapTool(ViewerEditTool.TEXT)
        editor.tapTool(ViewerEditTool.TEXT)
        val tool = editor.settings.tool
        val textPanel = editor.panel
        editor.tapTool(ViewerEditTool.ERASER)
        editor.tapTool(ViewerEditTool.ERASER)
        editor.tapSwatch()

        assertEquals(ViewerEditTool.TEXT, tool)
        assertEquals(ViewerEditPanel.WIDTH, textPanel)
        assertEquals(ViewerEditTool.ERASER, editor.settings.tool)
        assertNull(editor.panel)
    }

    @Test
    fun restoredSettingsAreCheckedAndNotStoredAgain() {
        val stored = mutableListOf<ViewerEditSettings>()
        editor.onSettingsChanged = { stored += it }

        editor.restore(ViewerEditSettings(tool = ViewerEditTool.TEXT, penColor = 50, undoPanelTop = true))
        editor.placeUndoPanel(true)
        editor.selectTool(ViewerEditTool.HIGHLIGHTER)

        assertEquals(ViewerEditTool.HIGHLIGHTER, editor.settings.tool)
        assertEquals(0, editor.settings.penColor)
        assertEquals(listOf(ViewerEditSettings(tool = ViewerEditTool.HIGHLIGHTER, drawingTool = ViewerEditTool.HIGHLIGHTER, undoPanelTop = true)), stored)
        assertNotNull(editor.settings)
    }

    private fun signatureReady(slot: Int = 0) {
        val store = object : ViewerSignatureStore {
            override suspend fun read(slot: Int): ViewerSignature? = if (slot == 0) SIGNATURE else null
            override suspend fun write(slot: Int, signature: ViewerSignature) = Unit
            override suspend fun delete(slot: Int) = Unit
        }
        editor.signatures.attach(store)
        editing()
        editor.selectTool(ViewerEditTool.SIGNATURE)
        editor.chooseSignature(slot)
    }

    @Test
    fun aTapPlacesTheChosenSignatureFramedInItsColorAndATapBesideItReleasesIt() {
        signatureReady()
        editor.chooseColor(2)

        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)

        val placed = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.SIGNATURE, placed.kind)
        assertEquals(0xDD0000, placed.color)
        assertSame(placed, editor.framed?.mark)
        val box = editor.framed!!.box
        assertTrue(box[0] >= 0f && box[2] <= 100f && box[1] >= 0f && box[3] <= 100f)
        assertEquals(50f, (box[1] + box[3]) / 2, 0.01f)
        editor.tapSignature(0, ViewerTextPoint(5f, 5f), 100f, 100f, 2f)
        assertNull(editor.framed)
        assertEquals(1, editor.addedMarks(0).size)
    }

    @Test
    fun withoutAChosenSignatureATapOpensTheSignaturePanel() {
        editor.signatures.attach(null)
        editing()
        editor.selectTool(ViewerEditTool.SIGNATURE)

        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)

        assertEquals(ViewerEditPanel.SIGNATURES, editor.panel)
        assertTrue(editor.addedMarks(0).isEmpty())
    }

    @Test
    fun movingAndResizingAreUndoneOneStepAtATime() {
        signatureReady()
        editor.tapSignature(0, ViewerTextPoint(150f, 150f), 300f, 300f, 2f)
        val placed = editor.framed!!
        val box = placed.box
        assertEquals(ViewerSignature.PAD_WIDTH_ON_PAGE * (SIGNATURE.bounds()[2] - SIGNATURE.bounds()[0]), box[2] - box[0], 0.01f)

        assertTrue(editor.grabSignature(0, ViewerTextPoint(box[0] + 1f, box[1] + 1f), 3f, 1f, 0f))
        assertTrue(editor.addedMarks(0).isEmpty())
        editor.dragSignature(ViewerTextPoint(box[0] - 200f, box[1] + 6f), 300f, 300f)
        editor.endSignatureDrag()
        val moved = editor.framed!!.box
        assertEquals(0f, moved[0], 0.01f)
        assertEquals(box[1] + 5f, moved[1], 0.01f)
        assertTrue(editor.grabSignature(0, ViewerTextPoint(moved[2], moved[3]), 3f, 1f, 0f))
        editor.dragSignature(ViewerTextPoint(moved[2] + 1000f, moved[3] + 1000f), 300f, 300f)
        editor.endSignatureDrag()
        val grown = editor.framed!!.box
        assertEquals(0f, grown[0], 0.01f)
        assertEquals(300f, grown[2], 0.01f)
        assertTrue(grown[3] <= 300.01f)
        assertEquals((moved[2] - moved[0]) / (moved[3] - moved[1]), (grown[2] - grown[0]) / (grown[3] - grown[1]), 0.001f)
        assertEquals(1, editor.addedMarks(0).size)

        editor.undo()
        assertNull(editor.framed)
        assertEquals(moved.toList(), ViewerSignaturePlacement.box(editor.addedMarks(0).single().path, editor.addedMarks(0).single().width).toList())
        editor.undo()
        assertSame(placed.mark, editor.addedMarks(0).single())
        editor.undo()
        assertTrue(editor.addedMarks(0).isEmpty())
    }

    @Test
    fun theHandleSitsOnTheFrameOutsideTheSignatureAndGrabbingItDoesNotJump() {
        signatureReady()
        editor.tapSignature(0, ViewerTextPoint(70f, 150f), 300f, 300f, 2f)
        val box = editor.framed!!.box

        assertFalse(editor.grabSignature(0, ViewerTextPoint(box[2] + 4f, box[3] + 4f), 1f, 1f, 0f))
        assertTrue(editor.grabSignature(0, ViewerTextPoint(box[2] + 4f, box[3] + 4f), 1f, 1f, 4f))
        editor.dragSignature(ViewerTextPoint(box[2] + 4f, box[3] + 4f), 300f, 300f)
        assertEquals(box.toList(), ViewerSignaturePlacement.box(editor.movedSignature!!.path, editor.movedSignature!!.width).toList())
        editor.dragSignature(ViewerTextPoint(box[2] + 4f + (box[2] - box[0]), box[3] + 4f + (box[3] - box[1])), 300f, 300f)
        editor.endSignatureDrag()

        val doubled = editor.framed!!.box
        assertEquals(box[0], doubled[0], 0.01f)
        assertEquals(2 * (box[2] - box[0]), doubled[2] - doubled[0], 0.01f)
    }

    @Test
    fun aGrabWithoutMovingChangesNothingAndAPinchCancelsTheDrag() {
        signatureReady()
        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)
        val placed = editor.framed!!

        assertTrue(editor.grabSignature(0, ViewerTextPoint(50f, 50f), 3f, 1f, 0f))
        editor.endSignatureDrag()
        assertTrue(editor.grabSignature(0, ViewerTextPoint(50f, 50f), 3f, 1f, 0f))
        editor.dragSignature(ViewerTextPoint(60f, 60f), 100f, 100f)
        editor.cancelGesture()

        assertSame(placed, editor.framed)
        assertSame(placed.mark, editor.addedMarks(0).single())
        assertNull(editor.movedSignature)
        assertFalse(editor.grabSignature(0, ViewerTextPoint(1f, 1f), 3f, 1f, 0f))
        assertFalse(editor.grabSignature(1, ViewerTextPoint(50f, 50f), 3f, 1f, 0f))
    }

    @Test
    fun aSignatureDraggedBackToWhereItStartedMakesNoStep() {
        signatureReady()
        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)
        val placed = editor.framed!!

        assertTrue(editor.grabSignature(0, ViewerTextPoint(50f, 50f), 3f, 1f, 0f))
        editor.dragSignature(ViewerTextPoint(60f, 60f), 100f, 100f)
        editor.dragSignature(ViewerTextPoint(50f, 50f), 100f, 100f)
        editor.endSignatureDrag()

        assertSame(placed, editor.framed)
        assertSame(placed.mark, editor.addedMarks(0).single())
        editor.undo()
        assertTrue(editor.addedMarks(0).isEmpty())
    }

    @Test
    fun aSavedSignatureIsFramedByATapAndMovingItErasesTheSavedOneAndAddsItsCopy() {
        val saved = ViewerSavedMark(
            "s", 7, ViewerMarkKind.SIGNATURE, 0x3D5FEC, 2f,
            ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(20f, 20f, 40f, 30f))
        )
        document.marks[0] = ViewerPageMarks(listOf(saved, savedLine("p", 7, 60f)), intArrayOf(7))
        signatureReady()

        editor.tapSignature(0, ViewerTextPoint(30f, 25f), 100f, 100f, 2f)
        val framed = editor.framed!!
        assertEquals(0x3D5FEC, framed.mark.color)
        assertTrue(editor.addedMarks(0).isEmpty())
        assertTrue(editor.grabSignature(0, ViewerTextPoint(30f, 25f), 3f, 1f, 0f))
        assertEquals(setOf("s"), document.edits.last().getValue(7).erased)
        editor.dragSignature(ViewerTextPoint(40f, 35f), 100f, 100f)
        editor.endSignatureDrag()

        val copy = editor.addedMarks(0).single()
        assertEquals(ViewerMarkKind.SIGNATURE, copy.kind)
        assertEquals(0x3D5FEC, copy.color)
        assertArrayEquals(floatArrayOf(30f, 30f, 50f, 40f), copy.path.points, 0.001f)
        assertEquals(setOf("s"), editor.plan().erased[7])
        editor.undo()
        assertTrue(editor.addedMarks(0).isEmpty())
        assertTrue(editor.plan().erased.isEmpty())
    }

    @Test
    fun choosingAnotherToolOrUndoingReleasesTheFrame() {
        signatureReady()
        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)

        editor.selectTool(ViewerEditTool.PEN)

        assertNull(editor.framed)
        editor.selectTool(ViewerEditTool.SIGNATURE)
        editor.tapSignature(0, ViewerTextPoint(50f, 50f), 100f, 100f, 2f)
        assertNotNull(editor.framed)
        editor.tapSwatch()
        assertNull(editor.framed)
    }

    private class FakeMarkDocument : ViewerDocument, ViewerMarkSource {
        val marks = HashMap<Int, ViewerPageMarks>()
        val reads = mutableListOf<String>()
        val edits = mutableListOf<Map<Int, ViewerStreamEdit>>()
        var failEdits = false
        var words: (Int, ViewerTextPoint) -> ViewerTextSpan? = { _, _ -> null }

        override val pageCount = 10

        override suspend fun pageMarks(page: Int, blank: Boolean): ViewerPageMarks {
            reads += "marks $page $blank"
            return marks[page] ?: ViewerPageMarks.NONE
        }

        override suspend fun editStreams(edits: Map<Int, ViewerStreamEdit>) {
            if (failEdits) throw IllegalStateException("broken")
            this.edits += edits
        }

        override suspend fun pageSizes() = ViewerPageSizes(FloatArray(pageCount) { 100f }, FloatArray(pageCount) { 100f })

        override suspend fun render(page: Int, zoom: Float, left: Int, top: Int, width: Int, height: Int) = ViewerRender.Aborted

        override suspend fun renderTiles(tiles: List<ViewerTile>) = ViewerTilesRender.Aborted

        override fun release(image: ViewerImage) = Unit

        override fun trimMemory() = Unit

        override fun close() = Unit

        override suspend fun search(page: Int, query: String) = ViewerTextHits.NONE

        override suspend fun count(page: Int, query: String) = 0

        override fun releaseCount() = Unit

        override suspend fun word(page: Int, point: ViewerTextPoint, tolerance: Float) = words(page, point)

        override suspend fun caret(page: Int, point: ViewerTextPoint): ViewerTextPoint = point

        override suspend fun highlight(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) =
            ViewerTextHighlight(floatArrayOf(from?.x ?: 0f, 10f, to?.x ?: 0f, 20f), null, null)

        override suspend fun baselines(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) =
            floatArrayOf(from?.x ?: 0f, 30f, to?.x ?: 0f, 30f, 0f, -1f, 10f, 0f)

        override suspend fun copy(page: Int, from: ViewerTextPoint?, to: ViewerTextPoint?) = ""

        override suspend fun text(page: Int) = ""
    }

    private companion object {
        val SIGNATURE = ViewerSignature(2f, listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.3f, 0.9f, 0.2f)))
    }
}
