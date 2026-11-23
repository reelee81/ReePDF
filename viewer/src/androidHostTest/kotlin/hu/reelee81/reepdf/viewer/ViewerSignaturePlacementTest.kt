package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSignaturePlacementTest {
    private val signature = ViewerSignature(2.5f, listOf(floatArrayOf(0f, 0f, 1f, 0.4f)))

    @Test
    fun aPlacedSignatureIsSixtyMillimetersWideAroundTheTapWithItsWidth() {
        val mark = ViewerSignaturePlacement.place(signature, 0x3D5FEC, 2, ViewerTextPoint(300f, 400f), 595f, 842f)

        val box = ViewerSignaturePlacement.box(mark.path, mark.width)
        assertEquals(2, mark.page)
        assertEquals(ViewerMarkKind.SIGNATURE, mark.kind)
        assertEquals(2.5f, mark.width, 0f)
        assertEquals(ViewerSignature.PAD_WIDTH_ON_PAGE + 2.5f, box[2] - box[0], 0.01f)
        assertEquals(300f, (box[0] + box[2]) / 2, 0.01f)
        assertEquals(400f, (box[1] + box[3]) / 2, 0.01f)
    }

    @Test
    fun aSignatureLargerThanThePageShrinksToFitAndStaysInside() {
        val mark = ViewerSignaturePlacement.place(signature, 0, 0, ViewerTextPoint(0f, 0f), 50f, 300f)

        val box = ViewerSignaturePlacement.box(mark.path, mark.width)
        assertArrayEquals(floatArrayOf(0f, 0f, 50f), floatArrayOf(box[0], box[1], box[2]), 0.01f)
        assertTrue(mark.width < 2.5f)
    }

    @Test
    fun movingStopsAtThePageEdges() {
        val mark = line()
        val box = ViewerSignaturePlacement.box(mark.path, mark.width)

        val moved = ViewerSignaturePlacement.moved(mark, box, 500f, -500f, 100f, 100f)

        assertArrayEquals(floatArrayOf(79f, 1f, 99f, 11f), moved.path.points, 0.001f)
    }

    @Test
    fun resizingKeepsTheProportionTheTopLeftCornerAndAMinimumSize() {
        val mark = line()
        val box = ViewerSignaturePlacement.box(mark.path, mark.width)

        val doubled = ViewerSignaturePlacement.resized(mark, box, ViewerTextPoint(9f + 44f, 9f + 24f), 100f, 100f)
        val tiny = ViewerSignaturePlacement.resized(mark, box, ViewerTextPoint(0f, 0f), 100f, 100f)
        val huge = ViewerSignaturePlacement.resized(mark, box, ViewerTextPoint(1000f, 1000f), 100f, 100f)

        assertArrayEquals(floatArrayOf(11f, 11f, 51f, 31f), doubled.path.points, 0.001f)
        assertEquals(4f, doubled.width, 0.001f)
        val tinyBox = ViewerSignaturePlacement.box(tiny.path, tiny.width)
        assertEquals(ViewerSignaturePlacement.MIN_SIDE, tinyBox[2] - tinyBox[0], 0.001f)
        val hugeBox = ViewerSignaturePlacement.box(huge.path, huge.width)
        assertEquals(100f, hugeBox[2], 0.001f)
        assertEquals(9f, hugeBox[0], 0.001f)
    }

    @Test
    fun theHandleIsTheBottomRightCornerAndInsideIsTheBox() {
        val box = floatArrayOf(10f, 10f, 50f, 30f)

        assertTrue(ViewerSignaturePlacement.onHandle(box, ViewerTextPoint(52f, 31f), 3f))
        assertFalse(ViewerSignaturePlacement.onHandle(box, ViewerTextPoint(10f, 10f), 3f))
        assertTrue(ViewerSignaturePlacement.inside(box, ViewerTextPoint(9f, 30f), 1f))
        assertFalse(ViewerSignaturePlacement.inside(box, ViewerTextPoint(8f, 30f), 1f))
    }

    private fun line() = ViewerMark(
        0, ViewerMarkKind.SIGNATURE, 0, 2f,
        ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(10f, 10f, 30f, 20f))
    )
}
