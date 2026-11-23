package hu.reelee81.reepdf.viewer

import java.io.IOException
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

class ViewerSignaturesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val editor = ViewerEditor(scope) {}
    private val store = MemoryStore()
    private val signatures = editor.signatures

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun drawLine(pad: ViewerSignaturePad) {
        pad.begin(0.1f, 0.1f, 0.01f)
        pad.extend(0.3f, 0.2f)
        pad.end(0.5f, 0.3f)
    }

    @Test
    fun aDrawnLineIsKeptInPadUnitsInsideThePad() {
        val pad = ViewerSignaturePad(0, 1)

        assertFalse(pad.isDrawing)
        pad.begin(-0.1f, 0.1f, 0.01f)
        assertTrue(pad.isDrawing)
        assertArrayEquals(floatArrayOf(0f, 0.1f), pad.current, 0f)
        pad.extend(0.5f, 0.5f)
        pad.extend(0.501f, 0.4f)
        pad.end(1.2f, Float.NaN)

        assertFalse(pad.isDrawing)
        assertNull(pad.current)
        assertArrayEquals(floatArrayOf(0f, 0.1f, 0.5f, 0.4f, 1f, 0f), pad.strokes.single(), 0f)
    }

    @Test
    fun aTapIsADotAndClearingEmptiesThePad() {
        val pad = ViewerSignaturePad(0, 1)

        pad.begin(0.2f, 0.2f, 0.01f)
        pad.end(0.2f, 0.2f)

        assertArrayEquals(floatArrayOf(0.2f, 0.2f), pad.strokes.single(), 0f)
        pad.clear()
        assertTrue(pad.isEmpty)
        assertNull(pad.signature())
    }

    @Test
    fun aConfirmedSignatureIsStoredChosenAndItsWidthRemembered() {
        signatures.attach(store)
        val stored = mutableListOf<ViewerEditSettings>()
        editor.onSettingsChanged = { stored += it }

        editor.openSignaturePad(1)
        val pad = assertPad()
        editor.chooseSignatureWidth(3)
        drawLine(pad)
        editor.confirmSignaturePad()

        assertNull(signatures.pad)
        assertEquals(2f, store.saved[1]!!.width, 0f)
        assertSame(store.saved[1], signatures.saved[1])
        assertEquals(1, editor.settings.signatureSlot)
        assertEquals(3, editor.settings.signatureWidth)
        assertEquals(1, editor.chosenSignature)
        assertEquals(2, stored.size)
    }

    @Test
    fun aFilledSlotOpensNoPadAndAnEmptyPadCannotBeConfirmed() {
        store.saved[0] = signature()
        signatures.attach(store)
        signatures.load()

        editor.openSignaturePad(0)
        assertNull(signatures.pad)
        editor.openSignaturePad(1)
        editor.confirmSignaturePad()

        assertNotNull(signatures.pad)
        assertNull(store.saved[1])
        signatures.cancelPad()
        assertNull(signatures.pad)
        assertNull(signatures.saved[1])
    }

    @Test
    fun editingOpensTheSavedLinesAndWidthAndOkStoresTheChangedSignature() {
        store.saved[0] = ViewerSignature(2.5f, listOf(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)))
        signatures.attach(store)
        signatures.load()

        editor.editSignaturePad(0)
        val pad = assertPad()
        assertEquals(4, pad.widthIndex)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), pad.strokes.single(), 0f)
        editor.chooseSignatureWidth(2)
        drawLine(pad)
        editor.confirmSignaturePad()

        val stored = store.saved[0]!!
        assertEquals(1.5f, stored.width, 0f)
        assertEquals(2, stored.strokes.size)
        assertSame(stored, signatures.saved[0])
    }

    @Test
    fun cancellingAnEditKeepsTheOldSignatureAndAnEmptySlotHasNothingToEdit() {
        val old = ViewerSignature(2f, listOf(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)))
        store.saved[0] = old
        signatures.attach(store)
        signatures.load()

        editor.editSignaturePad(1)
        assertNull(signatures.pad)
        editor.editSignaturePad(0)
        drawLine(assertPad())
        signatures.cancelPad()
        editor.editSignaturePad(0)
        assertPad().clear()
        editor.confirmSignaturePad()

        assertNotNull(signatures.pad)
        assertSame(old, store.saved[0])
        assertSame(old, signatures.saved[0])
    }

    @Test
    fun deletingAsksFirstAndTheChoiceFallsToTheOtherSignature() {
        store.saved[0] = signature()
        store.saved[1] = signature()
        signatures.attach(store)
        signatures.load()
        editor.chooseSignature(1)

        signatures.askDelete(1)
        assertEquals(1, signatures.deleting)
        assertEquals(1, editor.chosenSignature)
        signatures.confirmDelete()

        assertNull(signatures.deleting)
        assertNull(store.saved[1])
        assertNull(signatures.saved[1])
        assertEquals(0, editor.chosenSignature)
        signatures.askDelete(1)
        assertNull(signatures.deleting)
    }

    @Test
    fun withoutSignaturesNothingIsChosenAndAnEmptySlotCannotBeChosen() {
        signatures.attach(store)
        signatures.load()

        editor.chooseSignature(1)

        assertNull(editor.chosenSignature)
        assertEquals(0, editor.settings.signatureSlot)
    }

    @Test
    fun aFailedWriteIsReportedButTheDrawingStaysForThisSession() {
        val failures = mutableListOf<String>()
        signatures.onFailure = { failures += it }
        store.fail = true
        signatures.attach(store)

        editor.openSignaturePad(0)
        drawLine(assertPad())
        editor.confirmSignaturePad()

        assertEquals(listOf("disk full"), failures)
        assertNotNull(signatures.saved[0])
    }

    @Test
    fun theSecondTapOnTheSignatureToolOpensItsPanel() {
        editor.tapTool(ViewerEditTool.SIGNATURE)
        assertNull(editor.panel)
        editor.tapTool(ViewerEditTool.SIGNATURE)
        assertEquals(ViewerEditPanel.SIGNATURES, editor.panel)
        editor.tapTool(ViewerEditTool.SIGNATURE)
        assertNull(editor.panel)
    }

    @Test
    fun aSignatureIsDrawnAsOnePathOfItsSmoothedLines() {
        val signature = ViewerSignature(2f, listOf(floatArrayOf(0f, 0f, 0.5f, 0.2f, 1f, 0f), floatArrayOf(0.2f, 0.3f)))

        val path = signature.path(10f, 1f, 2f)

        assertArrayEquals(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.LINE, ViewerMarkPath.MOVE, ViewerMarkPath.LINE),
            path.verbs
        )
        assertArrayEquals(floatArrayOf(1f, 2f), path.points.copyOfRange(0, 2), 0f)
        assertArrayEquals(floatArrayOf(11f, 2f, 3f, 5f, 3f, 5f), path.points.copyOfRange(path.points.size - 6, path.points.size), 1e-5f)
        val box = signature.bounds()
        val reach = 1f / ViewerSignature.PAD_WIDTH_ON_PAGE
        assertArrayEquals(floatArrayOf(-reach, -reach, 1f + reach, 0.3f + reach), box, 1e-6f)
    }

    private fun assertPad(): ViewerSignaturePad = signatures.pad ?: throw AssertionError("No pad is open")

    private fun signature() = ViewerSignature(2f, listOf(floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)))

    private class MemoryStore : ViewerSignatureStore {
        val saved = arrayOfNulls<ViewerSignature>(ViewerSignature.SLOTS)
        var fail = false

        override suspend fun read(slot: Int): ViewerSignature? = saved[slot]

        override suspend fun write(slot: Int, signature: ViewerSignature) {
            if (fail) throw IOException("disk full")
            saved[slot] = signature
        }

        override suspend fun delete(slot: Int) {
            saved[slot] = null
        }
    }
}
