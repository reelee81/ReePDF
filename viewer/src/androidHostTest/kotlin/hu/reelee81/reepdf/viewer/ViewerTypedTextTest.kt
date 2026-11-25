package hu.reelee81.reepdf.viewer

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTypedTextTest {
    private val fonts = ViewerTextFonts { weight -> File("../app/src/main/res/font/roboto_${FILES.getValue(weight)}.ttf").readBytes() }
    private val regular = fonts.font(400)

    @Test
    fun robotoAdvancesAreTheThousandthsThePdfWriterUses() {
        for ((weight, expected) in ITEXT_SUMS) {
            val font = fonts.font(weight)
            assertEquals("weight $weight", expected, SAMPLE.codePoints().toArray().sumOf { font.advance(it) })
            assertArrayEquals("weight $weight", ITEXT_EACH.getValue(weight), intArrayOf('i'.code, 'W'.code, 'ő'.code, '€'.code, ' '.code, 'Ж'.code).map(font::advance).toIntArray())
            assertEquals(2048, font.unitsPerEm)
            assertEquals(ViewerMarkText.ASCENT, font.ascender / font.unitsPerEm.toFloat(), 0f)
            assertEquals(ViewerMarkText.LINE_HEIGHT, (font.ascender - font.descender + font.lineGap) / font.unitsPerEm.toFloat(), 0f)
        }
        for (codePoint in intArrayOf('ő'.code, 'ű'.code, 'Ő'.code, 'Ű'.code, '–'.code, '€'.code, 'ß'.code, 'Ж'.code, 'Ω'.code)) assertTrue(regular.has(codePoint))
        for (codePoint in intArrayOf(0x4E2D, 0x05D0, 0x0627, 0x1F600, 0x2764, '\t'.code, '\n'.code)) assertFalse(regular.has(codePoint))
    }

    @Test
    fun typedTextJoinsSplitAccentsAndLeavesOutLettersTheFontLacks() {
        assertEquals("ő A\nB", ViewerTypedLayout.accepted(regular, "ő 中😀\tA\r\nB"))
        assertEquals(6000, ViewerTypedLayout.accepted(regular, "a".repeat(6000)).length)
    }

    @Test
    fun aFullBoxCutsOnlyWhatIsAddedAndKeepsItsEnd() {
        val full = "a".repeat(ViewerMarkText.MAX_CHARS - 10) + "0123456789"
        val pasted = full.substring(0, 100) + "x".repeat(50) + full.substring(100)
        val almost = full.substring(3)

        val (text, caret) = ViewerTypedLayout.limited(almost, almost.substring(0, 100) + "x".repeat(50) + almost.substring(100), 150)
        assertEquals(ViewerMarkText.MAX_CHARS, text.length)
        assertTrue(text.endsWith("0123456789"))
        assertEquals("xxx", text.substring(100, 103))
        assertEquals('a', text[103])
        assertEquals(103, caret)

        val (inMiddle, middleCaret) = ViewerTypedLayout.limited(full, pasted, 150)
        assertEquals(full, inMiddle)
        assertEquals(100, middleCaret)
        val (after, afterCaret) = ViewerTypedLayout.limited(full, pasted, pasted.length)
        assertEquals(full, after)
        assertEquals(full.length, afterCaret)
        val (short, shortCaret) = ViewerTypedLayout.limited("shor", "short", 2)
        assertEquals("short", short)
        assertEquals(2, shortCaret)
    }

    @Test
    fun theCaretSpansItsWholeLineAlongTheText() {
        val upright = ViewerMarkText.upright("Fő\nút", 400, 10f, 50f, 100f)
        val line = ViewerMarkText.LINE_HEIGHT * 10f
        val x = 50f + ViewerTypedLayout.lines(regular, "Fő\nút")[1].offsets[1] * 10f

        val (top, bottom) = ViewerTypedLayout.caretLine(regular, upright, 4)
        assertEquals(x, top.x, 0.0001f)
        assertEquals(100f + line, top.y, 0.0001f)
        assertEquals(x, bottom.x, 0.0001f)
        assertEquals(100f + line * 2, bottom.y, 0.0001f)

        val turned = ViewerMarkText("Fő", 400, 10f, floatArrayOf(0f, -1f, -1f, 0f, 120f, 200f))
        val (from, to) = ViewerTypedLayout.caretLine(regular, turned, 0)
        assertEquals(120f, from.x, 0.0001f)
        assertEquals(200f, from.y, 0.0001f)
        assertEquals(120f + line, to.x, 0.0001f)
        assertEquals(200f, to.y, 0.0001f)
    }

    @Test
    fun linesAndCaretsFollowTheAdvances() {
        val lines = ViewerTypedLayout.lines(regular, "Fő\n\nút")

        assertEquals(listOf("Fő", "", "út"), lines.map { it.text })
        assertEquals(listOf(0, 3, 4), lines.map { it.start })
        val f = regular.advance('F'.code) / 1000f
        assertArrayEquals(floatArrayOf(0f, f, f + regular.advance('ő'.code) / 1000f), lines[0].offsets, 0f)
        assertEquals(0f, lines[1].width, 0f)
        val mark = ViewerMarkText.upright("Fő\n\nút", 400, 10f, 50f, 100f)
        val line = ViewerMarkText.LINE_HEIGHT * 10f
        assertEquals(1, ViewerTypedLayout.caretAt(regular, mark, ViewerTextPoint(50f + f * 10f + 0.1f, 101f)))
        assertEquals(3, ViewerTypedLayout.caretAt(regular, mark, ViewerTextPoint(80f, 100f + line * 1.5f)))
        assertEquals(6, ViewerTypedLayout.caretAt(regular, mark, ViewerTextPoint(200f, 100f + line * 9f)))
        assertEquals(4, ViewerTypedLayout.caretAt(regular, mark, ViewerTextPoint(0f, 100f + line * 2.5f)))
        val (firstRow, firstX) = ViewerTypedLayout.caret(regular, "Fő\n\nút", 1)
        assertEquals(0, firstRow)
        assertEquals(f, firstX, 0f)
        val (emptyRow, emptyX) = ViewerTypedLayout.caret(regular, "Fő\n\nút", 4)
        assertEquals(2, emptyRow)
        assertEquals(0f, emptyX, 0f)
    }

    @Test
    fun aNewTextIsPlacedWithItsFirstLineCenteredOnTheTapAndStaysOnThePage() {
        val placed = ViewerTypedPlacement.placed(0, 0x202FB0, "Smith", 400, 12f, ViewerTextPoint(100f, 200f), regular, 300f, 400f)
        val text = placed.typed

        assertEquals(ViewerMarkKind.TYPED, placed.kind)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, -1f, 100f, 200f - ViewerMarkText.LINE_HEIGHT * 6f), text.matrix, 0.0001f)
        val width = ViewerTypedLayout.widths(regular, "Smith")[0] * 12f
        assertArrayEquals(floatArrayOf(100f, text.matrix[5], 100f + width, text.matrix[5] + ViewerMarkText.LINE_HEIGHT * 12f), box(placed.path), 0.001f)

        val atEdge = ViewerTypedPlacement.placed(0, 0, "John Smith", 400, 12f, ViewerTextPoint(280f, 395f), regular, 300f, 400f)
        val frame = ViewerTypedPlacement.frame(atEdge.typed, regular)
        assertEquals(300f, frame[2], 0.001f)
        assertEquals(400f, frame[3], 0.001f)
        assertEquals(12f, atEdge.typed.size, 0f)

        val wide = ViewerTypedPlacement.placed(0, 0, "A rather long line ".repeat(4), 400, 12f, ViewerTextPoint(50f, 50f), regular, 300f, 400f)
        val wideFrame = ViewerTypedPlacement.frame(wide.typed, regular)
        assertEquals(0f, wideFrame[0], 0.001f)
        assertEquals(300f, wideFrame[2], 0.01f)
        assertTrue(wide.typed.size < 12f)

        val endless = ViewerTypedPlacement.placed(0, 0, "x".repeat(5000), 400, 12f, ViewerTextPoint(50f, 50f), regular, 300f, 400f)
        assertEquals(ViewerMarkText.MIN_SIZE, endless.typed.size, 0.0001f)
        assertEquals(0f, ViewerTypedPlacement.frame(endless.typed, regular)[0], 0.001f)
    }

    @Test
    fun movingAndResizingKeepTheTextOnThePageAndAtLeastFourPointsHigh() {
        val start = ViewerTypedPlacement.placed(0, 0, "Label\nsecond line", 700, 10f, ViewerTextPoint(100f, 100f), fonts.font(700), 300f, 400f)
        val bold = fonts.font(700)
        val before = ViewerTypedPlacement.frame(start.typed, bold)

        val moved = ViewerTypedPlacement.moved(start, bold, 1000f, -1000f, 300f, 400f)
        val movedFrame = ViewerTypedPlacement.frame(moved.typed, bold)
        assertEquals(300f, movedFrame[2], 0.001f)
        assertEquals(0f, movedFrame[1], 0.001f)

        val grown = ViewerTypedPlacement.resized(start, bold, ViewerTextPoint(before[0] + (before[2] - before[0]) * 2, before[1] + (before[3] - before[1]) * 2), 300f, 400f)
        assertEquals(20f, grown.typed.size, 0.01f)
        assertArrayEquals(floatArrayOf(before[0], before[1]), ViewerTypedPlacement.frame(grown.typed, bold).copyOf(2), 0.001f)

        val shrunk = ViewerTypedPlacement.resized(start, bold, ViewerTextPoint(before[0], before[1]), 300f, 400f)
        assertEquals(ViewerMarkText.MIN_SIZE, shrunk.typed.size, 0.0001f)

        val huge = ViewerTypedPlacement.resized(start, bold, ViewerTextPoint(5000f, 5000f), 300f, 400f)
        val hugeFrame = ViewerTypedPlacement.frame(huge.typed, bold)
        assertTrue(hugeFrame[2] <= 300.01f && hugeFrame[3] <= 400.01f)
        assertTrue(hugeFrame[2] >= 299.99f || hugeFrame[3] >= 399.99f)

        val poster = ViewerTypedPlacement.placed(0, 0, "A", 400, 1000f, ViewerTextPoint(10f, 2000f), regular, 14400f, 14400f)
        val giant = ViewerTypedPlacement.resized(poster, regular, ViewerTextPoint(14400f, 14400f), 14400f, 14400f)
        assertEquals(ViewerMarkText.MAX_SIZE, giant.typed.size, 0.01f)
    }

    @Test
    fun aTurnedTextGrowsFromItsVisibleTopLeftCorner() {
        val turned = ViewerTypedPlacement.mark(0, 0, ViewerMarkText("Top", 400, 10f, floatArrayOf(0f, -1f, -1f, 0f, 120f, 200f)), regular)
        val frame = ViewerTypedPlacement.shown(turned.typed, regular, 300f, 400f)
        assertTrue(frame[1] < ViewerTypedPlacement.frame(turned.typed, regular)[1])

        val grown = ViewerTypedPlacement.resized(turned, regular, ViewerTextPoint(frame[0] + (frame[2] - frame[0]) * 1.5f, frame[1] + (frame[3] - frame[1]) * 1.5f), 300f, 400f)

        assertEquals(15f, grown.typed.size, 0.01f)
        val after = ViewerTypedPlacement.shown(grown.typed, regular, 300f, 400f)
        assertEquals(frame[0], after[0], 0.001f)
        assertEquals(frame[1], after[1], 0.001f)
        assertArrayEquals(floatArrayOf(0f, -1f, -1f, 0f), grown.typed.matrix.copyOf(4), 0f)
    }

    @Test
    fun editingRefitsTheTextAndRecoloringKeepsItsPlace() {
        val start = ViewerTypedPlacement.placed(1, 0, "short", 400, 12f, ViewerTextPoint(250f, 100f), regular, 300f, 400f)

        val longer = ViewerTypedPlacement.edited(start, regular, "short turned much longer", 300f, 400f)
        assertEquals("short turned much longer", longer.typed.text)
        assertEquals(300f, ViewerTypedPlacement.frame(longer.typed, regular)[2], 0.001f)

        val heavier = ViewerTypedPlacement.reweighted(start, fonts.font(900), 900, 300f, 400f)
        assertEquals(900, heavier.typed.weight)

        val red = ViewerTypedPlacement.recolored(start, 0xDD0000)
        assertEquals(0xDD0000, red.color)
        assertSame(start.path, red.path)
        assertSame(start.text, red.text)
    }

    @Test
    fun anOpenBoxIsShownWiderThanItsTextButNotPastThePage() {
        val line = ViewerMarkText.LINE_HEIGHT * 12f
        val empty = ViewerMarkText.upright("", 400, 12f, 40f, 60f)
        assertArrayEquals(floatArrayOf(40f, 60f, 40f, 60f + line), ViewerTypedPlacement.frame(empty, regular), 1e-3f)
        assertArrayEquals(floatArrayOf(40f, 60f, 70f, 60f + line), ViewerTypedPlacement.shown(empty, regular, 300f, 400f), 1e-3f)

        val long = ViewerMarkText.upright("Wide enough text", 400, 12f, 40f, 60f)
        assertArrayEquals(ViewerTypedPlacement.frame(long, regular), ViewerTypedPlacement.shown(long, regular, 300f, 400f), 0f)

        val edge = ViewerMarkText.upright("", 400, 12f, 290f, 60f)
        assertArrayEquals(floatArrayOf(290f, 60f, 300f, 60f + line), ViewerTypedPlacement.shown(edge, regular, 300f, 400f), 1e-3f)
    }

    @Test
    fun theSizeLabelShowsOneDecimalAndGrowsWithTheTextWithinItsLimits() {
        assertEquals("12,0", ViewerTypedLabel.text(12f, ","))
        assertEquals("12,3", ViewerTypedLabel.text(12.34f, ","))
        assertEquals("12.4", ViewerTypedLabel.text(12.35f, "."))
        assertEquals("12,0", ViewerTypedLabel.text(11.96f, ","))
        assertEquals("4,0", ViewerTypedLabel.text(ViewerMarkText.MIN_SIZE, ","))
        assertEquals("2000,0", ViewerTypedLabel.text(ViewerMarkText.MAX_SIZE, ","))

        assertEquals(27f, ViewerTypedLabel.fontSize(12f, 3f, 20f, 60f), 1e-4f)
        assertEquals(54f, ViewerTypedLabel.fontSize(24f, 3f, 20f, 60f), 1e-4f)
        assertEquals(20f, ViewerTypedLabel.fontSize(4f, 1.8f, 20f, 60f), 0f)
        assertEquals(60f, ViewerTypedLabel.fontSize(200f, 3f, 20f, 60f), 0f)
    }

    @Test
    fun theSizeLabelSitsAboveTheFrameUnlessThereIsNoRoom() {
        assertEquals(78f, ViewerTypedLabel.top(100f, 140f, 20f, 2f, 0f), 0f)
        assertEquals(52f, ViewerTypedLabel.top(10f, 50f, 20f, 2f, 0f), 0f)
        assertEquals(142f, ViewerTypedLabel.top(100f, 140f, 20f, 2f, 90f), 0f)
    }

    @Test
    fun typedMarksAndTheOpenTextBoxComeBackFromTheJournal() {
        val first = ViewerTypedPlacement.placed(0, 0x000000, "First\nline", 400, 12f, ViewerTextPoint(40f, 60f), regular, 300f, 400f)
        val second = ViewerTypedPlacement.edited(first, regular, "First, rewritten", 300f, 400f)
        val saved = setOf(ViewerEditSession.SavedRef(1, 9, "k7"))
        val draft = ViewerTypingDraft(ViewerTypedPlacement.edited(second, regular, "First, rewritten!", 300f, 400f), 5, 300f, 400f, setOf(second), saved, changed = true)
        val bytes = journal(
            ViewerEditRecord.Performed(ViewerEditSession.Action.Add(first), IntArray(0)),
            ViewerEditRecord.Performed(ViewerEditSession.Action.Replace(setOf(first), emptySet(), second), intArrayOf(0)),
            ViewerEditRecord.Typing(draft, intArrayOf(1))
        )

        val restore = (read(bytes) as ViewerEditJournalRead.Found).restore

        assertTrue(restore.complete)
        assertEquals(2, restore.actions.size)
        val added = (restore.actions[0] as ViewerEditSession.Action.Add).mark
        val replaced = restore.actions[1] as ViewerEditSession.Action.Replace
        assertSameTyped(first, added)
        assertSame(added, replaced.marks.single())
        assertSameTyped(second, replaced.mark)
        val open = restore.typing!!
        assertSameTyped(draft.mark, open.mark)
        assertEquals(5, open.cursor)
        assertEquals(300f, open.pageWidth, 0f)
        assertEquals(400f, open.pageHeight, 0f)
        assertTrue(open.changed)
        assertSame(replaced.mark, open.editing.single())
        assertEquals(saved, open.saved)
        assertTrue(restore.fits(2))
        assertFalse(restore.fits(1))

        val closed = (read(bytes + ViewerEditJournalFormat.encode(ViewerEditRecord.Typing(null))) as ViewerEditJournalRead.Found).restore
        assertNull(closed.typing)
        assertTrue(closed.complete)
    }

    @Test
    fun aStepOnTheOpenBoxComesBackTogetherWithTheBoxOrNotAtAll() {
        val first = ViewerTypedPlacement.placed(0, 0x000000, "Moved", 400, 12f, ViewerTextPoint(40f, 60f), regular, 300f, 400f)
        val moved = ViewerTypedPlacement.moved(first, regular, 20f, 30f, 300f, 400f)
        val opened = journal(ViewerEditRecord.Typing(ViewerTypingDraft(first, 3, 300f, 400f)))
        val after = ViewerTypingDraft(first, 3, 300f, 400f, setOf(first))
        val step = ViewerEditJournalFormat.encode(ViewerEditRecord.Performed(ViewerEditSession.Action.Add(first), IntArray(0), ViewerEditRecord.Typing(after, intArrayOf(0))))

        val stepped = (read(opened + step) as ViewerEditJournalRead.Found).restore
        assertTrue(stepped.complete)
        val added = (stepped.actions.single() as ViewerEditSession.Action.Add).mark
        assertSameTyped(first, added)
        val open = stepped.typing!!
        assertSame(added, open.editing.single())
        assertFalse(open.isNew)
        assertEquals(3, open.cursor)

        val close = ViewerEditJournalFormat.encode(
            ViewerEditRecord.Performed(ViewerEditSession.Action.Replace(setOf(first), emptySet(), moved), intArrayOf(0), ViewerEditRecord.Typing(null))
        )
        val closed = (read(opened + step + close) as ViewerEditJournalRead.Found).restore
        assertTrue(closed.complete)
        assertEquals(2, closed.actions.size)
        assertSame((closed.actions[0] as ViewerEditSession.Action.Add).mark, (closed.actions[1] as ViewerEditSession.Action.Replace).marks.single())
        assertNull(closed.typing)

        val cut = (read(opened + step.copyOf(step.size - 1)) as ViewerEditJournalRead.Found).restore
        assertFalse(cut.complete)
        assertTrue(cut.actions.isEmpty())
        assertTrue(cut.typing!!.isNew)

        val wrong = ViewerEditJournalFormat.encode(ViewerEditRecord.Performed(ViewerEditSession.Action.Add(first), IntArray(0), ViewerEditRecord.Typing(after, intArrayOf(1))))
        val refused = (read(opened + wrong) as ViewerEditJournalRead.Found).restore
        assertFalse(refused.complete)
        assertTrue(refused.actions.isEmpty())
        assertTrue(refused.typing!!.isNew)
    }

    @Test
    fun aVersionOneJournalIsStillReadAndADamagedTypedStepKeepsTheStepsBeforeIt() {
        val pen = ViewerMark(0, ViewerMarkKind.PEN, 0, 2f, ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(1f, 2f, 3f, 4f)))
        val versionOne = ViewerEditJournalFormat.header(IDENTITY).also { header ->
            header[4] = 1
            val crc = CRC32().apply { update(header, 0, header.size - 4) }.value.toInt()
            for (index in 0 until 4) header[header.size - 4 + index] = (crc ushr (24 - index * 8)).toByte()
        }
        val step = ViewerEditJournalFormat.encode(ViewerEditRecord.Performed(ViewerEditSession.Action.Add(pen), IntArray(0)))

        val old = (read(versionOne + step) as ViewerEditJournalRead.Found).restore
        assertEquals(1, old.actions.size)
        assertTrue(old.complete)

        val typed = ViewerTypedPlacement.placed(0, 0, "broken", 400, 12f, ViewerTextPoint(40f, 60f), regular, 300f, 400f)
        val record = ViewerEditJournalFormat.encode(ViewerEditRecord.Performed(ViewerEditSession.Action.Add(typed), IntArray(0)))
        val weightAt = record.size - 4 - 6 * 4 - 4 - 4
        record[weightAt + 2] = 0x07
        val crc = CRC32().apply { update(record, 0, record.size - 4) }.value.toInt()
        for (index in 0 until 4) record[record.size - 4 + index] = (crc ushr (24 - index * 8)).toByte()

        val damaged = (read(journal(ViewerEditRecord.Performed(ViewerEditSession.Action.Add(pen), IntArray(0))) + record) as ViewerEditJournalRead.Found).restore
        assertEquals(1, damaged.actions.size)
        assertFalse(damaged.complete)
    }

    @Test
    fun thePlanNamesThePagesOfErasedSavedMarks() {
        val session = ViewerEditSession()
        session.perform(ViewerEditSession.Action.Erase(emptySet(), setOf(ViewerEditSession.SavedRef(1, 9, "a"), ViewerEditSession.SavedRef(3, 12, "b"))))

        assertEquals(setOf(1, 3), session.state.plan().erasedPages)

        session.perform(ViewerEditSession.Action.Blank(setOf(3)))
        assertEquals(setOf(1), session.state.plan().erasedPages)
    }

    private fun assertSameTyped(expected: ViewerMark, actual: ViewerMark) {
        assertEquals(expected.page, actual.page)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.color, actual.color)
        assertArrayEquals(expected.path.points, actual.path.points, 0f)
        val want = expected.typed
        val got = actual.typed
        assertEquals(want.text, got.text)
        assertEquals(want.weight, got.weight)
        assertEquals(want.size, got.size, 0f)
        assertArrayEquals(want.matrix, got.matrix, 0f)
    }

    private val ViewerMark.typed: ViewerMarkText
        get() = requireNotNull(text)

    private fun box(path: ViewerMarkPath): FloatArray {
        val xs = path.points.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = path.points.filterIndexed { index, _ -> index % 2 == 1 }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun journal(vararg records: ViewerEditRecord): ByteArray =
        records.fold(ViewerEditJournalFormat.header(IDENTITY)) { bytes, record -> bytes + ViewerEditJournalFormat.encode(record) }

    private fun read(bytes: ByteArray): ViewerEditJournalRead = ViewerEditJournalFormat.read(ByteArrayInputStream(bytes)::read, IDENTITY)

    private companion object {
        const val IDENTITY = "/data/cache/viewer.pdf\n1234\n5678"
        const val SAMPLE = "Árvíztűrő tükörfúrógép ÁRVÍZTŰRŐ 0123456789 .,;:!?-–()€ßЖΩ@"
        val FILES = mapOf(400 to "regular", 500 to "medium", 600 to "semibold", 700 to "bold", 900 to "black")
        val ITEXT_SUMS = mapOf(400 to 28574, 500 to 29112, 600 to 29338, 700 to 29565, 900 to 30045)
        val ITEXT_EACH = mapOf(
            400 to intArrayOf(243, 887, 570, 562, 248, 907),
            500 to intArrayOf(254, 880, 567, 568, 248, 957),
            600 to intArrayOf(259, 877, 565, 571, 248, 978),
            700 to intArrayOf(264, 875, 564, 573, 248, 999),
            900 to intArrayOf(274, 869, 562, 579, 249, 1042)
        )
    }
}
